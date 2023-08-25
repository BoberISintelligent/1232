package pages.course_exercise

import AppProperties
import Auth
import DateSerializer
import EzDate
import Icons
import MathJax
import PageName
import PaginationConf
import Role
import Str
import cache.BasicCourseInfo
import debug
import debugFunStart
import emptyToNull
import getContainer
import getLastPageOffset
import highlightCode
import isNotNullAndTrue
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.await
import kotlinx.coroutines.launch
import kotlinx.dom.addClass
import kotlinx.dom.clear
import kotlinx.dom.removeClass
import kotlinx.serialization.Serializable
import libheaders.CodeMirror
import libheaders.Materialize
import libheaders.focus
import libheaders.tabHandler
import lightboxExerciseImages
import onSingleClickWithDisabled
import org.w3c.dom.*
import pages.EasyPage
import pages.Title
import pages.course_exercises_list.UpdateCourseExerciseModalComp
import pages.exercise.ExercisePage
import pages.exercise.TestingTabComp
import pages.exercise.formatFeedback
import pages.sidenav.ActivePage
import pages.sidenav.Sidenav
import queries.*
import restore
import rip.kspar.ezspa.*
import saveAsFile
import successMessage
import tmRender
import toEstonianString
import warn
import kotlin.js.Date
import kotlin.math.min

object ExerciseSummaryPage : EasyPage() {

    private const val PAGE_STEP = AppProperties.SUBMISSIONS_ROWS_ON_PAGE

    @Serializable
    data class TeacherExercise(
        val exercise_id: String,
        val title: String,
        val title_alias: String?,
        val instructions_html: String?,
        val instructions_adoc: String?,
        val text_html: String?,
        val text_adoc: String?,
        val student_visible: Boolean,
        @Serializable(with = DateSerializer::class)
        val student_visible_from: Date?,
        @Serializable(with = DateSerializer::class)
        val hard_deadline: Date?,
        @Serializable(with = DateSerializer::class)
        val soft_deadline: Date?,
        val grader_type: GraderType,
        val threshold: Int,
        @Serializable(with = DateSerializer::class)
        val last_modified: Date,
        val assessments_student_visible: Boolean,
        val grading_script: String?,
        val container_image: String?,
        val max_time_sec: Int?,
        val max_mem_mb: Int?,
        val assets: List<AutoAsset>?,
        val executors: List<AutoExecutor>?
    )

    @Serializable
    data class AutoAsset(
        val file_name: String,
        val file_content: String
    )

    @Serializable
    data class AutoExecutor(
        val id: String,
        val name: String
    )

    enum class GraderType {
        AUTO, TEACHER
    }

    @Serializable
    data class AutoassResult(
        val grade: Int,
        val feedback: String?
    )

    @Serializable
    data class Groups(
        val groups: List<Group>,
        val self_is_restricted: Boolean,
    )

    @Serializable
    data class Group(
        val id: String,
        val name: String
    )

    @Serializable
    data class TeacherStudents(
        val student_count: Int,
        val students: List<TeacherStudent>
    )

    @Serializable
    data class TeacherStudent(
        val student_id: String,
        val given_name: String,
        val family_name: String,
        @Serializable(with = DateSerializer::class)
        val submission_time: Date?,
        val grade: Int?,
        val graded_by: GraderType?,
        val groups: String? = null
    )

    @Serializable
    data class TeacherSubmissions(
        val submissions: List<TeacherSubmission>,
        val count: Int
    )

    @Serializable
    data class TeacherSubmission(
        val id: String,
        val solution: String,
        @Serializable(with = DateSerializer::class)
        val created_at: Date,
        val grade_auto: Int?,
        val feedback_auto: String?,
        val grade_teacher: Int?,
        val feedback_teacher: String?
    )


    private var rootComp: Component? = null

    override val pageName: Any
        get() = PageName.EXERCISE_SUMMARY

    override val sidenavSpec: Sidenav.Spec
        get() = Sidenav.Spec(pathParams.courseId, ActivePage.STUDENT_EXERCISE)

    override val pathSchema = "/courses/{courseId}/exercises/{courseExerciseId}/**"

    data class PathParams(val courseId: String, val courseExerciseId: String)

    private val pathParams: PathParams
        get() = parsePathParams().let {
            PathParams(it["courseId"], it["courseExerciseId"])
        }

