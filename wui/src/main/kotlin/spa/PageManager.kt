package spa

import debugFunStart
import kotlin.browser.window

object PageManager {

    // No need for thread-safety, JS runs single-threaded
    private var pages: List<Page> = emptyList()

    fun registerPages(newPages: List<Page>) {
        pages += newPages
    }

    fun updatePage(pageState: String? = null) {
        val funLog = debugFunStart("updatePage")

        val path = window.location.pathname
        val page = pageFromPath(path)

        page.clear()
        page.build(pageState)

        funLog?.end()
    }

    fun navigateTo(path: String) {
        window.history.pushState(null, "", path)
        updatePage()
    }

    private fun pageFromPath(path: String): Page {
        val matchingPages = pages.filter { it.pathMatches(path) }
        val matchingCount = matchingPages.size
        return when {
            matchingCount == 1 -> matchingPages.single()
            matchingCount < 1 -> error("Path $path did not match any pages")
            else -> error("Path $path matched several pages: ${matchingPages.map { it.pageName }}")
        }
    }

}