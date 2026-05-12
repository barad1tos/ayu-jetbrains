package dev.ayuislands.toolwindow

import com.intellij.openapi.wm.ex.ToolWindowManagerListener.ToolWindowManagerEventType
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
}
