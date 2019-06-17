import pages.CoursesPage
import pages.ExercisesPage
import pages.Page
import kotlin.browser.window


fun main() {
    debug { "Yello" }

    renderOnce()
    updatePage()
}

/**
 * Do actions that must be done only once per document load i.e. SPA refresh
 */
fun renderOnce() {
    debug { "renderOnce" }

    setupLinkInterception()
    setupHistoryNavInterception()
}

fun updatePage(pageState: Any? = null) {
    debug { "updatePage" }

    // Simulating paths for testing
//    window.history.pushState(null, "", "/courses/12a/exercises")
//    window.history.pushState(null, "", "/courses")

    val path = window.location.pathname
    debug { "Current path: $path" }

    AppState.path = path

    val page = pageFromPath(path)

    AppState.id = page.pageId

    page.clear()
    page.build(pageState)
}

fun pageFromPath(path: String): Page {
    return when {
        CoursesPage.pathMatches(path) -> CoursesPage
        ExercisesPage.pathMatches(path) -> ExercisesPage
        else -> error("Unmatched path")
    }
}