package dev.ayuislands.projectview

import com.intellij.ide.projectView.ProjectView
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.registry.Registry
import com.intellij.openapi.util.registry.RegistryValue
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.openapi.wm.ex.ToolWindowManagerListener
import com.intellij.ui.SimpleColoredComponent
import com.intellij.ui.SimpleTextAttributes
import dev.ayuislands.accent.AccentApplyPlanRunner
import dev.ayuislands.accent.AccentCheckpoint
import dev.ayuislands.accent.AccentWriteCheckpoint
import dev.ayuislands.accent.AccentWriteState
import dev.ayuislands.accent.UiDefaultsTarget
import dev.ayuislands.licensing.LicenseChecker
import dev.ayuislands.settings.AyuIslandsSettings
import dev.ayuislands.settings.PanelWidthMode
import dev.ayuislands.settings.WorkspaceWidth
import dev.ayuislands.toolwindow.AutoFitCalculator
import dev.ayuislands.toolwindow.ToolWindowAutoFitter
import dev.ayuislands.toolwindow.shouldTriggerAutoFitFor
import dev.ayuislands.ui.ComponentTreeRefreshedListener
import dev.ayuislands.ui.ComponentTreeRefreshedTopic
import java.awt.Component
import java.beans.PropertyChangeListener
import javax.swing.JScrollPane
import javax.swing.JTree
import javax.swing.ScrollPaneConstants
import javax.swing.SwingUtilities
import javax.swing.tree.TreeCellRenderer