    override val courseId
        get() = pathParams.courseId

    val courseExerciseId
        get() = pathParams.courseExerciseId

    override fun build(pageStateStr: String?) {
        super.build(pageStateStr)
        val scrollPosition = pageStateStr.getScrollPosFromState()

        when (Auth.activeRole) {
            Role.STUDENT -> doInPromise {
                getHtml().addClass("wui3", "full-width")
                Sidenav.refresh(sidenavSpec, true)

                val r = StudentCourseExerciseComp(courseId, courseExerciseId, ::setWildcardPath)
                rootComp = r
                r.createAndBuild().await()
                scrollPosition?.restore()

                Navigation.catchNavigation {
                    r.hasUnsavedChanges()
                }
            }

            Role.TEACHER, Role.ADMIN -> buildTeacherExercise(
                pathParams.courseId,
                pathParams.courseExerciseId,
                Auth.activeRole == Role.ADMIN
            )
        }
    }

    override fun onPreNavigation() {
        updateStateWithScrollPos()
    }

    override fun destruct() {
        super.destruct()
        rootComp?.destroy()
        rootComp = null
        getHtml().removeClass("wui3", "full-width")
    }

    private fun setWildcardPath(wildcardPathSuffix: String) {
        updateUrl(link(courseId, courseExerciseId) + wildcardPathSuffix)
    }

    // Bit of a hack until we migrate this page
    private val updateModalDst = IdGenerator.nextId()

    private fun buildTeacherExercise(courseId: String, courseExerciseId: String, isAdmin: Boolean) =
        MainScope().launch {
            val fl = debugFunStart("buildTeacherExercise")

            getContainer().innerHTML = tmRender(
                "tm-teach-exercise", mapOf(
                    "exerciseLabel" to Str.tabExerciseLabel(),
                    "testingLabel" to Str.tabTestingLabel(),
                    "studentSubmLabel" to Str.tabSubmissionsLabel()
                )
            )

            val tabs = Materialize.Tabs.init(getElemById("tabs"))

            getElemById("exercise").innerHTML = tmRender("tm-loading-exercise")

            // Could be optimised to load exercise details & students in parallel,
            // requires passing an exercisePromise to buildStudents since the threshold is needed for painting
            val exerciseDetails = buildTeacherSummaryAndCrumbs(courseId, courseExerciseId, isAdmin)
            if (exerciseDetails.grader_type == GraderType.AUTO)
                buildTeacherTesting(courseId, exerciseDetails.exercise_id)
            buildTeacherStudents(courseId, courseExerciseId, exerciseDetails.exercise_id, exerciseDetails.threshold)

            initTooltips()

            tabs.updateTabIndicator()
            fl?.end()
        }

