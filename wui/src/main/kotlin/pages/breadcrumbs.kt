package pages

import IdGenerator
import Str
import spa.Component
import tmRender


data class Crumb(val label: String, val href: String? = null) {
    companion object {
        val myCourses = Crumb(Str.myCourses(), "/courses")
    }
}


class BreadcrumbsComp(
        private val crumbs: List<Crumb>,
        parent: Component?,
        dstId: String = IdGenerator.nextId()
) : Component(dstId, parent) {

    override fun render(): String = tmRender("t-c-breadcrumbs",
            "crumbs" to crumbs.map {
                mapOf("label" to it.label,
                        "href" to it.href)
            }
    )
}
