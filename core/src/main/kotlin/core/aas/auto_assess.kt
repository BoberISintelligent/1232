package core.aas

import com.fasterxml.jackson.annotation.JsonProperty
import core.db.*
import core.exception.InvalidRequestException
import core.util.FunctionQueue
import kotlinx.coroutines.Dispatchers
import mu.KotlinLogging
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import org.springframework.web.client.RestTemplate
import java.util.*
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.absoluteValue
import kotlin.math.min


private const val EXECUTOR_GRADE_URL = "/v1/grade"

private val log = KotlinLogging.logger {}


@Service
class FutureAutoGradeService {
    @Value("\${easy.core.auto-assess.timeout-check.clear-older-than.ms}")
    private lateinit var allowedRunningTimeMs: String

    @Value("\${easy.core.auto-assess.allowed-wait-for-user.ms}")
    private lateinit var allowedWaitingTimeUserMs: String

    /**
     * [submitAndAwait] expects that map of [executors] is synced with database. However [addExecutorsFromDB] can be called
     * anytime, therefore [executorLock] is used to synchronize [submitAndAwait] and [addExecutorsFromDB] to avoid
     * cases where [submitAndAwait] expects to see an executor in [executors] that otherwise might be already in db, but
     * not in map.
     */
    private val executorLock = UUID.randomUUID().toString()

    val executors: MutableMap<Long, SortedMap<PriorityLevel, FunctionQueue<AutoAssessment>>> = mutableMapOf()

    // Global index for picking the next queue
    private var queuePickerIndex = AtomicInteger()

    /**
     * Delegator for [callExecutor] as [callExecutor] is private, but reflective access is needed by [FunctionQueue].
     */
    fun callExecutorInFutureJobService(executor: CapableExecutor, request: ExecutorRequest): AutoAssessment {
        return callExecutor(executor, request)
    }

    /**
     * Autograde maximum number of possible submissions assigned to this executor while considering current load.
     *
     * No guarantee is made how many submissions can be graded.
     *
     * @param executorId Which executor to use for grading?
     */
    private fun autograde(executorId: Long) {
        // Pointer, jobs may be added to it later
        var executorPriorityQueues = executors[executorId]?.values?.toList() ?: listOf()

        // Number of jobs planned to be executed, ensures that loop finishes
        val executableCount = min(
            executorPriorityQueues.sumOf { it.countWaiting() },
            getExecutorMaxLoad(executorId) - executorPriorityQueues.sumOf { it.countRunning().toInt() }
        )

        if (executableCount != 0) log.debug { "Executor '$executorId' is executing $executableCount jobs" }

        repeat(executableCount) {
            executorPriorityQueues = executorPriorityQueues.filter { it.hasWaiting() }
            if (executorPriorityQueues.isEmpty()) return

            val queuePicker = queuePickerIndex.incrementAndGet().absoluteValue
            val item = executorPriorityQueues[queuePicker % executorPriorityQueues.size]
            item.executeN(1)
        }
    }

    //  fixedDelay doesn't start a next call before the last one has finished
    @Scheduled(fixedDelayString = "\${easy.core.auto-assess.fixed-delay.ms}")
    private fun grade() {
        executors.keys.forEach { executorId -> autograde(executorId) }
    }


    fun submitAndAwait(
        autoExerciseId: EntityID<Long>,
        submission: String,
        priority: PriorityLevel
    ): AutoAssessment {
        synchronized(executorLock) {

            val autoExercise = getAutoExerciseDetails(autoExerciseId)
            val request = mapToExecutorRequest(autoExercise, submission)

            // If here and the map is empty, then probably there has been a server restart, force sync.
            if (executors.isEmpty()) {
                addExecutorsFromDB()
            }

            val executors = getCapableExecutors(autoExerciseId).filter { !it.drain }.toSet()
            val selected = selectExecutor(executors)

            log.debug { "Scheduling and waiting for priority '$priority' autoExerciseId '$autoExerciseId'." }

            return this.executors
                .getOrElse(selected.id) {
                    throw ExecutorException("Out of sync. Did you use API to add/remove executor '${selected.id}'?")
                }
                .getOrElse(priority) {
                    throw ExecutorException("Executor (${selected.id}) does not have queue with '$priority'.")
                }
                .submitAndAwait(
                    arrayOf(selected, request), timeout = allowedWaitingTimeUserMs.toLong()
                )
        }
    }


