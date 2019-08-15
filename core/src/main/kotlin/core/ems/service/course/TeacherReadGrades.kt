package core.ems.service.course

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonInclude.Include
import com.fasterxml.jackson.annotation.JsonProperty
import core.conf.security.EasyUser
import core.db.*
import core.ems.service.access.assertTeacherOrAdminHasAccessToCourse
import core.ems.service.containsInList
import core.ems.service.idToLongOrInvalidReq
import core.ems.service.selectLatestSubmissionsForExercise
import mu.KotlinLogging
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.or
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.transactions.transaction
import org.springframework.security.access.annotation.Secured
import org.springframework.web.bind.annotation.*

private val log = KotlinLogging.logger {}

@RestController
@RequestMapping("/v2")
class TeacherReadGradesController {

    data class Resp(@JsonProperty("student_count") val studentCount: Int,
                    @JsonProperty("students")
                    @JsonInclude(Include.NON_NULL) val students: List<Students>,
                    @JsonProperty("exercises")
                    @JsonInclude(Include.NON_NULL) val exercises: List<Exercises>)

    data class Students(@JsonProperty("student_id") val studentId: String,
                        @JsonProperty("given_name") val givenName: String,
                        @JsonProperty("family_name") val familyName: String,
                        @JsonProperty("email") val email: String)

    data class Exercises(@JsonProperty("exercise_id") val exerciseId: String,
                         @JsonProperty("effective_title") val effectiveTitle: String,
                         @JsonProperty("grade_threshold") val gradeThreshold: Int,
                         @JsonProperty("student_visible") val studentVisible: Boolean,
                         @JsonProperty("grades") @JsonInclude(Include.NON_NULL) val grades: List<Grade?>?)

    data class Grade(@JsonProperty("student_id") val studentId: String,
                     @JsonProperty("grade") val grade: Int,
                     @JsonProperty("grader_type") val graderType: GraderType,
                     @JsonProperty("feedback") @JsonInclude(Include.NON_NULL) val feedback: String?)


    @Secured("ROLE_TEACHER", "ROLE_ADMIN")
    @GetMapping("/courses/teacher/{courseId}/grades")
    fun controller(@PathVariable("courseId") courseIdStr: String,
                   @RequestParam("offset", required = false) offsetStr: String?,
                   @RequestParam("limit", required = false) limitStr: String?,
                   @RequestParam("search", required = false) search: String?,
                   caller: EasyUser): Resp {

        log.debug { "Getting grades for ${caller.id} on course $courseIdStr" }

        val courseId = courseIdStr.idToLongOrInvalidReq()
        assertTeacherOrAdminHasAccessToCourse(caller, courseId)

        val query = search?.split(" ")

        return selectGradesResponse(courseId, offsetStr?.toIntOrNull(), limitStr?.toIntOrNull(), query)
    }
}

private fun selectGradesResponse(courseId: Long, offset: Int?, limit: Int?, queryWords: List<String>?): TeacherReadGradesController.Resp {
    val studentCount = transaction { StudentCourseAccess.select { StudentCourseAccess.course eq courseId }.count() }
    val students = selectStudentsOnCourse(courseId, queryWords)

    val exercises = selectExercisesOnCourse(courseId,
            students.map { it.studentId },
            offset ?: 0,
            limit ?: Int.MAX_VALUE)

    return TeacherReadGradesController.Resp(studentCount, students, exercises);
}

private fun selectStudentsOnCourse(courseId: Long, queryWords: List<String>?): List<TeacherReadGradesController.Students> {
    return transaction {
        (Student innerJoin StudentCourseAccess)
                .slice(Student.id, Student.email, Student.givenName, Student.familyName)
                .select {
                    when (queryWords) {
                        null -> StudentCourseAccess.course eq courseId
                        else -> {
                            StudentCourseAccess.course eq courseId and
                                    ((Student.id containsInList (queryWords)) or
                                            (Student.email containsInList (queryWords)) or
                                            (Student.givenName containsInList queryWords) or
                                            (Student.familyName containsInList (queryWords)))
                        }
                    }
                }
                .map {
                    TeacherReadGradesController.Students(
                            it[Student.id].value,
                            it[Student.givenName],
                            it[Student.familyName],
                            it[Student.email]
                    )
                }
    }
}

private fun selectExercisesOnCourse(courseId: Long, studentIds: List<String>, offset: Int, limit: Int): List<TeacherReadGradesController.Exercises> {
    return transaction {
        (CourseExercise innerJoin Exercise innerJoin ExerciseVer)
                .slice(CourseExercise.id,
                        CourseExercise.gradeThreshold,
                        CourseExercise.studentVisible,
                        CourseExercise.orderIdx,
                        ExerciseVer.title,
                        ExerciseVer.validTo,
                        CourseExercise.titleAlias)
                .select { CourseExercise.course eq courseId and ExerciseVer.validTo.isNull() }
                .limit(limit, offset)
                .orderBy(CourseExercise.orderIdx to true)
                .map { ex ->
                    TeacherReadGradesController.Exercises(
                            ex[CourseExercise.id].value.toString(),
                            ex[CourseExercise.titleAlias] ?: ex[ExerciseVer.title],
                            ex[CourseExercise.gradeThreshold],
                            ex[CourseExercise.studentVisible],
                            selectLatestSubmissionsForExercise(ex[CourseExercise.id].value).mapNotNull {
                                selectLatestGradeForSubmission(it, studentIds)
                            }
                    )
                }
    }
}


fun selectLatestGradeForSubmission(submissionId: Long, studentIds: List<String>): TeacherReadGradesController.Grade? {
    val studentId = Submission
            .select { Submission.id eq submissionId }
            .map { it[Submission.student] }
            .firstOrNull()?.value.toString()

    if (!studentIds.contains(studentId)) return null

    val teacherGrade = TeacherAssessment
            .slice(TeacherAssessment.submission,
                    TeacherAssessment.createdAt,
                    TeacherAssessment.grade,
                    TeacherAssessment.feedback)
            .select { TeacherAssessment.submission eq submissionId }
            .orderBy(TeacherAssessment.createdAt to false)
            .limit(1)
            .map { assessment ->
                TeacherReadGradesController.Grade(studentId,
                        assessment[TeacherAssessment.grade],
                        GraderType.TEACHER,
                        assessment[TeacherAssessment.feedback])
            }
            .firstOrNull()

    if (teacherGrade != null)
        return teacherGrade

    return AutomaticAssessment
            .slice(AutomaticAssessment.submission,
                    AutomaticAssessment.createdAt,
                    AutomaticAssessment.grade,
                    AutomaticAssessment.feedback)
            .select { AutomaticAssessment.submission eq submissionId }
            .orderBy(AutomaticAssessment.createdAt to false)
            .limit(1)
            .map { assessment ->
                TeacherReadGradesController.Grade(studentId,
                        assessment[AutomaticAssessment.grade],
                        GraderType.AUTO,
                        assessment[AutomaticAssessment.feedback])
            }
            .firstOrNull()
}