    private suspend fun buildTeacherSummaryAndCrumbs(
        courseId: String,
        courseExerciseId: String,
        isAdmin: Boolean
    ): TeacherExercise {
        val fl = debugFunStart("buildTeacherSummaryAndCrumbs")

        val exercisePromise = fetchEms(
            "/teacher/courses/$courseId/exercises/$courseExerciseId", ReqMethod.GET,
            successChecker = { http200 }, errorHandler = ErrorHandlers.noCourseAccessMsg
        )

        val courseTitle = BasicCourseInfo.get(courseId).await().effectiveTitle
        val exercise = exercisePromise.await()
            .parseTo(TeacherExercise.serializer()).await()

        val effectiveTitle = exercise.title_alias ?: exercise.title

        Title.update {
            it.pageTitle = effectiveTitle
            it.parentPageTitle = courseTitle
        }

        debug { "Exercise ID: ${exercise.exercise_id} (course exercise ID: $courseExerciseId, title: ${exercise.title}, title alias: ${exercise.title_alias})" }

        getElemById("crumbs").innerHTML = tmRender(
            "tm-exercise-crumbs", mapOf(
                "coursesLabel" to Str.myCourses(),
                "coursesHref" to "/courses",
                "courseTitle" to courseTitle,
                "courseHref" to "/courses/$courseId/exercises",
                "exerciseTitle" to effectiveTitle
            )
        )

        val exerciseMap = mutableMapOf<String, Any?>(
            "softDeadlineLabel" to Str.softDeadlineLabel(),
            "hardDeadlineLabel" to Str.hardDeadlineLabel(),
            "graderTypeLabel" to Str.graderTypeLabel(),
            "thresholdLabel" to Str.thresholdLabel(),
            "studentVisibleLabel" to Str.studentVisibleLabel(),
            "studentVisibleFromTimeLabel" to Str.studentVisibleFromTimeLabel(),
            "assStudentVisibleLabel" to Str.assStudentVisibleLabel(),
            "lastModifiedLabel" to Str.lastModifiedLabel(),
            "softDeadline" to exercise.soft_deadline?.toEstonianString(),
            "hardDeadline" to exercise.hard_deadline?.toEstonianString(),
            "graderType" to if (exercise.grader_type == GraderType.AUTO) Str.graderTypeAuto() else Str.graderTypeTeacher(),
            "threshold" to exercise.threshold,
            "studentVisible" to Str.translateBoolean(exercise.student_visible),
            "studentVisibleFromTime" to if (!exercise.student_visible) exercise.student_visible_from?.toEstonianString() else null,
            "assStudentVisible" to Str.translateBoolean(exercise.assessments_student_visible),
            "lastModified" to exercise.last_modified.toEstonianString(),
            "exerciseTitle" to effectiveTitle,
            "exerciseText" to exercise.text_html,
            "updateModalDst" to updateModalDst,
        )

        val aaFiles =
            if (exercise.grading_script != null) {
                val assetFiles = exercise.assets ?: emptyList()
                val aaFiles = listOf(AutoAsset("evaluate.sh", exercise.grading_script)) + assetFiles
                exerciseMap["aaTitle"] = Str.aaTitle()
                exerciseMap["aaFiles"] = aaFiles.mapIndexed { i, file ->
                    objOf(
                        "fileName" to file.file_name,
                        "fileIdx" to i
                    )
                }.toTypedArray()

                aaFiles
            } else null

        getElemById("exercise").innerHTML = tmRender("tm-teach-exercise-summary", exerciseMap)


        Sidenav.replacePageSection(
            Sidenav.PageSection(
                effectiveTitle, listOf(
                    Sidenav.Action(Icons.settings, "Ülesande sätted") {
                        val m = UpdateCourseExerciseModalComp(
                            courseId,
                            UpdateCourseExerciseModalComp.CourseExercise(
                                courseExerciseId,
                                exercise.title,
                                exercise.title_alias,
                                exercise.student_visible,
                                exercise.student_visible_from?.let { EzDate(it) },
                            ),
                            null,
                            dstId = updateModalDst
                        )

                        m.createAndBuild().await()
                        val modalReturn = m.openWithClosePromise().await()
                        m.destroy()
                        if (modalReturn != null) {
                            build(null)
                        }
                    },
                    Sidenav.Link(Icons.library, "Vaata ülesandekogus", ExercisePage.link(exercise.exercise_id))
                )
            )
        )


        lightboxExerciseImages()
        highlightCode()

        if (aaFiles != null) {
            initAaFileEditor(aaFiles)
        }

        MathJax.formatPageIfNeeded(exercise.instructions_html.orEmpty(), exercise.text_html.orEmpty())

        fl?.end()
        return exercise
    }

    private fun initAaFileEditor(aaFiles: List<AutoAsset>) {
        val docs = aaFiles.mapIndexed { i, file ->
            val mode = if (i == 0) "shell" else "python"
            CodeMirror.Doc(file.file_content, mode)
        }

        val editor = CodeMirror.fromTextArea(
            getElemById("aa-files"),
            objOf(
                "mode" to "python",
                "theme" to "idea",
                "lineNumbers" to true,
                "autoRefresh" to true,
                "viewportMargin" to 100,
                "readOnly" to true
            )
        )

        CodeMirror.autoLoadMode(editor, "shell")

        val aaLinks = getElemsBySelector("a[data-file-idx]")

        aaLinks.map { link ->
            val fileIdx = link.getAttribute("data-file-idx")!!.toInt()
            link.onVanillaClick(true) {
                aaLinks.forEach {
                    it.removeClass("active")
                    it.setAttribute("href", "#!")
                }
                link.addClass("active")
                link.removeAttribute("href")
                editor.swapDoc(docs[fileIdx])
            }
        }

        (aaLinks[0] as HTMLAnchorElement).click()
    }


