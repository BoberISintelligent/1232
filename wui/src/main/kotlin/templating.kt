fun tmRender(templateId: String, data: Map<String, Any?>? = null): String =
        render(getElemById(templateId).innerHTML, data ?: emptyMap())

fun tmRender(templateId: String, vararg dataPairs: Pair<String, Any?>): String = tmRender(templateId, mapOf(*dataPairs))

fun singleDstStr(dstId: String) = """<ez-dst id="$dstId"></ez-dst>"""

private fun render(template: String, data: Map<String, Any?>): String {
    Mustache.parse(template)
    return Mustache.render(template, dynamicToAny(data.toJsObj()))
}