/** Per-project service that manages Project tool window tweaks (scrollbar, root path). */
@Service(Service.Level.PROJECT)
class ProjectViewScrollbarManager private constructor(
    private val project: Project,
    // Constructor parameters, not reassigned fields: init queues the first apply() before any secondary body runs.
    private val rootPathLeaseProvider: () -> RootPathLease,
) : Disposable {
    constructor(project: Project) : this(project, RootPathLease::getInstance)

    internal constructor(
        project: Project,
        rootPathLease: RootPathLease,
    ) : this(project, { rootPathLease })

    private var originalScrollbarPolicy: Int? = null
    private var scrollbarHold: ScrollbarCheckpoint? = null
    private var rootRendererHold: RootRendererCheckpoint? = null
    private var isDisposed = false
    private var trackedTree: JTree? = null
    private var lastAppliedHidePath: Boolean? = null
    private var rendererListener: PropertyChangeListener? = null
    private var canFilterRoot = false
    private val rootPathLease: RootPathLease
        get() = rootPathLeaseProvider()
    private val autoFitter =
        ToolWindowAutoFitter(
            project = project,
            toolWindowId = TOOL_WINDOW_ID,
            minWidth = AutoFitCalculator.MIN_PROJECT_AUTOFIT_WIDTH,
            canApply = { LicenseChecker.isLicensedOrGrace() },
        ).apply {
            maxWidthProvider = { AyuIslandsSettings.getInstance().state?.autoFitMaxWidth }
            minWidthProvider = { AyuIslandsSettings.getInstance().state?.projectPanelAutoFitMinWidth }
        }

    init {
        project.messageBus.connect(this).subscribe(
            ToolWindowManagerListener.TOPIC,
            object : ToolWindowManagerListener {
                override fun stateChanged(
                    toolWindowManager: ToolWindowManager,
                    changeType: ToolWindowManagerListener.ToolWindowManagerEventType,
                ) {
                    if (!changeType.shouldTriggerAutoFitFor(toolWindowManager, TOOL_WINDOW_ID)) return
                    applyIfAnyProjectFeatureManaged()
                }
            },
        )

        // Initial apply: a tool window may already be open when this service is created.
        SwingUtilities.invokeLater {
            if (!project.isDisposed) apply()
        }

        // Self-heal after IJSwingUtilities.updateComponentTreeUI (startup, focus swap,
        // LAF change) — the walk resets the ScrollPane's horizontal scrollbar policy
        // and any rendering overrides we patch from apply().
        project.messageBus
            .connect(this)
            .subscribe(
                ComponentTreeRefreshedTopic.TOPIC,
                ComponentTreeRefreshedListener { apply() },
            )
    }

    private fun applyIfAnyProjectFeatureManaged() {
        val state = AyuIslandsSettings.getInstance().state ?: return
        val widthMode = PanelWidthMode.fromString(state.projectPanelWidthMode)
        val allFeaturesDisabled =
            !state.hideProjectRootPath &&
                !state.hideProjectViewHScrollbar
        if (allFeaturesDisabled && widthMode == PanelWidthMode.DEFAULT) return
        apply()
    }

    fun apply() {
        rootRendererHold?.let { checkpoint ->
            checkpoint.defer { if (!isDisposed && !project.isDisposed) apply() }
            return
        }
        if (AyuIslandsSettings.getInstance().state == null) return
        canFilterRoot = LicenseChecker.isLicensedOrGrace()
        if (!canFilterRoot) {
            cleanupRuntime()
            return
        }
        applyScrollbar()
        applyRootDisplay()
        manageAutoFit()
    }

    private fun applyScrollbar() {
        if (scrollbarHold != null || isDisposed || project.isDisposed) return
        val scrollPane = findProjectScrollPane() ?: return
        val shouldHide =
            AyuIslandsSettings.getInstance().state?.hideProjectViewHScrollbar ?: return

        if (shouldHide) {
            if (originalScrollbarPolicy == null) {
                originalScrollbarPolicy =
                    scrollPane.horizontalScrollBarPolicy
            }
            scrollPane.horizontalScrollBarPolicy =
                ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
        } else if (originalScrollbarPolicy != null) {
            scrollPane.horizontalScrollBarPolicy =
                originalScrollbarPolicy!!
            originalScrollbarPolicy = null
        }
    }

    /** Observes the current native pane and existing baseline without changing settings or acquiring a hold. */
    internal fun prepareScrollbar(hideH: Boolean): ScrollbarCheckpoint? {
        check(SwingUtilities.isEventDispatchThread()) { "Project scrollbar capture must run on EDT" }
        check(scrollbarHold == null) { "Project scrollbar recovery must settle before capture" }
        if (isDisposed || project.isDisposed) return null
        return findProjectScrollPane(onlyExisting = true)?.let { ScrollbarCheckpoint(it, hideH) }
    }

    internal data class ScrollbarPolicy(
        val policy: Int,
        val originalPolicy: Int?,
    )

    /** Retains the existing fitter; capture never starts a second width owner or applies sibling settings. */
    internal fun prepareWidth(width: WorkspaceWidth): ToolWindowAutoFitter.WidthCheckpoint? {
        if (isDisposed || project.isDisposed) return null
        return autoFitter.prepareWidth(PanelWidthMode.fromString(width.mode), width.maximum, width.fixed, width.minimum)
    }

    /** Captures native renderer identity only; Registry membership is retained by the shared root-path lease. */
    internal fun prepareRootRenderer(hidePath: Boolean): RootRendererCheckpoint {
        check(SwingUtilities.isEventDispatchThread()) { "Project root capture must run on EDT" }
        check(rootRendererHold == null) { "Project root recovery must settle before capture" }
        return RootRendererCheckpoint(
            if (isDisposed ||
                project.isDisposed
            ) {
                null
            } else {
                findProjectTree(onlyExisting = true)
            },
            hidePath,
        )
    }

    internal inner class RootRendererCheckpoint(
        private val tree: JTree?,
        val hidePath: Boolean,
    ) : AccentWriteCheckpoint {
        private val target = UiDefaultsTarget()
        private val before = tree?.cellRenderer
        private val planned =
            when {
                hidePath && before != null && before !is RootFilteringRenderer -> wrapRenderer(before)
                !hidePath && before is RootFilteringRenderer -> before.delegate
                else -> before
            }
        private var expected = before
        private val priorTree = trackedTree
        private val priorGuard = rendererListener
        private val priorEligibility = canFilterRoot
        private val candidateGuard =
            tree?.takeIf { hidePath && (priorTree !== it || priorGuard == null) }?.let(::createRendererGuard)
        private var needsCleanup = false
        private val deferred = mutableListOf<() -> Unit>()

        override var state: AccentWriteState = AccentWriteState.PREPARED
            private set

        override val hasPending: Boolean get() = state == AccentWriteState.OWNED || needsCleanup

        val records: List<ProjectRootRendererRecord>
            get() = tree?.let { listOf(ProjectRootRendererRecord(it, before, planned, expected, state)) } ?: emptyList()

        override fun apply(): Boolean {
            check(SwingUtilities.isEventDispatchThread()) { "Project root writes must run on EDT" }
            check(state == AccentWriteState.PREPARED) { "Project root application was already started" }
            if (rootRendererHold != null || !isCurrent() || tree?.cellRenderer !== expected) {
                state = AccentWriteState.RELINQUISHED
                return false
            }
            rootRendererHold = this
            needsCleanup = true
            state = AccentWriteState.OWNED
            if (tree != null) {
                canFilterRoot = LicenseChecker.isLicensedOrGrace()
                write(planned)
                if (!ownsRenderer()) return false
                if (candidateGuard != null) tree.addPropertyChangeListener("cellRenderer", candidateGuard)
            }
            return ownsRenderer()
        }

        override fun restore(blockedBy: List<AccentCheckpoint>) {
            check(SwingUtilities.isEventDispatchThread()) { "Project root restoration must run on EDT" }
            if (state == AccentWriteState.PREPARED) {
                state = AccentWriteState.RESTORED
                return
            }
            if (!hasPending) return
            check(blockedBy.none { it is RootRendererCheckpoint && it.tree === tree && it.hasPending }) {
                "Project root restoration is blocked by a newer write"
            }
            if (ownsRenderer()) {
                write(before)
                ownsRenderer()
            }
            discardCandidateGuard()
            if (tree != null) canFilterRoot = priorEligibility
            if (state == AccentWriteState.OWNED) state = AccentWriteState.RESTORED
            needsCleanup = false
            releaseResources()
        }

        override fun commit() {
            check(SwingUtilities.isEventDispatchThread()) { "Project root acceptance must run on EDT" }
            if (state == AccentWriteState.PREPARED) {
                state = AccentWriteState.RESTORED
                return
            }
            if (!hasPending) return
            if (!ownsRenderer()) {
                discardCandidateGuard()
                if (tree != null) canFilterRoot = priorEligibility
            } else {
                if (tree != null) acceptGuard()
                state = AccentWriteState.RESTORED
            }
            needsCleanup = false
            releaseResources()
        }

        override fun releaseResources() {
            if (hasPending) return
            for (action in deferred.toList()) {
                AccentApplyPlanRunner.post { action() }
                deferred.remove(action)
            }
            if (rootRendererHold === this) rootRendererHold = null
        }

        override fun retain() {
            check(!hasPending || rootRendererHold === this) { "Project root recovery lost its hold" }
        }

        internal fun defer(action: () -> Unit) {
            deferred += action
        }

        private fun isCurrent(): Boolean =
            !isDisposed && !project.isDisposed && target.isCurrent() && findProjectTree(onlyExisting = true) === tree

        private fun ownsRenderer(): Boolean {
            if (state != AccentWriteState.OWNED) return false
            if (!isCurrent() || tree?.cellRenderer !== expected) state = AccentWriteState.RELINQUISHED
            return state == AccentWriteState.OWNED
        }

        private fun write(renderer: TreeCellRenderer?) {
            val previous = expected
            expected = renderer
            try {
                if (tree != null && tree.cellRenderer !== renderer) tree.cellRenderer = renderer
            } finally {
                val observed = tree?.cellRenderer
                if (observed !== expected) {
                    if (observed === previous) expected = previous else state = AccentWriteState.RELINQUISHED
                }
            }
        }

        private fun discardCandidateGuard() {
            if (tree != null &&
                candidateGuard != null &&
                tree.getPropertyChangeListeners("cellRenderer").any { it === candidateGuard }
            ) {
                tree.removePropertyChangeListener("cellRenderer", candidateGuard)
            }
        }

        private fun acceptGuard() {
            if (!hidePath || candidateGuard != null) {
                if (priorTree != null &&
                    priorGuard != null &&
                    priorTree.getPropertyChangeListeners("cellRenderer").any { it === priorGuard }
                ) {
                    priorTree.removePropertyChangeListener("cellRenderer", priorGuard)
                }
                trackedTree = if (hidePath) tree else null
                rendererListener = if (hidePath) candidateGuard else null
            }
            lastAppliedHidePath = hidePath
        }
    }

    /** Retains one policy and the manager's original baseline until outer acceptance or completed rollback. */
    internal inner class ScrollbarCheckpoint(
        val scrollPane: JScrollPane,
        hideH: Boolean,
    ) : AccentWriteCheckpoint {
        private val target = UiDefaultsTarget()
        val before = ScrollbarPolicy(scrollPane.horizontalScrollBarPolicy, originalScrollbarPolicy)
        val planned =
            if (hideH) {
                ScrollbarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER, before.originalPolicy ?: before.policy)
            } else {
                ScrollbarPolicy(before.originalPolicy ?: before.policy, null)
            }
        private var expected = before
        val lastWritten: ScrollbarPolicy get() = expected
        override var state: AccentWriteState = AccentWriteState.PREPARED
            private set

        override val hasPending: Boolean
            get() = state == AccentWriteState.OWNED

        override fun apply(): Boolean {
            check(SwingUtilities.isEventDispatchThread()) { "Project scrollbar writes must run on EDT" }
            check(state == AccentWriteState.PREPARED) { "Project scrollbar application was already started" }
            if (scrollbarHold != null) {
                state = AccentWriteState.RELINQUISHED
                return false
            }
            if (!isCurrent() || !matches(expected)) {
                relinquish()
                return false
            }
            scrollbarHold = this
            state = AccentWriteState.OWNED
            write(planned)
            return permitsWrite()
        }

        override fun restore(blockedBy: List<AccentCheckpoint>) {
            check(SwingUtilities.isEventDispatchThread()) { "Project scrollbar restoration must run on EDT" }
            if (state == AccentWriteState.PREPARED) {
                state = AccentWriteState.RESTORED
                return
            }
            if (!hasPending) return
            check(blockedBy.none { it is ScrollbarCheckpoint && it.scrollPane === scrollPane && it.hasPending }) {
                "Project scrollbar restoration is blocked by a newer write"
            }
            if (!permitsWrite()) return
            write(before)
            if (permitsWrite()) {
                state = AccentWriteState.RESTORED
                releaseResources()
            }
        }

        override fun commit() {
            check(SwingUtilities.isEventDispatchThread()) { "Project scrollbar acceptance must run on EDT" }
            if (hasPending && !permitsWrite()) return
            if (state != AccentWriteState.RELINQUISHED) state = AccentWriteState.RESTORED
            releaseResources()
        }

        override fun releaseResources() {
            if (!hasPending && scrollbarHold === this) scrollbarHold = null
        }

        override fun retain() {
            check(!hasPending || scrollbarHold === this) { "Project scrollbar recovery lost its hold" }
        }

        private fun write(next: ScrollbarPolicy) {
            val previous = expected
            expected = next
            originalScrollbarPolicy = next.originalPolicy
            try {
                if (scrollPane.horizontalScrollBarPolicy !=
                    next.policy
                ) {
                    scrollPane.horizontalScrollBarPolicy = next.policy
                }
            } finally {
                val observed = ScrollbarPolicy(scrollPane.horizontalScrollBarPolicy, originalScrollbarPolicy)
                if (observed != expected) {
                    if (observed.policy == previous.policy && observed.originalPolicy == next.originalPolicy) {
                        expected = observed
                    } else {
                        relinquish()
                    }
                }
            }
        }

        private fun permitsWrite(): Boolean {
            if (!hasPending) return false
            if (!isCurrent() || !matches(expected)) relinquish()
            return hasPending
        }

        private fun matches(policy: ScrollbarPolicy): Boolean =
            scrollPane.horizontalScrollBarPolicy == policy.policy && originalScrollbarPolicy == policy.originalPolicy

        private fun isCurrent(): Boolean =
            !isDisposed &&
                !project.isDisposed &&
                target.isCurrent() &&
                findProjectScrollPane(onlyExisting = true) === scrollPane

        private fun relinquish() {
            if (originalScrollbarPolicy == expected.originalPolicy) originalScrollbarPolicy = null
            state = AccentWriteState.RELINQUISHED
            releaseResources()
        }
    }

    private fun manageAutoFit() {
        val state = AyuIslandsSettings.getInstance().state ?: return
        when (PanelWidthMode.fromString(state.projectPanelWidthMode)) {
            PanelWidthMode.DEFAULT -> {
                autoFitter.removeExpansionListener()
            }

            PanelWidthMode.AUTO_FIT -> {
                autoFitter.installExpansionListener()
                autoFitter.applyAutoFitWidth(state.autoFitMaxWidth)
            }

            PanelWidthMode.FIXED -> {
                autoFitter.removeExpansionListener()
                autoFitter.applyFixedWidth(state.projectPanelFixedWidth)
            }
        }
    }

    private fun applyRootDisplay() {
        if (rootPathLease.deferIfHeld { if (!isDisposed && !project.isDisposed) apply() }) return
        val hidePath = AyuIslandsSettings.getInstance().state?.hideProjectRootPath ?: return
        if (hidePath == lastAppliedHidePath) return
        lastAppliedHidePath = hidePath

        val tree = findProjectTree() ?: return

        if (hidePath) {
            installRendererWrapper(tree)
            installRendererGuard(tree)
        } else {
            removeRendererGuard()
            unwrapRenderer(tree)
        }

        // Force full tree rebuild to pick up Registry change
        ProjectView.getInstance(project).currentProjectViewPane?.updateFromRoot(true)
    }

    private fun installRendererWrapper(tree: JTree) {
        val current = tree.cellRenderer
        if (current is RootFilteringRenderer) return
        tree.cellRenderer = wrapRenderer(current)
    }

    private fun wrapRenderer(delegate: TreeCellRenderer): RootFilteringRenderer =
        RootFilteringRenderer(
            delegate,
            project,
            hidePath = {
                rootRendererHold?.hidePath ?: AyuIslandsSettings.getInstance().state?.hideProjectRootPath
                    ?: (lastAppliedHidePath == true)
            },
        ) { canFilterRoot && !isDisposed && !project.isDisposed }

    private fun unwrapRenderer(tree: JTree) {
        val current = tree.cellRenderer
        if (current is RootFilteringRenderer) {
            tree.cellRenderer = current.delegate
        }
    }

    private fun installRendererGuard(tree: JTree) {
        if (trackedTree === tree && rendererListener != null) return

        removeRendererGuard()
        trackedTree = tree
        rendererListener = createRendererGuard(tree)
        tree.addPropertyChangeListener(
            "cellRenderer",
            rendererListener,
        )
    }

    private fun createRendererGuard(tree: JTree): PropertyChangeListener =
        PropertyChangeListener { event ->
            if (event.propertyName == "cellRenderer") {
                if (rootRendererHold != null) return@PropertyChangeListener
                val newRenderer =
                    event.newValue as? TreeCellRenderer
                        ?: return@PropertyChangeListener
                canFilterRoot = LicenseChecker.isLicensedOrGrace()
                if (newRenderer !is RootFilteringRenderer &&
                    canFilterRoot &&
                    (AyuIslandsSettings.getInstance().state?.hideProjectRootPath == true)
                ) {
                    tree.cellRenderer = wrapRenderer(newRenderer)
                }
            }
        }

    private fun removeRendererGuard() {
        val tree = trackedTree ?: return
        val listener = rendererListener ?: return
        tree.removePropertyChangeListener(
            "cellRenderer",
            listener,
        )
        trackedTree = null
        rendererListener = null
    }

    private fun findProjectScrollPane(onlyExisting: Boolean = false): JScrollPane? {
        val content = findProjectContent(onlyExisting) ?: return null
        return AutoFitCalculator.findFirstOfType(
            content,
            JScrollPane::class.java,
        ) as? JScrollPane
    }

    private fun findProjectTree(onlyExisting: Boolean = false): JTree? {
        val content = findProjectContent(onlyExisting) ?: return null
        return AutoFitCalculator.findFirstOfType(
            content,
            JTree::class.java,
        ) as? JTree
    }

    private fun findProjectContent(onlyExisting: Boolean): Component? {
        val toolWindow =
            ToolWindowManager
                .getInstance(project)
                .getToolWindow(TOOL_WINDOW_ID)
                ?: return null
        val contentManager = if (onlyExisting) toolWindow.contentManagerIfCreated else toolWindow.contentManager
        return contentManager
            ?.contents
            ?.firstOrNull()
            ?.component
    }

    override fun dispose() {
        isDisposed = true
        cleanupRuntime()
    }

    private fun cleanupRuntime() {
        rootRendererHold?.let { checkpoint ->
            checkpoint.defer { cleanupRuntime() }
            return
        }
        canFilterRoot = false
        removeRendererGuard()
        autoFitter.removeExpansionListener()
        val scrollPane = findProjectScrollPane()
        if (scrollbarHold == null && scrollPane != null && originalScrollbarPolicy != null) {
            scrollPane.horizontalScrollBarPolicy =
                originalScrollbarPolicy!!
            originalScrollbarPolicy = null
        }
        val tree = findProjectTree()
        if (tree != null) {
            unwrapRenderer(tree)
        }
        lastAppliedHidePath = null
    }

    companion object {
        private const val TOOL_WINDOW_ID = "Project"
        private const val SHOW_URL_KEY = "project.tree.structure.show.url"

        internal fun showUrlRegistryValue(): RegistryValue = Registry.get(SHOW_URL_KEY)

        fun getInstance(project: Project): ProjectViewScrollbarManager =
            project.getService(
                ProjectViewScrollbarManager::class.java,
            )
    }
}

