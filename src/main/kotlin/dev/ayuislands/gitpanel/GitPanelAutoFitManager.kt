package dev.ayuislands.gitpanel

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Splitter
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.openapi.wm.ex.ToolWindowManagerListener
import com.intellij.ui.JBSplitter
import com.intellij.ui.content.Content
import com.intellij.ui.content.ContentManager
import dev.ayuislands.accent.AccentApplyPlanRunner
import dev.ayuislands.accent.AccentCheckpoint
import dev.ayuislands.accent.AccentWriteCheckpoint
import dev.ayuislands.accent.AccentWriteState
import dev.ayuislands.accent.UiDefaultsTarget
import dev.ayuislands.accent.combineUiFailures
import dev.ayuislands.licensing.LicenseChecker
import dev.ayuislands.settings.AyuIslandsSettings
import dev.ayuislands.settings.PanelWidthMode
import dev.ayuislands.settings.WorkspaceWidth
import dev.ayuislands.toolwindow.AutoFitCalculator
import dev.ayuislands.toolwindow.shouldTriggerAutoFitFor
import org.jetbrains.annotations.TestOnly
import javax.swing.JComponent
import javax.swing.JTable
import javax.swing.JTree
import javax.swing.SwingUtilities
import javax.swing.Timer
import javax.swing.event.TreeExpansionEvent
import javax.swing.event.TreeExpansionListener

