package core.exception

import com.fasterxml.jackson.annotation.JsonProperty


enum class ReqError(val errorCodeStr: String) {
    ENTITY_WITH_ID_NOT_FOUND("ENTITY_WITH_ID_NOT_FOUND"),
    ROLE_NOT_ALLOWED("ROLE_NOT_ALLOWED"),
    ASSESSMENT_AWAIT_TIMEOUT("ASSESSMENT_AWAIT_TIMEOUT"),
    INVALID_PARAMETER_VALUE("INVALID_PARAMETER_VALUE"),
    MOODLE_LINKING_ERROR("MOODLE_LINKING_ERROR"),
    MOODLE_EMPTY_RESPONSE("MOODLE_EMPTY_RESPONSE"),
    MOODLE_GRADE_SYNC_ERROR("MOODLE_GRADE_SYNC_ERROR"),
    ACCOUNT_EMAIL_NOT_FOUND("ACCOUNT_EMAIL_NOT_FOUND"),
    ARTICLE_NOT_FOUND("ARTICLE_NOT_FOUND"),
    ARTICLE_ALIAS_IN_USE("ARTICLE_ALIAS_IN_USE"),
    NO_COURSE_ACCESS("NO_COURSE_ACCESS"),
    NO_GROUP_ACCESS("NO_GROUP_ACCESS"),
    EXERCISE_NOT_AUTOASSESSABLE("EXERCISE_NOT_AUTOASSESSABLE"),
    NO_EXERCISE_ACCESS("NO_EXERCISE_ACCESS"),
    EXERCISE_ALREADY_ON_COURSE("EXERCISE_ALREADY_ON_COURSE"),
    GROUP_NOT_EMPTY("GROUP_NOT_EMPTY"),
    NO_DIR_ACCESS("NO_DIR_ACCESS"),
    DIR_NOT_EMPTY("DIR_NOT_EMPTY"),
    EXERCISE_USED_ON_COURSE("EXERCISE_USED_ON_COURSE"),
    ENTITY_WITH_ID_ALREADY_EXISTS("ENTITY_WITH_ID_ALREADY_EXISTS"),
    MOODLE_SYNC_IN_PROGRESS("MOODLE_SYNC_IN_PROGRESS"),

    CANNOT_MODIFY_OWN("CANNOT_MODIFY_OWN"),
    ACCOUNT_MIGRATION_FAILED("ACCOUNT_MIGRATION_FAILED"),

    TSL_COMPILE_FAILED("TSL_COMPILE_FAILED"),

    COURSE_EXERCISE_CLOSED("COURSE_EXERCISE_CLOSED"),
    EXERCISE_WRONG_SOLUTION_TYPE("EXERCISE_WRONG_SOLUTION_TYPE"),
    STUDENT_NOT_ON_COURSE("STUDENT_NOT_ON_COURSE")
}


data class RequestErrorResponse(
    @JsonProperty("id", required = true) val id: String,
    @JsonProperty("code", required = true) val code: String?,
    @JsonProperty("attrs", required = true) val attrs: Map<String, String>,
    @JsonProperty("log_msg", required = true) val logMsg: String
)
