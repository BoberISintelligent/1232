package pages.exercise.editor.tsl.tests

import components.code_editor.CodeEditorComp
import pages.exercise.editor.tsl.TSLTestComponent
import rip.kspar.ezspa.Component
import rip.kspar.ezspa.IdGenerator
import rip.kspar.ezspa.doInPromise
import rip.kspar.ezspa.objOf
import tsl.common.model.FunctionExecutionTest
import tsl.common.model.Test

class TSLFunctionCallTest(
    private val initialModel: FunctionExecutionTest?,
    private val onUpdate: () -> Unit,
    parent: Component,
    dstId: String,
) : TSLTestComponent(parent, dstId) {

    private val testId = initialModel?.id ?: IdGenerator.nextLongId()

    private lateinit var editor: CodeEditorComp

    override val children: List<Component>
        get() = listOf(editor)

    override fun create() = doInPromise {
        editor = CodeEditorComp(
            CodeEditorComp.File(
                "create_object.py", """
            def create_object():
                return ... # TODO: Tagasta vastav klass või isend
        """.trimIndent()
            ), showTabs = false, parent = this
        )

        val d = editor.activeDoc
        d?.markText(
            objOf(
                "line" to 0,
                "ch" to 0,
            ),
            objOf(
                "line" to 0,
                "ch" to 100,
            ),
            objOf(
                "readOnly" to true,
                "atomic" to true,
                "inclusiveLeft" to true,
                "inclusiveRight" to true,
                "selectLeft" to false,
                "css" to "color: #999",
            )
        )
    }


    override fun getTSLModel(): Test {
        return FunctionExecutionTest(
            testId,
            "fun",
            listOf("42"),
            returnValue = "foo"
        )
    }

}