/** Per-project service that manages Git panel internal splitter proportions (auto-fit or fixed). */
@Service(Service.Level.PROJECT)
class GitPanelAutoFitManager(
    private val project: Project,
) : Disposable {
    private val debounceTimer = Timer(DEBOUNCE_DELAY_MS) { apply() }.apply { isRepeats = false }
    private val expansionListeners = mutableListOf<Pair<JTree, TreeExpansionListener>>()
    private var held: Checkpoint? = null
    private var isDisposed = false

    init {
        project.messageBus.connect(this).subscribe(
            ToolWindowManagerListener.TOPIC,
            object : ToolWindowManagerListener {
                override fun stateChanged(
                    toolWindowManager: ToolWindowManager,
                    changeType: ToolWindowManagerListener.ToolWindowManagerEventType,
                ) {
                    if (changeType.shouldTriggerAutoFitFor(
                            toolWindowManager,
                            TOOL_WINDOW_ID,
                        )
                    ) {
                        scheduleFitIfWidthManaged()
                    }
                }
            },
        )
    }

    private fun scheduleFitIfWidthManaged() {
        if (isDisposed || project.isDisposed) return
        held?.let {
            it.deferFit()
            return
        }
        if (!LicenseChecker.isLicensedOrGrace() ||
            PanelWidthMode.fromString(savedWidth()?.mode) == PanelWidthMode.DEFAULT
        ) {
            return
        }
        debounceTimer.restart()
    }

    @TestOnly
    internal fun flushDebounceForTesting() {
        val hadPending = debounceTimer.isRunning
        debounceTimer.stop()
        if (hadPending) apply()
    }

    fun apply() {
        if (isDisposed || project.isDisposed) return
        held?.let {
            it.deferFit()
            return
        }
        if (!LicenseChecker.isLicensedOrGrace()) {
            removeExpansionListeners()
            return
        }
        val width = savedWidth() ?: return
        val mode = PanelWidthMode.fromString(width.mode)
        if (mode != PanelWidthMode.AUTO_FIT) removeExpansionListeners()
        if (mode == PanelWidthMode.DEFAULT) return
        val content = observeLog().component ?: return
        if (mode == PanelWidthMode.AUTO_FIT) installExpansionListeners(content)
        for (splitter in splitters(content)) {
            val planned = planProportion(splitter, width) ?: continue
            if (!LicenseChecker.isLicensedOrGrace()) return
            splitter.proportion = planned
        }
    }

    private fun savedWidth(): WorkspaceWidth? =
        AyuIslandsSettings.getInstance().state?.let {
            WorkspaceWidth(
                it.gitPanelWidthMode,
                it.gitPanelAutoFitMinWidth,
                it.gitPanelAutoFitMaxWidth,
                it.gitPanelFixedWidth,
            )
        }

    private fun planProportion(
        splitter: Splitter,
        width: WorkspaceWidth,
    ): Float? {
        if (splitter.width <= 0) return null
        val first = splitter.firstComponent ?: return null
        val second = splitter.secondComponent ?: return null
        val isInner = AutoFitCalculator.findFirstOfType(first, JTable::class.java) != null
        val desired =
            when (PanelWidthMode.fromString(width.mode)) {
                PanelWidthMode.DEFAULT -> {
                    return null
                }

                PanelWidthMode.FIXED -> {
                    width.fixed
                }

                PanelWidthMode.AUTO_FIT -> {
                    val tree =
                        AutoFitCalculator.findFirstOfType(if (isInner) second else first, JTree::class.java)
                            as? JTree ?: return null
                    val measured = AutoFitCalculator.measureTreeMaxRowWidth(tree)
                    val calculated = AutoFitCalculator.calculateDesiredWidth(measured, width.maximum, width.minimum)
                    val current =
                        ((if (isInner) 1.0f - splitter.proportion else splitter.proportion) * splitter.width)
                            .toInt()
                    if (AutoFitCalculator.isJitterOnly(current, calculated)) return null
                    calculated
                }
            }
        return if (isInner) {
            (1.0f - desired.toFloat() / splitter.width.toFloat()).coerceIn(MIN_INNER_PROPORTION, MAX_INNER_PROPORTION)
        } else {
            (desired.toFloat() / splitter.width.toFloat()).coerceIn(MIN_OUTER_PROPORTION, MAX_OUTER_PROPORTION)
        }
    }

    private fun installExpansionListeners(content: JComponent) {
        val tracked = expansionListeners.map { it.first }.toSet()
        for (tree in trees(content)) {
            if (tree in tracked) continue
            val pair = tree to expansionListener()
            expansionListeners += pair
            tree.addTreeExpansionListener(pair.second)
        }
    }

    private fun expansionListener(): TreeExpansionListener =
        object : TreeExpansionListener {
            override fun treeExpanded(event: TreeExpansionEvent) = scheduleFitIfWidthManaged()

            override fun treeCollapsed(event: TreeExpansionEvent) = scheduleFitIfWidthManaged()
        }

    private fun removeExpansionListeners(stopDebounce: Boolean = true) {
        if (stopDebounce) debounceTimer.stop()
        var failure: Throwable? = null
        for (pair in expansionListeners.toList()) {
            val before = listenerCount(pair)
            if (before == 0) {
                expansionListeners.remove(pair)
                continue
            }
            try {
                pair.first.removeTreeExpansionListener(pair.second)
                check(listenerCount(pair) == before - 1) { "Git expansion listener removal did not settle" }
            } catch (exception: RuntimeException) {
                failure = combineUiFailures(failure, exception)
            } finally {
                if (listenerCount(pair) != before) expansionListeners.remove(pair)
            }
        }
        failure?.let { throw it }
    }

    /** Observes existing geometry and listeners without initializing content or holding callbacks. */
    internal fun capture(
        width: WorkspaceWidth,
        proportions: ((String) -> GitProportionCheckpoint)? = null,
    ): Checkpoint {
        checkEdt()
        check(!isDisposed && !project.isDisposed && held == null) { "Git width manager is unavailable" }
        val local = linkedMapOf<String, GitProportionCheckpoint>()
        val resolve = proportions ?: { key: String -> local.getOrPut(key) { GitProportionCheckpoint(key) } }
        return Checkpoint(width, resolve, local.values)
    }

    internal inner class Checkpoint internal constructor(
        width: WorkspaceWidth,
        resolveProperty: (String) -> GitProportionCheckpoint,
        private val ownedProperties: Collection<GitProportionCheckpoint>,
    ) : AccentWriteCheckpoint {
        private val target = UiDefaultsTarget()
        private val log = observeLog()
        private val previousListeners = expansionListeners.toList()
        private val properties = linkedSetOf<GitProportionCheckpoint>()
        private val geometry =
            log.component
                ?.let { content ->
                    splitters(content).map { splitter ->
                        val key = (splitter as? JBSplitter)?.splitterProportionKey?.takeIf { it.isNotEmpty() }
                        val property = key?.let(resolveProperty)?.also(properties::add)
                        GitSplitterCheckpoint(
                            splitter,
                            planProportion(splitter, width) ?: splitter.proportion,
                            ::isLogCurrent,
                            property,
                        )
                    }
                }.orEmpty()
        private val listeners =
            buildList {
                val isAutoFit = PanelWidthMode.fromString(width.mode) == PanelWidthMode.AUTO_FIT
                previousListeners.forEach { add(ListenerChange(it, true, isAutoFit, ::isCurrent)) }
                if (isAutoFit) {
                    val tracked = previousListeners.map { it.first }.toSet()
                    log.component?.let { content ->
                        for (tree in trees(content)) {
                            if (tree !in
                                tracked
                            ) {
                                add(
                                    ListenerChange(
                                        tree to expansionListener(),
                                        wasOwned = false,
                                        shouldKeep = true,
                                        ::isLogCurrent,
                                    ),
                                )
                            }
                        }
                    }
                }
            }
        private var phase = Phase.PREPARED
        private var hasDeferredFit = false
        val records: List<GitSplitterRecord>
            get() = geometry.map { it.record }
        val listenerRecords: List<GitListenerRecord>
            get() = listeners.map { it.record }
        val propertyRecords: List<GitProportionRecord>
            get() = properties.map { it.record }
        override val hasPending: Boolean
            get() = phase != Phase.DONE
        override val state: AccentWriteState
            get() =
                when {
                    geometry.any { it.state == AccentWriteState.RELINQUISHED } ||
                        listeners.any { it.state == AccentWriteState.RELINQUISHED } -> AccentWriteState.RELINQUISHED

                    phase == Phase.PREPARED -> AccentWriteState.PREPARED

                    phase == Phase.DONE -> AccentWriteState.RESTORED

                    else -> AccentWriteState.OWNED
                }

        override fun apply(): Boolean {
            checkEdt()
            check(phase == Phase.PREPARED) { "Git width change was already started" }
            if (held != null || !isLogCurrent() || !matchesBefore()) {
                geometry.forEach { it.relinquish() }
                listeners.forEach { it.relinquish() }
                phase = Phase.DONE
                return false
            }
            phase = Phase.APPLYING
            held = this
            hasDeferredFit = debounceTimer.isRunning
            debounceTimer.stop()
            listeners.forEach { it.apply() }
            for (change in geometry) check(change.apply()) { "Git splitter changed during application" }
            phase = Phase.APPLIED
            return true
        }

        override fun restore(blockedBy: List<AccentCheckpoint>) {
            checkEdt()
            check(phase != Phase.COMMITTED) { "Accepted Git width changes cannot be restored" }
            if (phase == Phase.DONE) return
            phase = Phase.RESTORING
            val newer = blockedBy.filterIsInstance<Checkpoint>()
            ownedProperties.forEach { it.prepareRestore() }
            var failure: Throwable? = null
            for (change in geometry.asReversed()) {
                try {
                    change.restore(newer.flatMap { it.geometry })
                } catch (exception: RuntimeException) {
                    failure = combineUiFailures(failure, exception)
                }
            }
            for (change in listeners.asReversed()) {
                try {
                    check(newer.none { it.hasPending && it.listeners.any { entry -> entry.overlaps(change) } }) {
                        "Git expansion listener restoration is blocked by a newer write"
                    }
                    change.restore()
                } catch (exception: RuntimeException) {
                    failure = combineUiFailures(failure, exception)
                }
            }
            if (failure == null) {
                for (property in ownedProperties.toList().asReversed()) {
                    try {
                        property.restore(newer.flatMap { it.properties })
                    } catch (exception: RuntimeException) {
                        failure = combineUiFailures(failure, exception)
                    }
                }
            }
            failure?.let { throw it }
            phase = Phase.RESTORED
            releaseResources()
        }

        override fun commit() {
            checkEdt()
            if (phase == Phase.DONE || phase == Phase.COMMITTED) return
            check(phase == Phase.APPLIED) { "Git width application has not completed" }
            geometry.forEach { it.commit() }
            listeners.forEach { it.commit() }
            ownedProperties.forEach { it.commit() }
            phase = Phase.COMMITTED
        }

        override fun releaseResources() {
            checkEdt()
            if (phase == Phase.DONE) return
            check(phase == Phase.COMMITTED || phase == Phase.RESTORED) { "Git width is not settled" }
            if (expansionListeners.isNotEmpty()) AccentApplyPlanRunner.post { settleListeners() }
            if (held === this) held = null
            phase = Phase.DONE
            if (hasDeferredFit && !isDisposed && !project.isDisposed) debounceTimer.restart()
        }

        override fun retain() {
            checkEdt()
            if (phase != Phase.PREPARED && phase != Phase.DONE) {
                check(held == null || held === this) { "Another Git width change owns callbacks" }
                held = this
            }
        }

        internal fun deferFit() {
            hasDeferredFit = true
        }

        internal fun close() {
            hasDeferredFit = false
            if (phase == Phase.COMMITTED) releaseResources() else restore()
        }

        private fun isCurrent(): Boolean = !isDisposed && !project.isDisposed && target.isCurrent()

        private fun isLogCurrent(): Boolean = isCurrent() && observeLog().matches(log)

        private fun matchesBefore(): Boolean =
            expansionListeners == previousListeners &&
                geometry.all { it.matchesBefore() } &&
                listeners.all { it.matchesBefore() }
    }

    private inner class ListenerChange(
        private val pair: Pair<JTree, TreeExpansionListener>,
        private val wasOwned: Boolean,
        shouldKeep: Boolean,
        private val isCurrent: () -> Boolean,
    ) {
        private val before = listenerCount(pair)
        private val planned =
            if (shouldKeep) {
                if (wasOwned) {
                    before
                } else {
                    before + 1
                }
            } else {
                (before - 1).coerceAtLeast(0)
            }
        private var expected = before
        var state = AccentWriteState.PREPARED
            private set
        val record: GitListenerRecord
            get() = GitListenerRecord(pair.first, pair.second, before, planned, expected, state)

        fun matchesBefore(): Boolean = isCurrent() && listenerCount(pair) == before

        fun overlaps(other: ListenerChange): Boolean = pair == other.pair && state == AccentWriteState.OWNED

        fun apply() {
            check(matchesBefore()) { "Git expansion listener changed before application" }
            state = AccentWriteState.OWNED
            // Retain original positions until acceptance; reattaching would reorder foreign listeners.
            if (wasOwned) return
            expansionListeners += pair
            expected = planned
            pair.first.addTreeExpansionListener(pair.second)
            check(listenerCount(pair) == planned) { "Git expansion listener attachment did not settle" }
        }

        fun restore() {
            if (state == AccentWriteState.PREPARED) {
                state = AccentWriteState.RESTORED
                return
            }
            if (state != AccentWriteState.OWNED) return
            // New listener identities remain ours to retire even after the Log or LAF changes.
            if (wasOwned && !isCurrent()) {
                relinquish()
                return
            }
            val current = listenerCount(pair)
            if (current != expected && current != before) {
                relinquish()
                return
            }
            if (!wasOwned && current != before) {
                expected = before
                pair.first.removeTreeExpansionListener(pair.second)
                check(listenerCount(pair) == before) { "Git expansion listener restoration did not settle" }
            }
            if (!wasOwned) expansionListeners.remove(pair)
            state = AccentWriteState.RESTORED
        }

        fun commit() {
            if (!isCurrent() || listenerCount(pair) != expected) relinquish()
            if (state != AccentWriteState.RELINQUISHED) state = AccentWriteState.RESTORED
        }

        fun relinquish() {
            if (state == AccentWriteState.OWNED && listenerCount(pair) == 0) expansionListeners.remove(pair)
            state = AccentWriteState.RELINQUISHED
        }
    }

    private fun settleListeners() {
        if (isDisposed || project.isDisposed || held != null) return
        if (!LicenseChecker.isLicensedOrGrace() ||
            PanelWidthMode.fromString(savedWidth()?.mode) != PanelWidthMode.AUTO_FIT
        ) {
            removeExpansionListeners(stopDebounce = false)
        }
    }

    private fun observeLog(): LogTarget {
        val window = ToolWindowManager.getInstance(project).getToolWindow(TOOL_WINDOW_ID)
        val manager = window?.contentManagerIfCreated
        val content = manager?.contents?.firstOrNull { it.tabName == "Log" }
        return LogTarget(window, manager, content, content?.component)
    }

    override fun dispose() {
        isDisposed = true
        held?.close()
        removeExpansionListeners()
    }

    private class LogTarget(
        val window: ToolWindow?,
        val manager: ContentManager?,
        val content: Content?,
        val component: JComponent?,
    ) {
        fun matches(other: LogTarget): Boolean =
            window === other.window &&
                manager === other.manager &&
                content === other.content &&
                component === other.component
    }

    private enum class Phase { PREPARED, APPLYING, APPLIED, RESTORING, RESTORED, COMMITTED, DONE }

    companion object {
        private const val TOOL_WINDOW_ID = "Version Control"
        private const val DEBOUNCE_DELAY_MS = 150
        private const val MIN_OUTER_PROPORTION = 0.05f
        private const val MAX_OUTER_PROPORTION = 0.5f
        private const val MIN_INNER_PROPORTION = 0.5f
        private const val MAX_INNER_PROPORTION = 0.95f

        private fun checkEdt() = check(SwingUtilities.isEventDispatchThread()) { "Git width checkpoints require EDT" }

        private fun splitters(content: JComponent): List<Splitter> =
            AutoFitCalculator.findAllOfType(content, Splitter::class.java).filterIsInstance<Splitter>()

        private fun trees(content: JComponent): List<JTree> =
            AutoFitCalculator.findAllOfType(content, JTree::class.java).filterIsInstance<JTree>()

        private fun listenerCount(pair: Pair<JTree, TreeExpansionListener>): Int =
            pair.first.treeExpansionListeners.count { it === pair.second }

        fun getInstance(project: Project): GitPanelAutoFitManager =
            project.getService(GitPanelAutoFitManager::class.java)
    }
}

internal data class GitListenerRecord(
    val tree: JTree,
    val listener: TreeExpansionListener,
    val before: Int,
    val planned: Int,
    val lastWritten: Int,
    val state: AccentWriteState,
)
