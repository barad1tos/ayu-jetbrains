package dev.ayuislands.toolwindow

import com.intellij.openapi.wm.ex.ToolWindowManagerListener

/**
 * Whether this `ToolWindowManager` event indicates a content change that
 * warrants an auto-fit re-measure.
 *
 * Filtered out:
 * - `MovedOrResized` — fires from our own `stretchWidth` call; reacting to
 *   it would create a feedback loop (was the root of v2.3.7 width oscillation).
 * - `ShowToolWindow` — toggling visibility does not change tree content,
 *   but `JTree.getRowBounds()` returns different widths depending on cell-renderer
 *   focus state, so re-measuring on show produces unstable widths (root cause
 *   of #169).
 *
 * All other event types fall through and let the auto-fit listener proceed —
 * the fingerprint guard inside [ToolWindowAutoFitter] handles idempotence for
 * the legitimate cases.
 */
internal fun ToolWindowManagerListener.ToolWindowManagerEventType.shouldTriggerAutoFit(): Boolean =
    this != ToolWindowManagerListener.ToolWindowManagerEventType.MovedOrResized &&
        this != ToolWindowManagerListener.ToolWindowManagerEventType.ShowToolWindow
