package dev.ayuislands.toolwindow

import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ex.ToolWindowManagerListener.ToolWindowManagerEventType
import io.mockk.every
import io.mockk.mockk
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Locks the filter contract shared by the three `*AutoFitManager` listeners.
 *
 * Both filtered cases (`MovedOrResized`, `ShowToolWindow`) are the actual root causes
 * of the v2.3.7 / #169 width-oscillation regressions — keeping them rejected here
 * prevents drift if a future caller adds a third manager and inlines its own guards.
 */
class AutoFitTriggersTest {
    @Test
    fun `MovedOrResized does not trigger auto-fit`() {
        assertFalse(ToolWindowManagerEventType.MovedOrResized.shouldTriggerAutoFit())
    }

    @Test
    fun `ShowToolWindow does not trigger auto-fit`() {
        assertFalse(ToolWindowManagerEventType.ShowToolWindow.shouldTriggerAutoFit())
    }

    @Test
    fun `HideToolWindow falls through for downstream tw isVisible filter`() {
        assertTrue(ToolWindowManagerEventType.HideToolWindow.shouldTriggerAutoFit())
    }

    @Test
    fun `ActivateToolWindow triggers auto-fit`() {
        assertTrue(ToolWindowManagerEventType.ActivateToolWindow.shouldTriggerAutoFit())
    }

    @Test
    fun `foreign visible tool window does not trigger scoped auto-fit`() {
        val toolWindow = toolWindow(id = "AWS", isVisible = true)

        assertFalse(
            ToolWindowManagerEventType.ActivateToolWindow
                .shouldTriggerAutoFitFor(toolWindow, expectedToolWindowId = "Commit"),
        )
    }

    @Test
    fun `own hidden tool window does not trigger scoped auto-fit`() {
        val toolWindow = toolWindow(id = "Commit", isVisible = false)

        assertFalse(
            ToolWindowManagerEventType.ActivateToolWindow
                .shouldTriggerAutoFitFor(toolWindow, expectedToolWindowId = "Commit"),
        )
    }

    @Test
    fun `own visible tool window triggers scoped auto-fit for accepted events`() {
        val toolWindow = toolWindow(id = "Commit", isVisible = true)

        assertTrue(
            ToolWindowManagerEventType.ActivateToolWindow
                .shouldTriggerAutoFitFor(toolWindow, expectedToolWindowId = "Commit"),
        )
    }

    @Test
    fun `own visible tool window keeps rejected event filter`() {
        val toolWindow = toolWindow(id = "Commit", isVisible = true)

        assertFalse(
            ToolWindowManagerEventType.ShowToolWindow
                .shouldTriggerAutoFitFor(toolWindow, expectedToolWindowId = "Commit"),
        )
    }

    private fun toolWindow(
        id: String,
        isVisible: Boolean,
    ): ToolWindow =
        mockk {
            every { this@mockk.id } returns id
            every { this@mockk.isVisible } returns isVisible
        }
}
