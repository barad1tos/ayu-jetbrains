package dev.ayuislands.toolwindow

import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowManager
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
    fun `HideToolWindow falls through for downstream visibility filter`() {
        assertTrue(ToolWindowManagerEventType.HideToolWindow.shouldTriggerAutoFit())
    }

    @Test
    fun `ActivateToolWindow triggers auto-fit`() {
        assertTrue(ToolWindowManagerEventType.ActivateToolWindow.shouldTriggerAutoFit())
    }

    @Test
    fun `foreign active tool window does not trigger scoped auto-fit`() {
        val toolWindowManager = mockk<ToolWindowManager>()
        every { toolWindowManager.activeToolWindowId } returns "AWS"

        assertFalse(
            ToolWindowManagerEventType.ActivateToolWindow
                .shouldTriggerAutoFitFor(toolWindowManager, expectedToolWindowId = "Commit"),
        )
    }

    @Test
    fun `missing active tool window does not trigger scoped auto-fit`() {
        val toolWindowManager = mockk<ToolWindowManager>()
        every { toolWindowManager.activeToolWindowId } returns null

        assertFalse(
            ToolWindowManagerEventType.ActivateToolWindow
                .shouldTriggerAutoFitFor(toolWindowManager, expectedToolWindowId = "Commit"),
        )
    }

    @Test
    fun `active hidden tool window does not trigger scoped auto-fit`() {
        val toolWindowManager = mockk<ToolWindowManager>()
        every { toolWindowManager.activeToolWindowId } returns "Commit"
        every { toolWindowManager.getToolWindow("Commit") } returns toolWindow(isVisible = false)

        assertFalse(
            ToolWindowManagerEventType.ActivateToolWindow
                .shouldTriggerAutoFitFor(toolWindowManager, expectedToolWindowId = "Commit"),
        )
    }

    @Test
    fun `active visible tool window triggers scoped auto-fit for accepted events`() {
        val toolWindowManager = mockk<ToolWindowManager>()
        every { toolWindowManager.activeToolWindowId } returns "Commit"
        every { toolWindowManager.getToolWindow("Commit") } returns toolWindow(isVisible = true)

        assertTrue(
            ToolWindowManagerEventType.ActivateToolWindow
                .shouldTriggerAutoFitFor(toolWindowManager, expectedToolWindowId = "Commit"),
        )
    }

    @Test
    fun `active visible tool window keeps rejected event filter`() {
        val toolWindowManager = mockk<ToolWindowManager>()
        every { toolWindowManager.activeToolWindowId } returns "Commit"

        assertFalse(
            ToolWindowManagerEventType.ShowToolWindow
                .shouldTriggerAutoFitFor(toolWindowManager, expectedToolWindowId = "Commit"),
        )
    }

    @Test
    fun `global layout event triggers scoped auto-fit for active visible managed tool window`() {
        val toolWindowManager = mockk<ToolWindowManager>()
        every { toolWindowManager.activeToolWindowId } returns "Commit"
        every { toolWindowManager.getToolWindow("Commit") } returns toolWindow(isVisible = true)

        assertTrue(
            ToolWindowManagerEventType.SetLayout
                .shouldTriggerAutoFitFor(toolWindowManager, expectedToolWindowId = "Commit"),
        )
    }

    @Test
    fun `global layout event ignores hidden managed tool window`() {
        val toolWindowManager = mockk<ToolWindowManager>()
        every { toolWindowManager.activeToolWindowId } returns "Commit"
        every { toolWindowManager.getToolWindow("Commit") } returns toolWindow(isVisible = false)

        assertFalse(
            ToolWindowManagerEventType.SetLayout
                .shouldTriggerAutoFitFor(toolWindowManager, expectedToolWindowId = "Commit"),
        )
    }

    private fun toolWindow(isVisible: Boolean): ToolWindow =
        mockk {
            every { this@mockk.isVisible } returns isVisible
        }
}
