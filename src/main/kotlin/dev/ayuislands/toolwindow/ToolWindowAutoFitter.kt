package dev.ayuislands.toolwindow

import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.openapi.wm.ToolWindowType
import com.intellij.openapi.wm.ex.ToolWindowEx
import dev.ayuislands.settings.PanelWidthMode
import javax.swing.JTree
import javax.swing.SwingUtilities
import javax.swing.Timer
import javax.swing.event.TreeExpansionEvent
import javax.swing.event.TreeExpansionListener

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

    /** Lambda to get the current max width from settings (called at fit time, not init time). */
    var maxWidthProvider: () -> Int = { DEFAULT_MAX_WIDTH }

    /** Lambda to get the current min width from settings (called at fit time, not init time). */
    var minWidthProvider: () -> Int = { minWidth }

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

        val desiredWidth = AutoFitCalculator.calculateDesiredWidth(maxRowWidth, maxWidth, minWidthProvider())
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
        if (AutoFitCalculator.isJitterOnly(currentWidth, desiredWidth)) return
        val delta = desiredWidth - currentWidth

        when (toolWindow.type) {
            ToolWindowType.FLOATING, ToolWindowType.WINDOWED -> {
                val window = SwingUtilities.getWindowAncestor(toolWindow.component) ?: return
                window.setSize(desiredWidth, window.height)
            }
            else -> toolWindow.stretchWidth(delta)
        }
    }

    fun applyWidthMode(
        mode: PanelWidthMode,
        autoFitMaxWidth: Int,
        fixedWidth: Int,
    ) {
        when (mode) {
            PanelWidthMode.DEFAULT -> removeExpansionListener()
            PanelWidthMode.AUTO_FIT -> {
                installExpansionListener()
                applyAutoFitWidth(autoFitMaxWidth)
            }
            PanelWidthMode.FIXED -> {
                removeExpansionListener()
                applyFixedWidth(fixedWidth)
            }
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
        return AutoFitCalculator.findFirstOfType(content, JTree::class.java) as? JTree
    }

    companion object {
        private const val DEBOUNCE_DELAY_MS = 150
        private const val DEFAULT_MAX_WIDTH = 400
    }
}
