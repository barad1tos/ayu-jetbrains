package dev.ayuislands.toolwindow

import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.openapi.wm.ex.ToolWindowEx
import com.intellij.ui.content.ContentManager
import dev.ayuislands.accent.UiDefaultsTarget
import java.awt.Component
import javax.swing.JComponent
import javax.swing.JTree

/** Captures an existing visible native peer without creating content or changing tool window state. */
internal class ToolWindowWidthTarget private constructor(
    val toolWindow: ToolWindowEx,
    private val contentManager: ContentManager,
    private val content: JComponent,
    private val peer: ToolWindowWidthPeer,
    val tree: JTree?,
    private val toolWindowId: String,
) {
    private val laf = UiDefaultsTarget()
    private val type = toolWindow.type
    private val anchor = toolWindow.anchor
    private val hierarchy = ancestors(content)
    private val contentBounds = content.bounds
    val before: ToolWindowWidthGeometry = peer.before
    val component: Component = peer.component
    val field: ToolWindowWidthField = peer.field

    fun isCurrent(manager: ToolWindowManager): Boolean =
        laf.isCurrent() &&
            manager.getToolWindow(toolWindowId) === toolWindow &&
            !toolWindow.isDisposed &&
            toolWindow.isVisible &&
            toolWindow.type == type &&
            toolWindow.anchor == anchor &&
            toolWindow.contentManagerIfCreated === contentManager &&
            contentManager.component === content &&
            ancestors(content) == hierarchy &&
            currentTree(contentManager) === tree &&
            peer.current()

    fun matchesBefore(): Boolean = geometry() == before && content.bounds == contentBounds

    fun geometry(): ToolWindowWidthGeometry = peer.geometry()

    fun plan(desiredWidth: Int?): ToolWindowWidthValue =
        if (desiredWidth == null || AutoFitCalculator.isJitterOnly(content.width, desiredWidth)) {
            before.value
        } else {
            peer.plan(desiredWidth, desiredWidth - content.width)
        }

    fun restore() {
        if (geometry().value != before.value) peer.restore()
    }

    companion object {
        fun capture(
            manager: ToolWindowManager,
            toolWindowId: String,
        ): ToolWindowWidthTarget? {
            val window = manager.getToolWindow(toolWindowId) as? ToolWindowEx ?: return null
            if (window.isDisposed || !window.isVisible) return null
            val contentManager = window.contentManagerIfCreated ?: return null
            val content = contentManager.component
            val peer = ToolWindowWidthPeer.capture(window, content) ?: return null
            return ToolWindowWidthTarget(
                window,
                contentManager,
                content,
                peer,
                currentTree(contentManager),
                toolWindowId,
            )
        }

        private fun ancestors(component: Component): List<Component> =
            generateSequence(component) { it.parent }.toList()

        private fun currentTree(contentManager: ContentManager): JTree? =
            contentManager.contents.firstOrNull()?.component?.let {
                AutoFitCalculator.findFirstOfType(it, JTree::class.java) as? JTree
            }
    }
}