    private suspend fun buildTeacherTesting(courseId: String, exerciseId: String) {

        suspend fun postSolution(solution: String): AutoassResult {
            debug { "Posting submission ${solution.substring(0, 15)}..." }
            val result = fetchEms("/exercises/$exerciseId/testing/autoassess" + createQueryString("course" to courseId),
                ReqMethod.POST, mapOf("solution" to solution), successChecker = { http200 }).await()
                .parseTo(AutoassResult.serializer()).await()
            debug { "Received result, grade: ${result.grade}" }
            return result
        }


        val fl = debugFunStart("buildTeacherTesting")

        val latestSubmission =
            fetchEms("/exercises/$exerciseId/testing/autoassess/submissions${createQueryString("limit" to "1")}",
                ReqMethod.GET,
                successChecker = { http200 }
            ).await().parseTo(TestingTabComp.LatestSubmissions.serializer()).await()
                .submissions.getOrNull(0)?.solution

        getElemById("testing").innerHTML = tmRender(
            "tm-teach-exercise-testing", mapOf(
                "latestSubmission" to latestSubmission,
                "checkLabel" to Str.doAutoAssess(),
            )
        )
        val editor = CodeMirror.fromTextArea(
            getElemById("testing-submission"),
            objOf(
                "mode" to "python",
                "theme" to "idea",
                "lineNumbers" to true,
                "autoRefresh" to true,
                "viewportMargin" to 100,
                "indentUnit" to 4,
                "matchBrackets" to true,
                "extraKeys" to tabHandler,
                "placeholder" to "Kirjuta või lohista lahendus siia...",
            )
        )

        val submitButton = getElemByIdAs<HTMLButtonElement>("testing-submit")

        submitButton.onVanillaClick(true) {
            MainScope().launch {
                submitButton.disabled = true
                submitButton.textContent = Str.autoAssessing()
                val autoAssessmentWrap = getElemById("testing-assessment")
                autoAssessmentWrap.innerHTML = tmRender(
                    "tm-exercise-auto-feedback", mapOf(
                        "autoLabel" to Str.autoAssessmentLabel(),
                        "autoGradeLabel" to Str.autoGradeLabel(),
                        "grade" to "-",
                        "feedback" to Str.autoAssessing()
                    )
                )
                val solution = editor.getValue()
                val result = postSolution(solution)
                autoAssessmentWrap.innerHTML = renderAutoAssessment(result.grade, result.feedback)
                submitButton.textContent = Str.doAutoAssess()
                submitButton.disabled = false
            }
        }
        fl?.end()
    }


    private suspend fun buildTeacherStudents(
        courseId: String,
        courseExerciseId: String,
        exerciseId: String,
        threshold: Int
    ) {
        val fl = debugFunStart("buildTeacherStudents")
        getElemById("students").innerHTML = tmRender("tm-teach-exercise-students")
        val defaultGroupId = buildTeacherStudentsFrame(courseId, courseExerciseId, exerciseId, threshold)
        buildTeacherStudentsList(courseId, courseExerciseId, exerciseId, threshold, defaultGroupId)

        getElemByIdAs<HTMLButtonElement>("export-submissions-button").onSingleClickWithDisabled("Laen...") {
            debug { "Downloading submissions" }
            val selectedGroupId = getElemByIdAsOrNull<HTMLSelectElement>("group-select")?.value.emptyToNull()
            val groupsList = selectedGroupId?.let { listOf(mapOf("id" to it)) }
            val blob = fetchEms("/export/exercises/$exerciseId/submissions/latest",
                ReqMethod.POST,
                mapOf("courses" to listOf(mapOf("id" to courseId, "groups" to groupsList))),
                successChecker = { http200 }).await()
                .blob().await()
            val filename =
                "esitused-kursus-$courseId-ul-$courseExerciseId${selectedGroupId?.let { "-g-$it" }.orEmpty()}.zip"
            blob.saveAsFile(filename)
        }

        fl?.end()
    }