internal data class ProjectRootRendererRecord(
    val tree: JTree,
    val before: TreeCellRenderer?,
    val planned: TreeCellRenderer?,
    val lastWritten: TreeCellRenderer?,
    val state: AccentWriteState,
)

/**
 * Filters root node path fragments.
 * Reads the path-display setting live; entitlement is supplied by the owning manager.
 */
internal class RootFilteringRenderer(
    val delegate: TreeCellRenderer,
    private val project: Project,
    private val hidePath: () -> Boolean = { (AyuIslandsSettings.getInstance().state?.hideProjectRootPath == true) },
    private val canApply: () -> Boolean = { LicenseChecker.isLicensedOrGrace() },
) : TreeCellRenderer {
    override fun getTreeCellRendererComponent(
        tree: JTree,
        value: Any?,
        selected: Boolean,
        expanded: Boolean,
        leaf: Boolean,
        row: Int,
        hasFocus: Boolean,
    ): Component {
        val component =
            delegate.getTreeCellRendererComponent(
                tree,
                value,
                selected,
                expanded,
                leaf,
                row,
                hasFocus,
            )
        if (canApply() && row == 0 && component is SimpleColoredComponent) {
            filterRootFragments(component)
        }
        return component
    }

    private fun filterRootFragments(component: SimpleColoredComponent) {
        if (!hidePath()) return

        val basePath = project.basePath
        val tildeBasePath =
            basePath?.replace(System.getProperty("user.home"), "~")
        val context =
            RootNodeContext(
                projectName = project.name,
                basePath = basePath,
                tildeBasePath = tildeBasePath,
            )
        val kept =
            mutableListOf<Pair<String, SimpleTextAttributes>>()
        val iter = component.iterator()
        while (iter.hasNext()) {
            iter.next()
            val text = iter.fragment
            val trimmed = text.trim()
            if (!RootFragmentFilter.isPathFragment(trimmed, context)) {
                kept.add(text to iter.textAttributes)
            }
        }

        val savedIcon = component.icon
        component.clear()
        component.icon = savedIcon
        for ((text, attrs) in kept) {
            component.append(text, attrs)
        }
    }
}