    @Scheduled(cron = "\${easy.core.auto-assess.timeout-check.cron}")
    @Synchronized
    private fun timeout() {
        val timeout = allowedRunningTimeMs.toLong()
        val removed = executors.values.flatMap { it.values }.sumOf { it.clearOlder(timeout) }
        log.debug { "Checked for timeout in scheduled call results: Removed '$removed' older than '$timeout' ms." }
    }


    /**
     *  Remove executor.
     */
    fun deleteExecutor(executorId: Long, force: Boolean) {
        synchronized(executorLock) {

            return transaction {
                val executorQuery = Executor.select { Executor.id eq executorId }
                val executorExists = executorQuery.count() == 1L
                val currentLoad = executorQuery.map { it[Executor.load] }.singleOrNull()
                if (!executorExists) {
                    throw InvalidRequestException("Executor with id $executorId not found")

                    // TODO: waiting jobs are not considered. Get all priorities size() from executor map.
                    // TODO: executors[executorId].values.map { it.size() }.sum() != 0 ??
                } else if (!force && currentLoad!! > 0) {
                    throw InvalidRequestException("Executor load != 0 (is $currentLoad). Set 'force'=true for forced removal.")
                } else {
                    ExecutorContainerImage.deleteWhere { ExecutorContainerImage.executor eq executorId }
                    AutoExerciseExecutor.deleteWhere { AutoExerciseExecutor.executor eq executorId }
                    Executor.deleteWhere { Executor.id eq executorId }
                    executors.remove(executorId)
                    log.info { "Executor '$executorId' deleted" }
                }
            }
        }
    }


    fun addExecutorsFromDB() {
        synchronized(executorLock) {
            val executorIdsFromDB = getAvailableExecutorIds()

            val new = executorIdsFromDB.filter {
                executors.putIfAbsent(
                    it, sortedMapOf(
                        PriorityLevel.AUTHENTICATED to FunctionQueue(
                            ::callExecutorInFutureJobService,
                            Dispatchers.Default
                        ),
                        PriorityLevel.ANONYMOUS to FunctionQueue(::callExecutorInFutureJobService, Dispatchers.Default)
                    )
                ) == null
            }.size
            log.debug { "Checked for new executors. Added total '$new' new executors." }
        }
    }
}


private data class AutoAssessExerciseDetails(
    val gradingScript: String, val containerImage: String, val maxTime: Int, val maxMem: Int,
    val assets: List<AutoAssessExerciseAsset>
)

private data class AutoAssessExerciseAsset(
    val fileName: String, val fileContent: String
)

data class CapableExecutor(
    val id: Long, val name: String, val baseUrl: String, val load: Int, val maxLoad: Int, val drain: Boolean
)

data class AutoAssessment(
    val grade: Int, val feedback: String
)


private fun getAutoExerciseDetails(autoExerciseId: EntityID<Long>): AutoAssessExerciseDetails {
    return transaction {
        val assets = Asset
            .select { Asset.autoExercise eq autoExerciseId }
            .map {
                AutoAssessExerciseAsset(
                    it[Asset.fileName],
                    it[Asset.fileContent]
                )
            }

        AutoExercise.select { AutoExercise.id eq autoExerciseId }
            .map {
                AutoAssessExerciseDetails(
                    it[AutoExercise.gradingScript],
                    it[AutoExercise.containerImage].value,
                    it[AutoExercise.maxTime],
                    it[AutoExercise.maxMem],
                    assets
                )
            }
            .first()
    }
}


data class ExecutorRequest(
    @JsonProperty("submission") val submission: String,
    @JsonProperty("grading_script") val gradingScript: String,
    @JsonProperty("assets") val assets: List<ExecutorRequestAsset>,
    @JsonProperty("image_name") val imageName: String,
    @JsonProperty("max_time_sec") val maxTime: Int,
    @JsonProperty("max_mem_mb") val maxMem: Int
)

