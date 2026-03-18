package dev.ayuislands.gitpanel

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Splitter
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.openapi.wm.ex.ToolWindowManagerListener
import dev.ayuislands.licensing.LicenseChecker
import dev.ayuislands.settings.AyuIslandsSettings
import dev.ayuislands.settings.PanelWidthMode
import dev.ayuislands.toolwindow.AutoFitCalculator
import javax.swing.JTable
import javax.swing.JTree
import javax.swing.Timer
import javax.swing.event.TreeExpansionEvent
import javax.swing.event.TreeExpansionListener

/** Per-project service that manages Git panel internal splitter proportions (auto-fit or fixed). */
@Service(Service.Level.PROJECT)
class GitPanelAutoFitManager(
    private val project: Project,
) : Disposable {
    private val debounceTimer =
        Timer(DEBOUNCE_DELAY_MS) { fitSplitters() }
            .apply { isRepeats = false }

    private val expansionListeners =
        mutableListOf<Pair<JTree, TreeExpansionListener>>()

    init {
        project.messageBus.connect(this).subscribe(
            ToolWindowManagerListener.TOPIC,
            object : ToolWindowManagerListener {
                override fun stateChanged(
                    toolWindowManager: ToolWindowManager,
                    changeType: ToolWindowManagerListener.ToolWindowManagerEventType,
                ) {
                    val mode =
                        PanelWidthMode.fromString(
                            AyuIslandsSettings.getInstance().state.gitPanelWidthMode,
                        )
                    if (mode == PanelWidthMode.DEFAULT) return
                    apply()
                }
            },
        )
    }

    fun apply() {
        if (!LicenseChecker.isLicensedOrGrace()) return
        val mode =
            PanelWidthMode.fromString(
                AyuIslandsSettings.getInstance().state.gitPanelWidthMode,
            )
        when (mode) {
            PanelWidthMode.DEFAULT -> removeExpansionListeners()
            PanelWidthMode.AUTO_FIT -> {
                installExpansionListeners()
                fitSplitters()
            }
            PanelWidthMode.FIXED -> {
                removeExpansionListeners()
                setFixedProportions()
            }
        }
    }

    private fun fitSplitters() {
        val logContent = findLogTabContent() ?: return
        val splitters =
            AutoFitCalculator
                .findAllOfType(logContent, Splitter::class.java)
                .filterIsInstance<Splitter>()
        val state = AyuIslandsSettings.getInstance().state
        val maxWidth = state.gitPanelAutoFitMaxWidth

        for (splitter in splitters) {
            if (splitter.width <= 0) continue
            fitSplitter(splitter, maxWidth)
        }
    }

    private fun fitSplitter(
        splitter: Splitter,
        maxWidth: Int,
    ) {
        val firstHasTable =
            AutoFitCalculator.findFirstOfType(
                splitter.firstComponent,
                JTable::class.java,
            ) != null

        if (firstHasTable) {
            // Inner splitter: left=JTable (log), right=JTree (file changes)
            val tree =
                AutoFitCalculator.findFirstOfType(
                    splitter.secondComponent,
                    JTree::class.java,
                ) as? JTree ?: return
            val maxRowWidth = measureTreeMaxRowWidth(tree)
            val desiredWidth =
                AutoFitCalculator.calculateDesiredWidth(
                    maxRowWidth,
                    maxWidth,
                    AutoFitCalculator.MIN_GIT_AUTOFIT_WIDTH,
                )
            val currentWidth = ((1.0f - splitter.proportion) * splitter.width).toInt()
            if (AutoFitCalculator.isJitterOnly(currentWidth, desiredWidth)) return
            val proportion =
                (1.0f - desiredWidth.toFloat() / splitter.width.toFloat())
                    .coerceIn(MIN_INNER_PROPORTION, MAX_INNER_PROPORTION)
            splitter.proportion = proportion
        } else {
            // Outer splitter: left=JTree (branches), right=inner splitter or log
            val tree =
                AutoFitCalculator.findFirstOfType(
                    splitter.firstComponent,
                    JTree::class.java,
                ) as? JTree ?: return
            val maxRowWidth = measureTreeMaxRowWidth(tree)
            val desiredWidth =
                AutoFitCalculator.calculateDesiredWidth(
                    maxRowWidth,
                    maxWidth,
                    AutoFitCalculator.MIN_GIT_AUTOFIT_WIDTH,
                )
            val currentWidth = (splitter.proportion * splitter.width).toInt()
            if (AutoFitCalculator.isJitterOnly(currentWidth, desiredWidth)) return
            val proportion =
                (desiredWidth.toFloat() / splitter.width.toFloat())
                    .coerceIn(MIN_OUTER_PROPORTION, MAX_OUTER_PROPORTION)
            splitter.proportion = proportion
        }
    }

    private fun setFixedProportions() {
        val logContent = findLogTabContent() ?: return
        val splitters =
            AutoFitCalculator
                .findAllOfType(logContent, Splitter::class.java)
                .filterIsInstance<Splitter>()
        val fixedWidth = AyuIslandsSettings.getInstance().state.gitPanelFixedWidth

        for (splitter in splitters) {
            if (splitter.width <= 0) continue
            val firstHasTable =
                AutoFitCalculator.findFirstOfType(
                    splitter.firstComponent,
                    JTable::class.java,
                ) != null

            if (firstHasTable) {
                val proportion =
                    (1.0f - fixedWidth.toFloat() / splitter.width.toFloat())
                        .coerceIn(MIN_INNER_PROPORTION, MAX_INNER_PROPORTION)
                splitter.proportion = proportion
            } else {
                val proportion =
                    (fixedWidth.toFloat() / splitter.width.toFloat())
                        .coerceIn(MIN_OUTER_PROPORTION, MAX_OUTER_PROPORTION)
                splitter.proportion = proportion
            }
        }
    }

    private fun installExpansionListeners() {
        val logContent = findLogTabContent() ?: return
        val trees =
            AutoFitCalculator
                .findAllOfType(logContent, JTree::class.java)
                .filterIsInstance<JTree>()

        val tracked = expansionListeners.map { it.first }.toSet()
        for (tree in trees) {
            if (tree in tracked) continue
            val listener =
                object : TreeExpansionListener {
                    override fun treeExpanded(event: TreeExpansionEvent) {
                        debounceTimer.restart()
                    }

                    override fun treeCollapsed(event: TreeExpansionEvent) {
                        debounceTimer.restart()
                    }
                }
            tree.addTreeExpansionListener(listener)
            expansionListeners.add(tree to listener)
        }
    }

    private fun removeExpansionListeners() {
        for ((tree, listener) in expansionListeners) {
            tree.removeTreeExpansionListener(listener)
        }
        expansionListeners.clear()
        debounceTimer.stop()
    }

    private fun findLogTabContent(): java.awt.Component? {
        val toolWindow =
            ToolWindowManager
                .getInstance(project)
                .getToolWindow("Git")
                ?: return null
        return toolWindow
            .contentManager
            .contents
            .firstOrNull { it.tabName == "Log" }
            ?.component
    }

    private fun measureTreeMaxRowWidth(tree: JTree): Int {
        var maxRowWidth = 0
        for (row in 0 until tree.rowCount) {
            val bounds = tree.getRowBounds(row) ?: continue
            val rowRight = bounds.x + bounds.width
            if (rowRight > maxRowWidth) maxRowWidth = rowRight
        }
        return maxRowWidth
    }

    override fun dispose() {
        removeExpansionListeners()
    }

    companion object {
        private const val DEBOUNCE_DELAY_MS = 150
        private const val MIN_OUTER_PROPORTION = 0.05f
        private const val MAX_OUTER_PROPORTION = 0.5f
        private const val MIN_INNER_PROPORTION = 0.5f
        private const val MAX_INNER_PROPORTION = 0.95f

        fun getInstance(project: Project): GitPanelAutoFitManager =
            project.getService(
                GitPanelAutoFitManager::class.java,
            )
    }
}
