package dev.ayuislands.toolwindow

import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.openapi.wm.ToolWindowType
import com.intellij.openapi.wm.ex.ToolWindowEx
import java.awt.Component
import java.awt.Container
import javax.swing.JTree
import javax.swing.SwingUtilities
import javax.swing.Timer
import javax.swing.event.TreeExpansionEvent
import javax.swing.event.TreeExpansionListener
import kotlin.math.abs

/**
 * Reusable auto-fit logic for any tool window containing a JTree.
 * Measures visible row widths and stretches the tool window to fit content,
 * clamped between [minWidth] and the caller-supplied max width.
 */
class ToolWindowAutoFitter(
    private val project: Project,
    private val toolWindowId: String,
    private val minWidth: Int,
) {
    private var expansionListener: TreeExpansionListener? = null
    private var expansionTree: JTree? = null
    private val debounceTimer =
        Timer(DEBOUNCE_DELAY_MS) { applyAutoFitWidth(maxWidthProvider()) }
            .apply { isRepeats = false }

    /** Lambda to get current max width from settings (called at fit time, not init time). */
    var maxWidthProvider: () -> Int = { DEFAULT_MAX_WIDTH }

    fun applyAutoFitWidth(maxWidth: Int) {
        val tree = findTree() ?: return
        val toolWindow =
            ToolWindowManager
                .getInstance(project)
                .getToolWindow(toolWindowId) as? ToolWindowEx
                ?: return

        var maxRowWidth = 0
        for (row in 0 until tree.rowCount) {
            val bounds = tree.getRowBounds(row) ?: continue
            val rowRight = bounds.x + bounds.width
            if (rowRight > maxRowWidth) {
                maxRowWidth = rowRight
            }
        }

        val desiredWidth =
            (maxRowWidth + AUTOFIT_PADDING)
                .coerceAtMost(maxWidth)
                .coerceAtLeast(minWidth)
        applyWidth(toolWindow, desiredWidth)
    }

    fun applyFixedWidth(targetWidth: Int) {
        val toolWindow =
            ToolWindowManager
                .getInstance(project)
                .getToolWindow(toolWindowId) as? ToolWindowEx
                ?: return
        applyWidth(toolWindow, targetWidth)
    }

    private fun applyWidth(
        toolWindow: ToolWindowEx,
        desiredWidth: Int,
    ) {
        val currentWidth = toolWindow.component.width
        val delta = desiredWidth - currentWidth
        if (abs(delta) <= JITTER_THRESHOLD) return

        when (toolWindow.type) {
            ToolWindowType.FLOATING, ToolWindowType.WINDOWED -> {
                val window = SwingUtilities.getWindowAncestor(toolWindow.component) ?: return
                window.setSize(desiredWidth, window.height)
            }
            else -> toolWindow.stretchWidth(delta)
        }
    }

    fun installExpansionListener() {
        val tree = findTree() ?: return
        if (expansionTree === tree && expansionListener != null) return

        removeExpansionListener()
        expansionTree = tree
        expansionListener =
            object : TreeExpansionListener {
                override fun treeExpanded(event: TreeExpansionEvent) {
                    scheduleAutoFit()
                }

                override fun treeCollapsed(event: TreeExpansionEvent) {
                    scheduleAutoFit()
                }
            }
        tree.addTreeExpansionListener(expansionListener)
    }

    fun removeExpansionListener() {
        val tree = expansionTree ?: return
        val listener = expansionListener ?: return
        tree.removeTreeExpansionListener(listener)
        expansionTree = null
        expansionListener = null
        debounceTimer.stop()
    }

    fun scheduleAutoFit() {
        debounceTimer.restart()
    }

    fun findTree(): JTree? {
        val toolWindow =
            ToolWindowManager
                .getInstance(project)
                .getToolWindow(toolWindowId)
                ?: return null
        val content =
            toolWindow
                .contentManager
                .contents
                .firstOrNull()
                ?.component
                ?: return null
        return findFirstOfType(content, JTree::class.java) as? JTree
    }

    companion object {
        private const val DEBOUNCE_DELAY_MS = 150
        private const val AUTOFIT_PADDING = 20
        private const val JITTER_THRESHOLD = 8
        private const val DEFAULT_MAX_WIDTH = 400
    }
}

private fun findFirstOfType(
    component: Component,
    type: Class<*>,
): Component? {
    if (type.isInstance(component)) return component
    if (component is Container) {
        for (child in component.components) {
            val found = findFirstOfType(child, type)
            if (found != null) return found
        }
    }
    return null
}
