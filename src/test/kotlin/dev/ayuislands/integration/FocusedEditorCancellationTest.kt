package dev.ayuislands.integration

import com.intellij.ide.DataManager
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
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
            val indicator = EmptyProgressIndicator()
            val progress = ProgressManager.getInstance()
            progress.runProcess(
                {
                    indicator.cancel()
                    assertFailsWith<ProcessCanceledException> { ProgressManager.checkCanceled() }

                    listener.propertyChange(PropertyChangeEvent(this, "permanentFocusOwner", null, JPanel()))

                    assertEquals("Recovery.kt", context.get(project)?.activeFile?.fileName)
                    assertFailsWith<ProcessCanceledException> { ProgressManager.checkCanceled() }
                },
                indicator,
            )
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
        val dataManager = mockk<DataManager>()
        every { dataManager.getDataContext(any<java.awt.Component>()) } answers {
            ProgressManager.checkCanceled()
            dataContext
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
