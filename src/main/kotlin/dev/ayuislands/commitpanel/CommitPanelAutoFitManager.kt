package dev.ayuislands.commitpanel

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.openapi.wm.ex.ToolWindowManagerListener
import dev.ayuislands.accent.AccentCheckpoint
import dev.ayuislands.accent.AccentWriteCheckpoint
import dev.ayuislands.accent.AccentWriteState
import dev.ayuislands.accent.UiDefaultsTarget
import dev.ayuislands.licensing.LicenseChecker
import dev.ayuislands.settings.AyuIslandsSettings
import dev.ayuislands.settings.AyuIslandsState
import dev.ayuislands.settings.PanelWidthMode
import dev.ayuislands.settings.WorkspaceWidth
import dev.ayuislands.toolwindow.AutoFitCalculator
import dev.ayuislands.toolwindow.ToolWindowAutoFitter
import dev.ayuislands.toolwindow.shouldTriggerAutoFitFor
import java.beans.PropertyChangeListener
import javax.swing.JTree
import javax.swing.SwingUtilities
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
            canApply = { LicenseChecker.isLicensedOrGrace() },
        ).apply {
            maxWidthProvider = { AyuIslandsSettings.getInstance().state?.autoFitCommitMaxWidth }
            minWidthProvider = { AyuIslandsSettings.getInstance().state?.commitPanelAutoFitMinWidth }
        }
    private var pathRendererTree: JTree? = null
    private var pathRendererListener: PropertyChangeListener? = null
    private var canShortenPaths = false
    private var isDisposed = false
    private var pathBinding: CommitRendererBinding? = null
    private var rendererHold: RendererCheckpoint? = null

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
                AyuIslandsSettings.getInstance().state?.commitPanelWidthMode,
            )
        if (mode == PanelWidthMode.DEFAULT) return
        apply()
    }

    fun apply() {
        if (isDisposed || project.isDisposed) return
        rendererHold?.let {
            it.deferApply()
            return
        }
        canShortenPaths = LicenseChecker.isLicensedOrGrace()
        if (!canShortenPaths) {
            removePathRenderer()
            autoFitter.removeExpansionListener()
            return
        }
        val state = AyuIslandsSettings.getInstance().state ?: return
        val mode = PanelWidthMode.fromString(state.commitPanelWidthMode)
        syncPathRenderer(mode)
        autoFitter.applyWidthMode(
            mode,
            state.autoFitCommitMaxWidth,
            state.commitPanelFixedWidth,
        )
    }

    /** Capture the existing width owner independently of path rendering and other Workspace preferences. */
    internal fun prepareWidth(width: WorkspaceWidth): ToolWindowAutoFitter.WidthCheckpoint? =
        autoFitter.prepareWidth(PanelWidthMode.fromString(width.mode), width.maximum, width.fixed, width.minimum)

    override fun dispose() {
        isDisposed = true
        canShortenPaths = false
        if (rendererHold == null) removePathRenderer() else rendererHold?.disposeBindings()
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
            bindRenderer(tree, current)
            refreshTree(tree)
        }
        installPathRendererGuard(tree)
        pathBinding?.refresh()
    }

    private fun bindRenderer(
        tree: JTree,
        delegate: TreeCellRenderer,
    ) {
        val renderer =
            CommitPathShorteningRenderer(
                delegate,
                canApply = { canShortenPaths },
                onInvalidated = { pathBinding?.requestPreparation() },
            )
        if (!renderer.prepare(tree, expectedRenderer = delegate)) {
            renderer.close()
            return
        }
        pathBinding?.close()
        tree.cellRenderer = renderer
        pathBinding =
            CommitRendererBinding(tree, renderer, { !isDisposed && !project.isDisposed && rendererHold == null })
    }

    private fun removePathRenderer() {
        val tree = pathRendererTree
        val listener = pathRendererListener
        if (tree != null && listener != null) {
            tree.removePropertyChangeListener("cellRenderer", listener)
        }
        pathRendererTree = null
        pathRendererListener = null
        val owned = pathBinding?.renderer
        pathBinding?.close()
        pathBinding = null

        if (tree != null && owned != null && tree.cellRenderer === owned) {
            tree.cellRenderer = owned.delegate
            refreshTree(tree)
        }
    }

    private fun installPathRendererGuard(tree: JTree) {
        if (pathRendererListener != null) return

        pathRendererListener =
            PropertyChangeListener { event ->
                if (event.propertyName != "cellRenderer" ||
                    rendererHold != null ||
                    isDisposed
                ) {
                    return@PropertyChangeListener
                }
                val newRenderer = event.newValue as? TreeCellRenderer ?: return@PropertyChangeListener
                if (newRenderer is CommitPathShorteningRenderer) return@PropertyChangeListener

                // Native UI refresh callbacks may prohibit model reads. Prepare on a later plain EDT turn.
                SwingUtilities.invokeLater {
                    rebindPathRenderer(tree, newRenderer)
                }
            }
        tree.addPropertyChangeListener("cellRenderer", pathRendererListener)
    }

    private fun rebindPathRenderer(
        tree: JTree,
        renderer: TreeCellRenderer,
    ) {
        if (isDisposed || project.isDisposed || rendererHold != null) return
        if (pathRendererTree !== tree || tree.cellRenderer !== renderer) return
        canShortenPaths = LicenseChecker.isLicensedOrGrace()
        val mode = PanelWidthMode.fromString(AyuIslandsSettings.getInstance().state?.commitPanelWidthMode)
        if (!canShortenPaths || mode == PanelWidthMode.DEFAULT) {
            removePathRenderer()
            return
        }
        bindRenderer(tree, renderer)
        refreshTree(tree)
    }

    /** Captures existing renderer identities; candidate preparation starts only after the outer acknowledgement. */
    internal fun prepareRenderer(candidate: AyuIslandsState): RendererCheckpoint {
        check(SwingUtilities.isEventDispatchThread()) { "Commit renderer capture must run on EDT" }
        check(rendererHold == null) { "Commit renderer recovery must settle before capture" }
        return RendererCheckpoint(candidate)
    }

    internal inner class RendererCheckpoint(
        candidate: AyuIslandsState,
    ) : AccentWriteCheckpoint {
        private val target = UiDefaultsTarget()
        private val initialTree = pathRendererTree
        private val initialBinding = pathBinding
        private val tree = autoFitter.findCreatedTree()
        private val cells = mutableListOf<CommitRendererCell>()
        private var candidateBinding: CommitRendererBinding? = null
        private var shouldUseCandidate = true
        private var hasDeferredApply = false
        override var state = AccentWriteState.PREPARED
            private set
        override val hasPending: Boolean get() = state == AccentWriteState.OWNED
        val records: List<CommitRendererRecord> get() = cells.map { it.record }

        init {
            if (initialTree != null && initialTree !== tree) {
                val before = initialTree.cellRenderer
                if (before is CommitPathShorteningRenderer) {
                    cells +=
                        CommitRendererCell(initialTree, before, before.delegate)
                }
            }
            if (tree != null) {
                val before = tree.cellRenderer
                val delegate = (before as? CommitPathShorteningRenderer)?.delegate ?: before
                val enabled = PanelWidthMode.fromString(candidate.commitPanelWidthMode) != PanelWidthMode.DEFAULT
                val planned =
                    if (enabled && delegate != null) {
                        CommitPathShorteningRenderer(
                            delegate,
                            styleProvider = {
                                if (shouldUseCandidate) {
                                    CommitPathStyle.capture(candidate)
                                } else {
                                    AyuIslandsSettings.getInstance().state?.let(CommitPathStyle::capture)
                                }
                            },
                            canApply = {
                                if (shouldUseCandidate) !isDisposed && !project.isDisposed else canShortenPaths
                            },
                            onInvalidated = { candidateBinding?.requestPreparation() },
                        )
                    } else {
                        delegate
                    }
                cells += CommitRendererCell(tree, before, planned)
            }
        }

        override fun apply(): Boolean {
            checkEdt()
            check(state == AccentWriteState.PREPARED) { "Commit renderer application was already started" }
            if (rendererHold != null || !isCurrent()) {
                state = AccentWriteState.RELINQUISHED
                return false
            }
            rendererHold = this
            state = AccentWriteState.OWNED
            for (cell in cells) {
                val captured = cell.record
                val planned = captured.planned
                if (planned is CommitPathShorteningRenderer) {
                    check(
                        planned.prepare(cell.tree, expectedRenderer = captured.before),
                    ) { "Commit renderer changed during preparation" }
                }
                cell.apply()
            }
            val planned = cells.firstOrNull { it.tree === tree }?.record?.planned
            if (tree != null && planned is CommitPathShorteningRenderer && tree.cellRenderer === planned) {
                candidateBinding =
                    CommitRendererBinding(tree, planned, {
                        !isDisposed &&
                            !project.isDisposed &&
                            (rendererHold === this || pathBinding === candidateBinding)
                    })
            }
            return cells.none { it.state == AccentWriteState.RELINQUISHED }
        }

        override fun restore(blockedBy: List<AccentCheckpoint>) {
            checkEdt()
            if (state == AccentWriteState.PREPARED) {
                state = AccentWriteState.RESTORED
                return
            }
            if (!hasPending) return
            check(
                blockedBy.none { newer ->
                    newer is RendererCheckpoint &&
                        newer.hasPending &&
                        newer.cells.any { other -> cells.any { it.tree === other.tree } }
                },
            ) { "Commit renderer restoration is blocked by a newer write" }
            for (cell in cells.asReversed()) cell.restore(isCurrent())
            candidateBinding?.close()
            candidateBinding = null
            val foreign = cells.any { it.state == AccentWriteState.RELINQUISHED }
            if (foreign) retireOriginalBinding() else initialBinding?.refresh()
            state = if (foreign) AccentWriteState.RELINQUISHED else AccentWriteState.RESTORED
            releaseResources()
        }

        override fun commit() {
            checkEdt()
            if (state != AccentWriteState.OWNED) return
            for (cell in cells) cell.accept(isCurrent())
            val accepted = cells.firstOrNull { it.tree === tree }?.record
            retireOriginalBinding()
            if (accepted != null) {
                if (accepted.state == AccentWriteState.RESTORED && accepted.planned is CommitPathShorteningRenderer) {
                    shouldUseCandidate = false
                    canShortenPaths = true
                    pathRendererTree = tree
                    pathBinding = candidateBinding
                    installPathRendererGuard(checkNotNull(tree))
                } else {
                    candidateBinding?.close()
                }
            }
            state =
                if (cells.any {
                        it.state == AccentWriteState.RELINQUISHED
                    }
                ) {
                    AccentWriteState.RELINQUISHED
                } else {
                    AccentWriteState.RESTORED
                }
            releaseResources()
        }

        override fun releaseResources() {
            if (hasPending || rendererHold !== this) return
            rendererHold = null
            if (!hasDeferredApply || state != AccentWriteState.RESTORED) return
            if (isDisposed || project.isDisposed) return
            SwingUtilities.invokeLater {
                if (!isDisposed && !project.isDisposed) {
                    this@CommitPanelAutoFitManager.apply()
                }
            }
        }

        override fun retain() {
            check(!hasPending || rendererHold === this) { "Commit renderer recovery lost its hold" }
        }

        fun deferApply() {
            hasDeferredApply = true
        }

        fun disposeBindings() {
            initialBinding?.close()
            candidateBinding?.close()
        }

        private fun retireOriginalBinding() {
            initialBinding?.close()
            val listener = pathRendererListener
            if (initialTree != null &&
                listener != null
            ) {
                initialTree.removePropertyChangeListener("cellRenderer", listener)
            }
            pathRendererListener = null
            pathRendererTree = null
            pathBinding = null
        }

        private fun isCurrent(): Boolean =
            !isDisposed && !project.isDisposed && target.isCurrent() && autoFitter.findCreatedTree() === tree

        private fun checkEdt() =
            check(SwingUtilities.isEventDispatchThread()) { "Commit renderer mutation must run on EDT" }
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