    private suspend fun buildTeacherStudentsFrame(
        courseId: String,
        courseExerciseId: String,
        exerciseId: String,
        threshold: Int
    ): String? {
        val groups = fetchEms(
            "/courses/$courseId/groups", ReqMethod.GET, successChecker = { http200 },
            errorHandler = ErrorHandlers.noCourseAccessMsg
        ).await()
            .parseTo(Groups.serializer()).await()
            .groups.sortedBy { it.name }

        debug { "Groups available: $groups" }

        getElemById("students-frame").innerHTML = tmRender(
            "tm-teach-exercise-students-frame", mapOf(
                "exportSubmissionsLabel" to "Lae alla",
                "groupLabel" to if (groups.isNotEmpty()) "Rühm" else null,
                "allLabel" to "Kõik õpilased",
                "hasOneGroup" to (groups.size == 1),
                "groups" to groups.map { mapOf("id" to it.id, "name" to it.name) })
        )

        if (groups.isNotEmpty()) {
            initSelectFields()
            val groupSelect = getElemByIdAs<HTMLSelectElement>("group-select")
            groupSelect.onChange {
                MainScope().launch {
                    val group = groupSelect.value
                    debug { "Selected group $group" }
                    buildTeacherStudentsList(courseId, courseExerciseId, exerciseId, threshold, group)
                }
            }
        }

        return if (groups.size == 1) groups[0].id else null
    }

    private fun initSelectFields() {
        Materialize.FormSelect.init(getNodelistBySelector("select"), objOf("coverTrigger" to false))
    }

    private suspend fun buildTeacherStudentsList(
        courseId: String, courseExerciseId: String, exerciseId: String,
        threshold: Int, groupId: String?, offset: Int = 0
    ) {

        val q = createQueryString("group" to groupId, "limit" to PAGE_STEP.toString(), "offset" to offset.toString())
        val teacherStudents = fetchEms(
            "/teacher/courses/$courseId/exercises/$courseExerciseId/submissions/latest/students$q", ReqMethod.GET,
            successChecker = { http200 }, errorHandler = ErrorHandlers.noCourseAccessMsg
        ).await()
            .parseTo(TeacherStudents.serializer()).await()

        val studentArray = teacherStudents.students.map { student ->
            val studentMap = mutableMapOf<String, Any?>(
                "id" to student.student_id,
                "givenName" to student.given_name,
                "familyName" to student.family_name,
                "groups" to student.groups,
                "time" to student.submission_time?.toEstonianString(),
                "points" to student.grade?.toString()
            )

            when (student.graded_by) {
                GraderType.AUTO -> {
                    studentMap["evalAuto"] = true
                }

                GraderType.TEACHER -> {
                    studentMap["evalTeacher"] = true
                }

                null -> {}
            }

            if (student.grade == null) {
                if (student.submission_time == null)
                    studentMap["unstarted"] = true
                else
                    studentMap["evalMissing"] = true
            } else {
                if (student.grade >= threshold)
                    studentMap["completed"] = true
                else
                    studentMap["started"] = true
            }

            studentMap.toJsObj()
        }.toTypedArray()


        val studentTotal = teacherStudents.student_count
        val paginationConf = if (studentTotal > PAGE_STEP) {
            PaginationConf(
                offset + 1, min(offset + PAGE_STEP, studentTotal), studentTotal,
                offset != 0, offset + PAGE_STEP < studentTotal
            )
        } else null

        getElemById("students-list").innerHTML = tmRender(
            "tm-teach-exercise-students-list", mapOf(
                "students" to studentArray,
                "autoLabel" to Str.gradedAutomatically(),
                "teacherLabel" to Str.gradedByTeacher(),
                "missingLabel" to Str.notGradedYet(),
                "hasPagination" to (paginationConf != null),
                "pageStart" to paginationConf?.pageStart,
                "pageEnd" to paginationConf?.pageEnd,
                "pageTotal" to paginationConf?.pageTotal,
                "pageTotalLabel" to ", kokku ",
                "canGoBack" to paginationConf?.canGoBack,
                "canGoForward" to paginationConf?.canGoForward
            )
        )

        if (paginationConf?.canGoBack.isNotNullAndTrue) {
            getElemsByClass("go-first").onVanillaClick(true) {
                buildTeacherStudentsList(courseId, courseExerciseId, exerciseId, threshold, groupId, 0)
            }
            getElemsByClass("go-back").onVanillaClick(true) {
                buildTeacherStudentsList(courseId, courseExerciseId, exerciseId, threshold, groupId, offset - PAGE_STEP)
            }
        }

        if (paginationConf?.canGoForward.isNotNullAndTrue) {
            getElemsByClass("go-forward").onVanillaClick(true) {
                buildTeacherStudentsList(courseId, courseExerciseId, exerciseId, threshold, groupId, offset + PAGE_STEP)
            }
            getElemsByClass("go-last").onVanillaClick(true) {
                buildTeacherStudentsList(
                    courseId,
                    courseExerciseId,
                    exerciseId,
                    threshold,
                    groupId,
                    getLastPageOffset(studentTotal, PAGE_STEP)
                )
            }
        }

        getElemsBySelector("[data-student-id]").forEach {
            val id = it.getAttribute("data-student-id")
                ?: error("No data-student-id found on student item")
            val givenName = it.getAttribute("data-given-name")
                ?: error("No data-given-name found on student item")
            val familyName = it.getAttribute("data-family-name")
                ?: error("No data-family-name found on student item")

            it.onVanillaClick(true) {
                buildStudentTab(courseId, courseExerciseId, exerciseId, threshold, id, givenName, familyName, false)
            }
        }

        initTooltips()
    }


