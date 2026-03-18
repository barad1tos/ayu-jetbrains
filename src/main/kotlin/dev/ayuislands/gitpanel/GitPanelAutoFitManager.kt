package dev.ayuislands.gitpanel

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.logger
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
        if (!LicenseChecker.isLicensedOrGrace()) {
            LOG.info("[GitAutoFit] apply() skipped — not licensed")
            return
        }
        val mode =
            PanelWidthMode.fromString(
                AyuIslandsSettings.getInstance().state.gitPanelWidthMode,
            )
        LOG.info("[GitAutoFit] apply() called — mode=$mode")
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
        val logContent = findLogTabContent()
        if (logContent == null) {
            LOG.info("[GitAutoFit] fitSplitters() — findLogTabContent() returned null")
            return
        }
        LOG.info(
            "[GitAutoFit] fitSplitters() — logContent type=${logContent::class.java.name}, " +
                "size=${logContent.width}x${logContent.height}",
        )

        // Dump shallow hierarchy (3 levels) for debugging
        dumpComponentTree(logContent, depth = 0, maxDepth = 3)

        val allSplitters =
            AutoFitCalculator.findAllOfType(logContent, Splitter::class.java)
        LOG.info(
            "[GitAutoFit] findAllOfType(Splitter) found ${allSplitters.size} instances: " +
                allSplitters.joinToString { "${it::class.java.name}(w=${it.width})" },
        )

        val splitters = allSplitters.filterIsInstance<Splitter>()
        LOG.info("[GitAutoFit] filterIsInstance<Splitter> kept ${splitters.size}")

        val state = AyuIslandsSettings.getInstance().state
        val maxWidth = state.gitPanelAutoFitMaxWidth
        val minWidth = state.gitPanelAutoFitMinWidth

        for ((index, splitter) in splitters.withIndex()) {
            if (splitter.width <= 0) {
                LOG.info("[GitAutoFit] splitter[$index] width=${splitter.width} — skipping (<=0)")
                continue
            }
            LOG.info(
                "[GitAutoFit] splitter[$index] width=${splitter.width}, " +
                    "proportion=${splitter.proportion}, " +
                    "firstComponent=${splitter.firstComponent?.javaClass?.name}, " +
                    "secondComponent=${splitter.secondComponent?.javaClass?.name}",
            )
            fitSplitter(splitter, maxWidth, minWidth)
        }
    }

    private fun fitSplitter(
        splitter: Splitter,
        maxWidth: Int,
        minWidth: Int,
    ) {
        val first = splitter.firstComponent
        val second = splitter.secondComponent
        if (first == null || second == null) {
            LOG.info("[GitAutoFit] fitSplitter — skipping, first=${first != null}, second=${second != null}")
            return
        }

        val firstHasTable = AutoFitCalculator.findFirstOfType(first, JTable::class.java) != null
        val firstHasTree = AutoFitCalculator.findFirstOfType(first, JTree::class.java) != null
        val secondHasTable = AutoFitCalculator.findFirstOfType(second, JTable::class.java) != null
        val secondHasTree = AutoFitCalculator.findFirstOfType(second, JTree::class.java) != null

        LOG.info(
            "[GitAutoFit] fitSplitter — firstHasTable=$firstHasTable, firstHasTree=$firstHasTree, " +
                "secondHasTable=$secondHasTable, secondHasTree=$secondHasTree",
        )

        if (firstHasTable) {
            // Inner splitter: left=JTable (log), right=JTree (file changes)
            val tree =
                AutoFitCalculator.findFirstOfType(
                    second,
                    JTree::class.java,
                ) as? JTree
            if (tree == null) {
                LOG.info("[GitAutoFit] inner splitter — no JTree in secondComponent, skipping")
                return
            }
            val maxRowWidth = measureTreeMaxRowWidth(tree)
            val desiredWidth =
                AutoFitCalculator.calculateDesiredWidth(
                    maxRowWidth,
                    maxWidth,
                    minWidth,
                )
            val currentWidth = ((1.0f - splitter.proportion) * splitter.width).toInt()
            LOG.info(
                "[GitAutoFit] inner splitter — maxRowWidth=$maxRowWidth, desiredWidth=$desiredWidth, " +
                    "currentWidth=$currentWidth, splitterWidth=${splitter.width}, " +
                    "proportion=${splitter.proportion}",
            )
            if (AutoFitCalculator.isJitterOnly(currentWidth, desiredWidth)) {
                LOG.info("[GitAutoFit] inner splitter — jitter only, skipping")
                return
            }
            val proportion =
                (1.0f - desiredWidth.toFloat() / splitter.width.toFloat())
                    .coerceIn(MIN_INNER_PROPORTION, MAX_INNER_PROPORTION)
            LOG.info("[GitAutoFit] inner splitter — setting proportion to $proportion")
            splitter.proportion = proportion
        } else {
            // Outer splitter: left=JTree (branches), right=inner splitter or log
            val tree =
                AutoFitCalculator.findFirstOfType(
                    first,
                    JTree::class.java,
                ) as? JTree
            if (tree == null) {
                LOG.info("[GitAutoFit] outer splitter — no JTree in firstComponent, skipping")
                return
            }
            val maxRowWidth = measureTreeMaxRowWidth(tree)
            val desiredWidth =
                AutoFitCalculator.calculateDesiredWidth(
                    maxRowWidth,
                    maxWidth,
                    minWidth,
                )
            val currentWidth = (splitter.proportion * splitter.width).toInt()
            LOG.info(
                "[GitAutoFit] outer splitter — maxRowWidth=$maxRowWidth, desiredWidth=$desiredWidth, " +
                    "currentWidth=$currentWidth, splitterWidth=${splitter.width}, " +
                    "proportion=${splitter.proportion}",
            )
            if (AutoFitCalculator.isJitterOnly(currentWidth, desiredWidth)) {
                LOG.info("[GitAutoFit] outer splitter — jitter only, skipping")
                return
            }
            val proportion =
                (desiredWidth.toFloat() / splitter.width.toFloat())
                    .coerceIn(MIN_OUTER_PROPORTION, MAX_OUTER_PROPORTION)
            LOG.info("[GitAutoFit] outer splitter — setting proportion to $proportion")
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
            if (splitter.firstComponent == null || splitter.secondComponent == null) continue
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
        val manager = ToolWindowManager.getInstance(project)
        val allIds = manager.toolWindowIds.toList()
        LOG.info("[GitAutoFit] findLogTabContent — registered tool window IDs: $allIds")
        val toolWindow = manager.getToolWindow("Version Control")
        if (toolWindow == null) {
            LOG.info("[GitAutoFit] findLogTabContent — getToolWindow('Git') returned null")
            return null
        }
        LOG.info("[GitAutoFit] findLogTabContent — Git tool window found, isVisible=${toolWindow.isVisible}")

        val contents = toolWindow.contentManager.contents
        LOG.info(
            "[GitAutoFit] findLogTabContent — ${contents.size} content tabs: " +
                contents.joinToString { "'${it.tabName}' (displayName='${it.displayName}')" },
        )

        val logContent =
            contents.firstOrNull { it.tabName == "Log" }
        if (logContent == null) {
            LOG.info("[GitAutoFit] findLogTabContent — no tab with tabName='Log' found")
            return null
        }

        val component = logContent.component
        LOG.info(
            "[GitAutoFit] findLogTabContent — Log tab component: ${component.javaClass.name}, " +
                "size=${component.width}x${component.height}",
        )
        return component
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

    private fun dumpComponentTree(
        component: java.awt.Component,
        depth: Int,
        maxDepth: Int,
    ) {
        if (depth > maxDepth) return
        val indent = "  ".repeat(depth)
        val extra =
            if (component is Splitter) {
                " [SPLITTER prop=${component.proportion}]"
            } else {
                ""
            }
        LOG.info(
            "[GitAutoFit] TREE $indent${component::class.java.name} " +
                "(${component.width}x${component.height})$extra",
        )
        if (component is java.awt.Container) {
            for (child in component.components) {
                dumpComponentTree(child, depth + 1, maxDepth)
            }
        }
    }

    override fun dispose() {
        removeExpansionListeners()
    }

    companion object {
        private val LOG = logger<GitPanelAutoFitManager>()
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
