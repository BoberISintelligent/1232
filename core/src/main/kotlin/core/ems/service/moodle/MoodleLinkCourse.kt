package core.ems.service.moodle

import com.fasterxml.jackson.annotation.JsonProperty
import core.conf.security.EasyUser
import core.db.Course
import core.ems.service.assertCourseExists
import core.ems.service.assertTeacherOrAdminHasAccessToCourse
import core.ems.service.idToLongOrInvalidReq
import mu.KotlinLogging
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.springframework.security.access.annotation.Secured
import org.springframework.web.bind.annotation.*
import javax.validation.Valid
import javax.validation.constraints.NotBlank
import javax.validation.constraints.Size

private val log = KotlinLogging.logger {}

@RestController
@RequestMapping("/v2")
class MoodleLinkCourseController(val moodleStudentsSyncService: MoodleStudentsSyncService) {


    data class Req(@JsonProperty("moodle_short_name") @field:NotBlank @field:Size(max = 500) val moodleShortName: String,
                   @JsonProperty("sync_students", required = true) val syncStudents: Boolean,
                   @JsonProperty("sync_grades", required = true) val syncGrades: Boolean)

    data class Resp(@JsonProperty("students_synced") val studentsSynced: Int,
                    @JsonProperty("pending_students_synced") val pendingStudentsSynced: Int)


    @Secured("ROLE_TEACHER", "ROLE_ADMIN")
    @PostMapping("/courses/{courseId}/moodle")
    fun controller(@PathVariable("courseId") courseIdStr: String, @Valid @RequestBody dto: Req, caller: EasyUser): Resp {
        log.debug { "${caller.id} is linking Moodle course '${dto.moodleShortName}' with '$courseIdStr' (sync students: ${dto.syncStudents}, sync grades: ${dto.syncGrades})" }

        val courseId = courseIdStr.idToLongOrInvalidReq()

        assertTeacherOrAdminHasAccessToCourse(caller, courseId)
        assertCourseExists(courseId)

        linkCourse(courseId, dto.moodleShortName, dto.syncStudents, dto.syncGrades)

        return if (dto.syncStudents) {
            val moodleStudents = moodleStudentsSyncService.queryStudents(dto.moodleShortName)
            val syncedStudents = moodleStudentsSyncService.syncCourse(moodleStudents, courseId, dto.moodleShortName)
            Resp(syncedStudents.syncedStudents, syncedStudents.syncedPendingStudents)
        } else {
            Resp(0, 0)
        }
    }
}


private fun linkCourse(courseId: Long, moodleShortname: String, syncStudents: Boolean, syncGrades: Boolean) {
    transaction {
        Course.update({ Course.id eq courseId }) {
            it[moodleShortName] = moodleShortname
            it[moodleSyncStudents] = syncStudents
            it[moodleSyncGrades] = syncGrades
        }
    }
}