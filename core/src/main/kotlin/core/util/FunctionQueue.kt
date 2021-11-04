package core.util

import core.exception.AwaitTimeoutException
import core.exception.ReqError
import kotlinx.coroutines.*
import mu.KotlinLogging
import org.joda.time.DateTime
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicLong
import kotlin.reflect.KFunction

private val log = KotlinLogging.logger {}

typealias Ticket = Long

open class FunctionQueue<T>(private val futureCall: KFunction<T>, private val dispatcher: CoroutineDispatcher) {
    private var runningTicket = AtomicLong(0)

    /**
     * Holds [JobInfo] that are not yet assigned to coroutine execution. Chose [ConcurrentLinkedQueue] as it has no
     * fixed size limit.
     */
    private val pendingJobs = ConcurrentLinkedQueue<JobInfo>()

    /**
     * Holds [JobInfo] assigned to coroutine. The finished coroutine results will be made available in this map.
     */
    private val assignedJobs = ConcurrentHashMap<Ticket, DeferredOutput<T>>()

    /**
     * @param ticket uniq ID that identifies job.
     * @param submitted time in long, which the job was submitted via [submit]. Expected to equal [JobInfo] param.
     * @param deferred represents the future function/job call
     */
    private data class DeferredOutput<E>(val ticket: Ticket, val submitted: Long, val deferred: Deferred<E>)

    /**
     *
     * @param ticket job ticket that identifies scheduled job.
     * @param submitted time in long, which the job was submitted via [submit]. Should be same in [DeferredOutput].
     * @param arguments to be used on [futureCall].
     *
     */
    private data class JobInfo(val ticket: Ticket, val submitted: Long, val arguments: Array<Any?>) {

        /**
         *  Implemented [equals] and [hashCode] only based on ticket so that [pendingJobs].contains()
         *  can be called using just the ticket.
         */
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false

            other as JobInfo
            return ticket == other.ticket
        }

        override fun hashCode(): Int {
            return ticket.hashCode()
        }
    }


    /**
     * Execute min(number_of_jobs_in_queue, n) jobs.
     */
    fun executeN(n: Int) {
        repeat(n) {
            when (val job = pendingJobs.poll()) {
                null -> return
                else -> {
                    log.debug { "Starting job '${job.ticket}'." }
                    assignedJobs[job.ticket] = DeferredOutput(
                        job.ticket,
                        job.submitted,
                        GlobalScope.async {
                            futureCall.call(*job.arguments)
                        }
                    )
                }
            }
        }
    }

    /**
     * Submit and wait for [futureCall] output with given arguments.
     *
     * @param arguments to be passed to [futureCall]
     * @return [futureCall] output
     */
    suspend fun submitAndAwait(arguments: Array<Any?>, timeout: Long): T {
        return await(submit(arguments), timeout)
    }


    /**
     * Submit a job to scheduled execution, e.g into [pendingJobs].
     */
    private fun submit(arguments: Array<Any?>): Ticket {
        val ticket = runningTicket.incrementAndGet()
        pendingJobs.add(JobInfo(ticket, DateTime.now().millis, arguments))
        return ticket
    }


    /**
     * Job in [pendingJobs], e.g. not yet called with coroutine via [executeN]?
     */
    private fun isWaiting(ticket: Ticket): Boolean {
        return pendingJobs.contains(JobInfo(ticket, 0L, emptyArray()))
    }

    /**
     * Number of jobs pending for scheduling, e.g. not yet called with coroutine via [executeN]?
     */
    fun countWaiting(): Int {
        return pendingJobs.size
    }

    /**
     * Is there any jobs pending for scheduling, e.g. not yet called with coroutine via [executeN]?
     */
    fun hasWaiting(): Boolean {
        return pendingJobs.isNotEmpty()
    }

    /**
     * Number of jobs pending and active
     */
    fun size(): Int {
        return pendingJobs.size + assignedJobs.size
    }


    private fun isTimeOut(endTime: Long): Boolean {
        return DateTime.now().millis > endTime
    }

    private fun throwTimeOut(reason: String) {
        throw AwaitTimeoutException(
            "Scheduled job has been cancelled due to timeout. Reason: $reason.",
            ReqError.ASSESSMENT_AWAIT_TIMEOUT
        )
    }

    /**
     * Get result from [assignedJobs].
     */
    private suspend fun waitResult(ticket: Ticket): T {
        val call = assignedJobs.remove(ticket)
        log.debug {
            "Waiting for job '$ticket'. States: \nCompleted: ${call?.deferred?.isCompleted}. \n" +
                    "Canceled = ${call?.deferred?.isCancelled}. \n" +
                    "Active = ${call?.deferred?.isCancelled}."
        }
        try {
            val callResult = call?.deferred?.await()
            if (callResult == null) throwTimeOut("Job reached maximum allowed running time.")
            return callResult!!

        } finally {
            log.debug {
                "Finished job '$ticket'. States: \nCompleted: ${call?.deferred?.isCompleted}. \n" +
                        "Canceled = ${call?.deferred?.isCancelled}. \n" +
                        "Active = ${call?.deferred?.isCancelled}."
            }

        }
    }

    /**
     * Wait for job result.
     *
     * Presumes that ticket is valid.
     *
     * It is not exposed as knowing job ticket, any job and therefore any job result could be retrieved.
     */
    @Throws(AwaitTimeoutException::class)
    private suspend fun await(ticket: Ticket, timeout: Long): T {
        val endTime = DateTime.now().millis + timeout

        try {
            while (true) {
                when {
                    isTimeOut(endTime) -> throwTimeOut("Timeout in queue")
                    // Used by coroutines, using Thread.sleep in here would cause interesting concurrency issues.
                    isWaiting(ticket) -> delay(1000)
                    else -> return waitResult(ticket)
                }
            }

        } catch (ex: CancellationException) {
            throwTimeOut("Job was cancelled")
        } finally {
            pendingJobs.remove(JobInfo(ticket, 0L, emptyArray()))
            assignedJobs.remove(ticket)
        }

        // Should never reach here.
        throw RuntimeException()
    }

    /**
     * Return number of jobs scheduled to run or which are already running.
     */
    fun countRunning(): Long {
        return assignedJobs.values.sumOf { if (it.deferred.isActive) 1L else 0L }
    }

    /**
     * Clear job results, which were not retrieved or are still running.
     *
     * @param timeout in ms after which job is cancelled
     * @return number of old jobs that were set to timeout
     */
    @Synchronized
    fun clearOlder(timeout: Long): Long {
        // Is synchronized as ConcurrentHashMap states: iterators are designed to be used by only one thread at a time.
        var removed = 0L
        val currentTime = DateTime.now().millis

        assignedJobs.values.removeIf {
            val remove = it.submitted + timeout < currentTime
            if (remove) {
                it.deferred.cancel()
                removed++
            }
            remove
        }

        // Also remove old from pending queue because the queue can get long
        pendingJobs.removeIf {
            val remove = it.submitted + timeout < currentTime
            if (remove) {
                removed++
            }
            remove
        }

        log.debug { "Cleared '$removed' jobs due to timeout of $timeout." }

        return removed
    }

    override fun toString(): String {
        return "FunctionQueue(jobs=${size()})"
    }
}