    private fun buildStudentTab(
        courseId: String, courseExerciseId: String, exerciseId: String, threshold: Int,
        studentId: String, givenName: String, familyName: String, isAllSubsOpen: Boolean
    ) {

        suspend fun addAssessment(grade: Int, feedback: String, submissionId: String) {
            val assMap: MutableMap<String, Any> = mutableMapOf("grade" to grade)
            if (feedback.isNotBlank())
                assMap["feedback"] = feedback

            debug { "Posting assessment $assMap" }

            fetchEms("/teacher/courses/$courseId/exercises/$courseExerciseId/submissions/$submissionId/assessments",
                ReqMethod.POST, assMap, successChecker = { http200 }).await()
        }

        fun toggleAddGradeBox(submissionId: String, validGrade: Int?) {
            if (getElemByIdOrNull("add-grade-wrap") == null) {
                // Grading box is not visible
                debug { "Open add grade" }
                getElemById("add-grade-section").innerHTML = tmRender(
                    "tm-teach-exercise-add-grade", mapOf(
                        "gradePrefill" to validGrade,
                        "hasGradePrefill" to (validGrade != null),
                        "feedbackLabel" to Str.addAssessmentFeedbackLabel(),
                        "gradeLabel" to Str.addAssessmentGradeLabel(),
                        "gradeValidationError" to Str.addAssessmentGradeValidErr(),
                        "addGradeButton" to Str.addAssessmentButtonLabel()
                    )
                )

                getElemById("grade-button").onVanillaClick(true) {
                    val grade = getElemByIdAs<HTMLInputElement>("grade").valueAsNumber.toInt()
                    val feedback = getElemByIdAs<HTMLTextAreaElement>("feedback").value
                    MainScope().launch {
                        addAssessment(grade, feedback, submissionId)
                        successMessage { Str.assessmentAddedMsg() }
                        buildStudentTab(
                            courseId,
                            courseExerciseId,
                            exerciseId,
                            threshold,
                            studentId,
                            givenName,
                            familyName,
                            isSubmissionBoxVisible()
                        )
                        buildTeacherStudents(courseId, courseExerciseId, exerciseId, threshold)
                    }
                }

                getElemById("add-grade-link").textContent = Str.closeToggleLink()

            } else {
                // Grading box is visible
                debug { "Close add grade" }
                getElemById("add-grade-section").clear()
                getElemById("add-grade-link").textContent = Str.addAssessmentLink()
            }
        }

        fun paintSubmission(
            id: String, number: Int, time: Date, solution: String, isLast: Boolean,
            gradeAuto: Int?, feedbackAuto: String?, gradeTeacher: Int?, feedbackTeacher: String?
        ) {

            getElemById("submission-part").innerHTML = tmRender(
                "tm-teach-exercise-student-submission-sub", mapOf(
                    "id" to id,
                    "submissionLabel" to Str.submissionHeading(),
                    "submissionNo" to number,
                    "latestSubmissionLabel" to Str.latestSubmissionSuffix(),
                    "notLatestSubmissionLabel" to Str.oldSubmissionNote(),
                    "notLatestSubmissionLink" to Str.toLatestSubmissionLink(),
                    "isLatest" to isLast,
                    "timeLabel" to Str.submissionTimeLabel(),
                    "time" to time.toEstonianString(),
                    "addGradeLink" to Str.addAssessmentLink(),
                    "solution" to solution
                )
            )

            if (gradeAuto != null) {
                getElemById("assessment-auto").innerHTML =
                    renderAutoAssessment(gradeAuto, feedbackAuto)
            }
            if (gradeTeacher != null) {
                getElemById("assessment-teacher").innerHTML =
                    renderTeacherAssessment(gradeTeacher, feedbackTeacher)
            }

            CodeMirror.fromTextArea(
                getElemById("student-submission"), objOf(
                    "mode" to "python",
                    "theme" to "idea",
                    "lineNumbers" to true,
                    "autoRefresh" to true,
                    "viewportMargin" to 100,
                    "readOnly" to true
                )
            )

            val validGrade = gradeTeacher ?: gradeAuto

            getElemByIdOrNull("add-grade-link")?.onVanillaClick(true) { toggleAddGradeBox(id, validGrade) }

            getElemByIdOrNull("last-submission-link")?.onVanillaClick(true) {
                val isAllSubsBoxOpen = getElemByIdOrNull("all-submissions-wrap") != null
                buildStudentTab(
                    courseId,
                    courseExerciseId,
                    exerciseId,
                    threshold,
                    studentId,
                    givenName,
                    familyName,
                    isAllSubsBoxOpen
                )
            }
        }

        suspend fun paintSubmissionBox() {
            getElemById("all-submissions-section").innerHTML = tmRender(
                "tm-teach-exercise-all-submissions-placeholder", mapOf(
                    "text" to Str.loadingAllSubmissions()
                )
            )

            val submissionsWrap =
                fetchEms("/teacher/courses/$courseId/exercises/$courseExerciseId/submissions/all/students/$studentId",
                    ReqMethod.GET, successChecker = { http200 }).await()
                    .parseTo(TeacherSubmissions.serializer()).await()

            data class SubData(
                val number: Int, val isLast: Boolean, val time: Date, val solution: String,
                val gradeAuto: Int?, val feedbackAuto: String?, val gradeTeacher: Int?, val feedbackTeacher: String?
            )

            val submissionIdMap = mutableMapOf<String, SubData>()
            var submissionNumber = submissionsWrap.count
            val submissions = submissionsWrap.submissions.map {

                submissionIdMap[it.id] = SubData(
                    submissionNumber, submissionNumber == submissionsWrap.count,
                    it.created_at, it.solution, it.grade_auto, it.feedback_auto, it.grade_teacher, it.feedback_teacher
                )

                val submissionMap = mutableMapOf<String, Any?>(
                    "autoLabel" to Str.gradedAutomatically(),
                    "teacherLabel" to Str.gradedByTeacher(),
                    "missingLabel" to Str.notGradedYet(),
                    "id" to it.id,
                    "number" to submissionNumber--,
                    "time" to it.created_at.toEstonianString()
                )

                val validGrade = when {
                    it.grade_teacher != null -> {
                        submissionMap["points"] = it.grade_teacher.toString()
                        submissionMap["evalTeacher"] = true
                        it.grade_teacher
                    }

                    it.grade_auto != null -> {
                        submissionMap["points"] = it.grade_auto.toString()
                        submissionMap["evalAuto"] = true
                        it.grade_auto
                    }

                    else -> {
                        submissionMap["evalMissing"] = true
                        null
                    }
                }

                when {
                    validGrade == null -> Unit
                    validGrade >= threshold -> submissionMap["completed"] = true
                    else -> submissionMap["started"] = true
                }

                submissionMap.toJsObj()
            }.toTypedArray()

            val selectedSubId = getElemBySelectorOrNull("[data-active-sub]")?.getAttribute("data-active-sub")
            debug { "Selected submission: $selectedSubId" }

            getElemById("all-submissions-section").innerHTML = tmRender(
                "tm-teach-exercise-all-submissions", mapOf(
                    "submissions" to submissions
                )
            )

            if (selectedSubId != null) {
                refreshSubListLinks(selectedSubId)
            } else {
                warn { "Active submission id is null" }
            }

            getNodelistBySelector("[data-sub-id]").asList().forEach {
                if (it is Element) {
                    val id = it.getAttribute("data-sub-id")
                        ?: error("No data-sub-id found on submission item")
                    val sub = submissionIdMap[id] ?: error("No submission $id found in idMap")

                    it.onVanillaClick(true) {
                        debug { "Painting submission $id" }
                        paintSubmission(
                            id, sub.number, sub.time, sub.solution, sub.isLast,
                            sub.gradeAuto, sub.feedbackAuto, sub.gradeTeacher, sub.feedbackTeacher
                        )
                        refreshSubListLinks(id)
                    }
                } else {
                    error("Submission item is not an Element")
                }
            }

            initTooltips()
        }

        suspend fun toggleSubmissionsBox() {
            if (!isSubmissionBoxVisible()) {
                debug { "Open all submissions" }
                getElemById("all-submissions-link").textContent = Str.closeToggleLink()
                paintSubmissionBox()
            } else {
                debug { "Close all submissions" }
                getElemById("all-submissions-section").clear()
                getElemById("all-submissions-link").textContent = Str.allSubmissionsLink()
            }
        }


        val studentTab = getElemById("tab-student")
        studentTab.removeClass("display-none")
        getElemById("student").clear()
        val studentTabLink = studentTab.firstElementChild
        studentTabLink?.textContent = "$givenName ${familyName[0]}"

        val tabs = Materialize.Tabs.getInstance(getElemById("tabs"))!!
        tabs.select("student")
        tabs.updateTabIndicator()
        studentTabLink?.focus()

        MainScope().launch {
            val submissions =
                fetchEms("/teacher/courses/$courseId/exercises/$courseExerciseId/submissions/all/students/$studentId?limit=1",
                    ReqMethod.GET, successChecker = { http200 }).await()
                    .parseTo(TeacherSubmissions.serializer()).await()

            val submission = submissions.submissions[0]

            getElemById("student").innerHTML = tmRender("tm-teach-exercise-student-submission", emptyMap())
            getElemById("all-submissions-part").innerHTML = tmRender(
                "tm-teach-exercise-student-submission-all", mapOf(
                    "allSubmissionsLink" to Str.allSubmissionsLink()
                )
            )
            paintSubmission(
                submission.id, submissions.count, submission.created_at, submission.solution, true,
                submission.grade_auto, submission.feedback_auto, submission.grade_teacher, submission.feedback_teacher
            )

            if (isAllSubsOpen) {
                toggleSubmissionsBox()
                refreshSubListLinks(submission.id)
            }

            getElemById("all-submissions-link").onVanillaClick(true) { MainScope().launch { toggleSubmissionsBox() } }
        }
    }

