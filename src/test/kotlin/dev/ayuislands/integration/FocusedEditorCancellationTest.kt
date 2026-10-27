package dev.ayuislands.integration

import com.intellij.ide.DataManager
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.ayuislands.settings.FocusedEditorContext
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import java.beans.PropertyChangeEvent
import java.beans.PropertyChangeListener
import javax.swing.JPanel
import kotlin.test.assertFailsWith

class FocusedEditorCancellationTest : BasePlatformTestCase() {
    fun testDialogCancellationDoesNotEscapeSynchronousFocusCapture() {
        withFocusedEditor { listener, context ->
            withCancelledProgress {
                listener.propertyChange(PropertyChangeEvent(this, "permanentFocusOwner", null, JPanel()))

                assertEquals("Recovery.kt", context.get(project)?.activeFile?.fileName)
            }
        }
    }

    fun testNonEditorFocusPreservesSelection() {
        myFixture.configureByText("Recovery.kt", "val answer = 42")
        var dataContext =
            SimpleDataContext.getSimpleContext(CommonDataKeys.EDITOR, myFixture.editor, DataContext.EMPTY_CONTEXT)
        withDataContext({ dataContext }) { listener, context ->
            repeat(2) {
                listener.propertyChange(PropertyChangeEvent(this, "permanentFocusOwner", null, JPanel()))
            }
            val selectedContext = context.get(project)
            assertEquals("Recovery.kt", selectedContext?.activeFile?.fileName)
            dataContext = DataContext.EMPTY_CONTEXT

            withCancelledProgress {
                listener.propertyChange(PropertyChangeEvent(this, "permanentFocusOwner", null, JPanel()))

                assertSame(selectedContext, context.get(project))
            }
        }
    }

    fun testProjectlessEditorUsesFocusedProject() {
        myFixture.configureByText("Recovery.kt", "val answer = 42")
        val editorFactory = EditorFactory.getInstance()
        val editor = editorFactory.createEditor(myFixture.editor.document)
        try {
            assertNull(editor.project)
            val dataContext =
                SimpleDataContext
                    .builder()
                    .setParent(DataContext.EMPTY_CONTEXT)
                    .add(CommonDataKeys.EDITOR, editor)
                    .add(CommonDataKeys.PROJECT, project)
                    .build()
            withDataContext({ dataContext }) { listener, context ->
                withCancelledProgress {
                    listener.propertyChange(PropertyChangeEvent(this, "permanentFocusOwner", null, JPanel()))

                    assertEquals("Recovery.kt", context.get(project)?.activeFile?.fileName)
                }
            }
        } finally {
            editorFactory.releaseEditor(editor)
        }
    }

    fun testProjectlessFocusPreservesSelection() {
        myFixture.configureByText("Recovery.kt", "val answer = 42")
        val editorFactory = EditorFactory.getInstance()
        val editor = editorFactory.createEditor(myFixture.editor.document)
        try {
            assertNull(editor.project)
            val missingProjectContext =
                SimpleDataContext
                    .builder()
                    .setParent(DataContext.EMPTY_CONTEXT)
                    .add(CommonDataKeys.EDITOR, editor)
                    .addNull(CommonDataKeys.PROJECT)
                    .build()
            assertFocusPreservesSelection(missingProjectContext)
        } finally {
            editorFactory.releaseEditor(editor)
        }
    }

    fun testUnboundDocumentPreservesSelection() {
        myFixture.configureByText("Recovery.kt", "val answer = 42")
        val editorFactory = EditorFactory.getInstance()
        val document = editorFactory.createDocument("scratch content")
        val editor = editorFactory.createEditor(document, project)
        try {
            assertNull(FileDocumentManager.getInstance().getFile(document))
            val unboundDocumentContext =
                SimpleDataContext.getSimpleContext(CommonDataKeys.EDITOR, editor, DataContext.EMPTY_CONTEXT)
            assertFocusPreservesSelection(unboundDocumentContext)
        } finally {
            editorFactory.releaseEditor(editor)
        }
    }

    fun testOrdinaryDataLookupFailuresStillPropagate() {
        withFocusedEditor { listener, _ ->
            val failure = IllegalStateException("data provider failed")
            every { DataManager.getInstance().getDataContext(any<java.awt.Component>()) } throws failure

            val thrown =
                assertFailsWith<IllegalStateException> {
                    listener.propertyChange(PropertyChangeEvent(this, "permanentFocusOwner", null, JPanel()))
                }

            assertSame(failure, thrown)
        }
    }

    private fun withFocusedEditor(block: (PropertyChangeListener, FocusedEditorContext) -> Unit) {
        myFixture.configureByText("Recovery.kt", "val answer = 42")
        val dataContext =
            SimpleDataContext.getSimpleContext(CommonDataKeys.EDITOR, myFixture.editor, DataContext.EMPTY_CONTEXT)
        withDataContext({ dataContext }, block)
    }

    private fun assertFocusPreservesSelection(incompleteContext: DataContext) {
        var dataContext =
            SimpleDataContext.getSimpleContext(CommonDataKeys.EDITOR, myFixture.editor, DataContext.EMPTY_CONTEXT)
        withDataContext({ dataContext }) { listener, context ->
            listener.propertyChange(PropertyChangeEvent(this, "permanentFocusOwner", null, JPanel()))
            val selectedContext = context.get(project)
            assertEquals("Recovery.kt", selectedContext?.activeFile?.fileName)
            dataContext = incompleteContext

            withCancelledProgress {
                listener.propertyChange(PropertyChangeEvent(this, "permanentFocusOwner", null, JPanel()))

                assertSame(selectedContext, context.get(project))
            }
        }
    }

    private fun withCancelledProgress(block: () -> Unit) {
        val indicator = EmptyProgressIndicator()
        ProgressManager.getInstance().runProcess(
            {
                indicator.cancel()
                assertFailsWith<ProcessCanceledException> { ProgressManager.checkCanceled() }

                block()

                assertFailsWith<ProcessCanceledException> { ProgressManager.checkCanceled() }
            },
            indicator,
        )
    }

    private fun withDataContext(
        dataContext: () -> DataContext,
        block: (PropertyChangeListener, FocusedEditorContext) -> Unit,
    ) {
        val dataManager = mockk<DataManager>()
        every { dataManager.getDataContext(any<java.awt.Component>()) } answers {
            ProgressManager.checkCanceled()
            dataContext()
        }
        mockkStatic(DataManager::class)
        var context: FocusedEditorContext? = null
        try {
            every { DataManager.getInstance() } returns dataManager
            lateinit var listener: PropertyChangeListener
            val focusedContext =
                FocusedEditorContext(subscribeFocus = { registered ->
                    listener = registered
                    {}
                })
            context = focusedContext
            block(listener, focusedContext)
        } finally {
            context?.dispose()
            unmockkStatic(DataManager::class)
        }
    }
}
