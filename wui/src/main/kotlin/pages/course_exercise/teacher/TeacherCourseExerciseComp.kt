package pages.course_exercise.teacher

import CONTENT_CONTAINER_ID
import cache.BasicCourseInfo
import components.PageTabsComp
import dao.CourseExercisesTeacherDAO
import kotlinx.coroutines.await
import pages.Title
import pages.course_exercise.CourseExerciseTextComp
import pages.course_exercise.student.CourseExerciseStudentSubmissionsTabComp
import pages.course_exercise.student.CourseExerciseStudentSubmitTabComp
import pages.exercise_library.createPathChainSuffix
import rip.kspar.ezspa.Component
import rip.kspar.ezspa.doInPromise
import template
import translation.Str

class TeacherCourseExerciseComp(
    private val courseId: String,
    private val courseExId: String,
    private val setPathSuffix: (String) -> Unit
) : Component(null, CONTENT_CONTAINER_ID) {

    private lateinit var exerciseTextComp: CourseExerciseTextComp
    private lateinit var tabs: PageTabsComp

    override val children: List<Component>
        get() = listOf(exerciseTextComp, tabs)

    override fun create() = doInPromise {
        val courseEx = CourseExercisesTeacherDAO.getCourseExerciseDetails(courseId, courseExId).await()
        val courseTitle = BasicCourseInfo.get(courseId).await().effectiveTitle

        Title.update {
            it.pageTitle = courseEx.effectiveTitle
            it.parentPageTitle = courseTitle
        }

        setPathSuffix(createPathChainSuffix(listOf(courseEx.effectiveTitle)))

        exerciseTextComp = CourseExerciseTextComp(courseEx.effectiveTitle, courseEx.text_html, null, this)
        tabs = PageTabsComp(
            type = PageTabsComp.Type.SUBPAGE,
            tabs = listOf(
                PageTabsComp.Tab(
                    Str.tabSubmit, preselected = true,
                    compProvider = {
                        CourseExerciseStudentSubmitTabComp(
                            courseId,
                            courseExId,
                            courseEx.grader_type,
                            true,
                            courseEx.solution_file_name,
                            courseEx.solution_file_type,
                            {},
                            it
                        )
                    }),
                PageTabsComp.Tab(
                    Str.tabMySubmissions,
                    compProvider = {
                        CourseExerciseStudentSubmissionsTabComp(
                            courseId,
                            courseExId,
                            courseEx.threshold,
                            {  },
                            it
                        )
                    }),
            ),
            parent = this
        )
    }

    override fun render() = template(
        """
            <ez-student-course-exercise>
                <ez-block-container>
                    <ez-block id='${exerciseTextComp.dstId}' style='width: 45rem; max-width: 80rem; overflow: auto;'></ez-block>
                    <ez-block id='${tabs.dstId}' style='width: 45rem; max-width: 80rem; padding-top: 1rem; padding-bottom: 5rem; overflow: auto;'></ez-block>
                </ez-block-container>
            </ez-student-course-exercise>
        """.trimIndent(),

        )

}