    private fun isSubmissionBoxVisible() = getElemByIdOrNull("all-submissions-wrap") != null

    private fun refreshSubListLinks(selectedSubmissionid: String) {
        getNodelistBySelector("[data-sub-id]").asList().filterIsInstance<Element>().forEach {
            it.apply {
                setAttribute("href", "#!")
                removeClass("inactive")
            }
        }
        getElemBySelectorOrNull("[data-sub-id='$selectedSubmissionid']")?.apply {
            removeAttribute("href")
            addClass("inactive")
        }
    }

    private fun renderTeacherAssessment(grade: Int, feedback: String?): String {
        return tmRender(
            "tm-exercise-teacher-feedback", mapOf(
                "teacherLabel" to Str.teacherAssessmentLabel(),
                "teacherGradeLabel" to Str.gradeLabel(),
                "grade" to grade.toString(),
                "feedback" to feedback
            )
        )
    }

    private fun renderAutoAssessment(grade: Int, feedback: String?): String {
        return tmRender(
            "tm-exercise-auto-feedback", mapOf(
                "autoLabel" to Str.autoAssessmentLabel(),
                "autoGradeLabel" to Str.autoGradeLabel(),
                "grade" to grade.toString(),
                "feedback" to feedback?.let { formatFeedback(it) },
            )
        )
    }


    private fun initTooltips() {
        Materialize.Tooltip.init(getNodelistBySelector(".tooltipped"))
    }

    fun link(courseId: String, courseExerciseId: String) =
        constructPathLink(mapOf("courseId" to courseId, "courseExerciseId" to courseExerciseId))
}