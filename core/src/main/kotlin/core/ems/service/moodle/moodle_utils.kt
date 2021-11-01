package core.ems.service.moodle

import core.db.Course
import core.exception.InvalidRequestException
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.transactions.transaction


fun assertCourseIsMoodleLinked(courseId: Long) {
    if (!isCourseMoodleLinked(courseId)) {
        throw InvalidRequestException("Course $courseId is not linked with Moodle")
    }
}

fun isCourseMoodleLinked(courseId: Long): Boolean {
    return transaction {
        Course.select {
            Course.id eq courseId and Course.moodleShortName.isNotNull()
        }.count() > 0
    }
}

fun selectCourseShortName(courseId: Long): String? {
    return transaction {
        Course.slice(Course.moodleShortName)
            .select {
                Course.id eq courseId
            }.map { it[Course.moodleShortName] }
            .singleOrNull()
    }
}
