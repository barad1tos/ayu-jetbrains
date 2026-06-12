package dev.ayuislands.commitpanel

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.openapi.wm.ex.ToolWindowManagerListener
import dev.ayuislands.licensing.LicenseChecker
import dev.ayuislands.settings.AyuIslandsSettings
import dev.ayuislands.settings.PanelWidthMode
import dev.ayuislands.toolwindow.AutoFitCalculator
import dev.ayuislands.toolwindow.ToolWindowAutoFitter
import dev.ayuislands.toolwindow.shouldTriggerAutoFitFor
import java.beans.PropertyChangeListener
import javax.swing.JTree
import javax.swing.tree.TreeCellRenderer

/** Per-project service that auto-fits the Commit tool window width to its tree content. */
@Service(Service.Level.PROJECT)
class CommitPanelAutoFitManager(
    private val project: Project,
) : Disposable {
    private val autoFitter =
        ToolWindowAutoFitter(
            project = project,
            toolWindowId = TOOL_WINDOW_ID,
            minWidth = AutoFitCalculator.MIN_COMMIT_AUTOFIT_WIDTH,
        ).apply {
            maxWidthProvider = { AyuIslandsSettings.getInstance().state.autoFitCommitMaxWidth }
            minWidthProvider = { AyuIslandsSettings.getInstance().state.commitPanelAutoFitMinWidth }
        }
    private var pathRendererTree: JTree? = null
    private var pathRendererListener: PropertyChangeListener? = null

    init {
        project.messageBus.connect(this).subscribe(
            ToolWindowManagerListener.TOPIC,
            object : ToolWindowManagerListener {
                override fun stateChanged(
                    toolWindowManager: ToolWindowManager,
                    changeType: ToolWindowManagerListener.ToolWindowManagerEventType,
                ) {
                    if (!changeType.shouldTriggerAutoFitFor(toolWindowManager, TOOL_WINDOW_ID)) return
                    applyIfWidthManaged()
                }
            },
        )
    }

    private fun applyIfWidthManaged() {
        val mode =
            PanelWidthMode.fromString(
                AyuIslandsSettings.getInstance().state.commitPanelWidthMode,
            )
        if (mode == PanelWidthMode.DEFAULT) return
        apply()
    }

    fun apply() {
        if (!LicenseChecker.isLicensedOrGrace()) {
            removePathRenderer()
            autoFitter.removeExpansionListener()
            return
        }
        val state = AyuIslandsSettings.getInstance().state
        val mode = PanelWidthMode.fromString(state.commitPanelWidthMode)
        syncPathRenderer(mode)
        autoFitter.applyWidthMode(
            mode,
            state.autoFitCommitMaxWidth,
            state.commitPanelFixedWidth,
        )
    }

    override fun dispose() {
        removePathRenderer()
        autoFitter.removeExpansionListener()
    }

    private fun syncPathRenderer(mode: PanelWidthMode) {
        when (mode) {
            PanelWidthMode.DEFAULT -> removePathRenderer()
            PanelWidthMode.AUTO_FIT,
            PanelWidthMode.FIXED,
            -> installPathRenderer()
        }
    }

    private fun installPathRenderer() {
        val tree = autoFitter.findTree()
        if (tree == null) {
            removePathRenderer()
            return
        }
        if (pathRendererTree !== tree) {
            removePathRenderer()
            pathRendererTree = tree
        }

        val current = tree.cellRenderer
        if (current !is CommitPathShorteningRenderer) {
            tree.cellRenderer = CommitPathShorteningRenderer(current)
            refreshTree(tree)
        }
        installPathRendererGuard(tree)
    }

    private fun removePathRenderer() {
        val tree = pathRendererTree
        val listener = pathRendererListener
        if (tree != null && listener != null) {
            tree.removePropertyChangeListener("cellRenderer", listener)
        }
        pathRendererTree = null
        pathRendererListener = null

        if (tree != null && tree.cellRenderer is CommitPathShorteningRenderer) {
            tree.cellRenderer = (tree.cellRenderer as CommitPathShorteningRenderer).delegate
            refreshTree(tree)
        }
    }

    private fun installPathRendererGuard(tree: JTree) {
        if (pathRendererListener != null) return

        pathRendererListener =
            PropertyChangeListener { event ->
                if (event.propertyName != "cellRenderer") return@PropertyChangeListener
                val newRenderer = event.newValue as? TreeCellRenderer ?: return@PropertyChangeListener
                if (newRenderer is CommitPathShorteningRenderer) return@PropertyChangeListener

                if (!LicenseChecker.isLicensedOrGrace()) {
                    removePathRenderer()
                    return@PropertyChangeListener
                }
                val mode =
                    PanelWidthMode.fromString(
                        AyuIslandsSettings.getInstance().state.commitPanelWidthMode,
                    )
                if (mode == PanelWidthMode.DEFAULT) {
                    removePathRenderer()
                    return@PropertyChangeListener
                }

                tree.cellRenderer = CommitPathShorteningRenderer(newRenderer)
                refreshTree(tree)
            }
        tree.addPropertyChangeListener("cellRenderer", pathRendererListener)
    }

    private fun refreshTree(tree: JTree) {
        tree.treeDidChange()
        tree.revalidate()
        tree.repaint()
    }

    companion object {
        private const val TOOL_WINDOW_ID = "Commit"

        fun getInstance(project: Project): CommitPanelAutoFitManager =
            project.getService(
                CommitPanelAutoFitManager::class.java,
            )
    }
}