data class ExecutorRequestAsset(
    @JsonProperty("file_name") val fileName: String,
    @JsonProperty("file_content") val fileContent: String
)


private fun mapToExecutorRequest(exercise: AutoAssessExerciseDetails, submission: String): ExecutorRequest =
    ExecutorRequest(
        submission,
        exercise.gradingScript,
        exercise.assets.map { ExecutorRequestAsset(it.fileName, it.fileContent) },
        exercise.containerImage,
        exercise.maxTime,
        exercise.maxMem
    )

private fun getCapableExecutors(autoExerciseId: EntityID<Long>): Set<CapableExecutor> {
    return transaction {
        // automatic_exercise = AutoExercise.id == autoExerciseId
        // executor_container_image = ExecutorContainerImage.containerImageId == ae.containerImageId
        // executorId = executor_container_image.executorId

        (Executor innerJoin AutoExerciseExecutor)
            .select { AutoExerciseExecutor.autoExercise eq autoExerciseId }
            .map {
                CapableExecutor(
                    it[Executor.id].value,
                    it[Executor.name],
                    it[Executor.baseUrl],
                    it[Executor.load],
                    it[Executor.maxLoad],
                    it[Executor.drain]
                )
            }
            .toSet()
    }
}

private fun selectExecutor(executors: Set<CapableExecutor>): CapableExecutor {
    if (executors.isEmpty()) {
        throw NoExecutorsException("No capable executors found for this auto exercise")
    }

    val executor = executors.reduce { bestExec, currentExec ->
        if (currentExec.load / currentExec.maxLoad < bestExec.load / bestExec.maxLoad) currentExec else bestExec
    }

    if (executor.load >= executor.maxLoad) {
        throw ExecutorOverloadException("All capable executors at max load")
    }
    return executor
}


data class ExecutorResponse(
    @JsonProperty("grade") val grade: Int,
    @JsonProperty("feedback") val feedback: String
)

private fun callExecutor(executor: CapableExecutor, request: ExecutorRequest): AutoAssessment {
    incExecutorLoad(executor.id)
    try {
        log.info { "Calling executor ${executor.name}, load is now ${getExecutorLoad(executor.id)}" }

        val template = RestTemplate()
        val responseEntity = template.postForEntity(
            executor.baseUrl + EXECUTOR_GRADE_URL, request, ExecutorResponse::class.java
        )

        if (responseEntity.statusCode.isError) {
            log.error { "Executor error ${responseEntity.statusCodeValue} with request $request" }
            throw ExecutorException("Executor error (${responseEntity.statusCodeValue})")
        }

        val response = responseEntity.body
        if (response == null) {
            log.error { "Executor response is empty with request $request" }
            throw ExecutorException("Executor error (empty body)")
        }

        return AutoAssessment(response.grade, response.feedback)

    } finally {
        decExecutorLoad(executor.id)
        log.info { "Call finished to executor ${executor.name}, load is now ${getExecutorLoad(executor.id)}" }
    }
}

private fun incExecutorLoad(executorId: Long) {
    transaction {
        Executor.update({ Executor.id eq executorId }) {
            with(SqlExpressionBuilder) {
                it.update(load, load + 1)
            }
        }
    }
}

private fun decExecutorLoad(executorId: Long) {
    transaction {
        Executor.update({ Executor.id eq executorId }) {
            with(SqlExpressionBuilder) {
                it.update(load, load - 1)
            }
        }
    }
}

private fun getExecutorLoad(executorId: Long): Int {
    return transaction {
        Executor.slice(Executor.load)
            .select { Executor.id eq executorId }
            .map { it[Executor.load] }[0]
    }
}

private fun getExecutorMaxLoad(executorId: Long): Int {
    return transaction {
        Executor.slice(Executor.maxLoad)
            .select { Executor.id eq executorId }
            .map { it[Executor.maxLoad] }[0]
    }
}


private fun getAvailableExecutorIds(): List<Long> {
    return transaction {
        Executor.slice(Executor.id).selectAll().map { it[Executor.id].value }
    }
}
