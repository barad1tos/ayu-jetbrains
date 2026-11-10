package dev.ayuislands.toolwindow

import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.openapi.wm.ToolWindowType
import com.intellij.openapi.wm.ex.ToolWindowEx
import dev.ayuislands.accent.AccentApplyPlanRunner
import dev.ayuislands.accent.AccentCheckpoint
import dev.ayuislands.accent.AccentWriteCheckpoint
import dev.ayuislands.accent.AccentWriteState
import dev.ayuislands.settings.PanelWidthMode
import org.jetbrains.annotations.TestOnly
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
    private val canApply: () -> Boolean = { true },
) {
    private var expansion: ExpansionBinding? = null
    private var retryTimer: Timer? = null
    private var isApplying = false
    private var held: WidthCheckpoint? = null
    private var callbacksEnabled = true

    // Defensive idempotence: fingerprint of the last successfully-measured tree shape.
    // Composed of (rowCount, maxRowWidth) — these are the only inputs that affect
    // [applyAutoFitWidth]'s desired width. When a stray re-apply (e.g. focus swap,
    // listener re-fire) arrives with unchanged content AND the resulting width would
    // be within jitter of the current width, skip the stretchWidth call.
    // Reset in [removeExpansionListener] so a re-installed listener forces a fresh measurement.
    private var lastFingerprint: Int? = null
    private val debounceTimer =
        Timer(DEBOUNCE_DELAY_MS) {
            if (callbacksEnabled && canApply()) maxWidthProvider()?.let(::applyAutoFitWidth)
        }.apply { isRepeats = false }

    /** Lambda to get the current max width from settings (called at fit time, not init time). */
    var maxWidthProvider: () -> Int? = { DEFAULT_MAX_WIDTH }

    /** Lambda to get the current min width from settings (called at fit time, not init time). */
    var minWidthProvider: () -> Int? = { minWidth }

    fun applyAutoFitWidth(maxWidth: Int) {
        assert(SwingUtilities.isEventDispatchThread()) {
            "applyAutoFitWidth must be called on EDT"
        }
        held?.let {
            it.deferredFit = true
            return
        }
        if (!canApply()) return
        callbacksEnabled = true
        findTreeWithRetry { tree ->
            held?.let {
                it.deferredFit = true
                return@findTreeWithRetry
            }
            if (!canApply()) return@findTreeWithRetry
            val toolWindowEx =
                resolveToolWindowEx("Auto-fit")
                    ?: return@findTreeWithRetry

            fitTree(tree, toolWindowEx, maxWidth, minWidthProvider() ?: return@findTreeWithRetry)
        }
    }

    private fun fitTree(
        tree: JTree,
        toolWindow: ToolWindowEx,
        maximum: Int,
        minimum: Int,
    ) {
        val maxRowWidth = AutoFitCalculator.measureTreeMaxRowWidth(tree)
        val desiredWidth = AutoFitCalculator.calculateDesiredWidth(maxRowWidth, maximum, minimum)
        val fingerprint = computeFingerprint(tree.rowCount, maxRowWidth)
        if (fingerprint == lastFingerprint &&
            AutoFitCalculator.isJitterOnly(toolWindow.component.width, desiredWidth)
        ) {
            return
        }
        lastFingerprint = fingerprint
        applyWidth(toolWindow, desiredWidth)
    }

    fun applyFixedWidth(targetWidth: Int) {
        assert(SwingUtilities.isEventDispatchThread()) {
            "applyFixedWidth must be called on EDT"
        }
        if (held != null || !canApply()) return
        val toolWindowEx = resolveToolWindowEx("Fixed-width") ?: return
        applyWidth(toolWindowEx, targetWidth)
    }

    private fun resolveToolWindowEx(context: String): ToolWindowEx? {
        val toolWindow =
            ToolWindowManager
                .getInstance(project)
                .getToolWindow(toolWindowId)
        val toolWindowEx = toolWindow as? ToolWindowEx
        if (toolWindowEx == null) {
            LOG.warn(
                "$context: '$toolWindowId' is not " +
                    "ToolWindowEx (type: " +
                    "${toolWindow?.javaClass?.name})",
            )
        }
        return toolWindowEx
    }

    private fun applyWidth(
        toolWindow: ToolWindowEx,
        desiredWidth: Int,
    ) {
        if (isApplying) return
        val currentWidth = toolWindow.component.width
        if (AutoFitCalculator.isJitterOnly(currentWidth, desiredWidth)) return
        if (isSharingSidebar()) return
        isApplying = true
        try {
            val delta = desiredWidth - currentWidth
            when (toolWindow.type) {
                ToolWindowType.FLOATING, ToolWindowType.WINDOWED -> {
                    val window = SwingUtilities.getWindowAncestor(toolWindow.component) ?: return
                    window.setSize(desiredWidth, window.height)
                }

                else -> {
                    toolWindow.stretchWidth(delta)
                }
            }
        } finally {
            isApplying = false
        }
    }

    private fun isSharingSidebar(): Boolean {
        if (project.isDisposed) return false
        val manager = ToolWindowManager.getInstance(project)
        val ourWindow = manager.getToolWindow(toolWindowId) ?: return false
        if (!ourWindow.isVisible) return false
        val ourAnchor = ourWindow.anchor

        return manager.toolWindowIdSet.any { id ->
            id != toolWindowId &&
                manager.getToolWindow(id)?.let { other ->
                    other.isVisible &&
                        other.anchor == ourAnchor &&
                        (
                            other.type == ToolWindowType.DOCKED ||
                                other.type == ToolWindowType.SLIDING
                        )
                } == true
        }
    }

    fun applyWidthMode(
        mode: PanelWidthMode,
        autoFitMaxWidth: Int,
        fixedWidth: Int,
    ) {
        if (held != null) return
        if (!canApply()) {
            removeExpansionListener()
            return
        }
        when (mode) {
            PanelWidthMode.DEFAULT -> {
                removeExpansionListener()
            }

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
        held?.let {
            it.deferredListener = true
            return
        }
        if (!canApply()) return
        callbacksEnabled = true
        findTreeWithRetry { tree ->
            held?.let {
                it.deferredListener = true
                return@findTreeWithRetry
            }
            if (!canApply()) return@findTreeWithRetry
            if (expansion?.tree === tree) return@findTreeWithRetry

            removeExpansionListener()
            callbacksEnabled = true
            setExpansion(ExpansionBinding(tree))
        }
    }

    fun removeExpansionListener() {
        if (held != null) return
        callbacksEnabled = false
        retryTimer?.stop()
        retryTimer = null
        debounceTimer.stop()
        // Reset fingerprint so a future re-install forces a fresh measurement
        // (the previous tree may have been disposed; its rowCount/maxRowWidth
        // is stale).
        lastFingerprint = null
        setExpansion(null)
    }

    private fun setExpansion(next: ExpansionBinding?) {
        val previous = expansion
        if (previous === next) return
        expansion = next
        previous?.enabled = false
        previous?.detach()
        next?.enabled = true
        next?.attach()
    }

    private inner class ExpansionBinding(
        val tree: JTree,
    ) : TreeExpansionListener {
        var enabled = false
        val attached: Boolean
            get() = tree.treeExpansionListeners.any { it === this }

        fun snapshot(): ToolWindowListenerValue = ToolWindowListenerValue(attached, enabled)

        override fun treeExpanded(event: TreeExpansionEvent) {
            if (enabled) scheduleAutoFit()
        }

        override fun treeCollapsed(event: TreeExpansionEvent) {
            if (enabled) scheduleAutoFit()
        }

        fun attach() {
            if (!attached) tree.addTreeExpansionListener(this)
        }

        fun detach() {
            if (attached) tree.removeTreeExpansionListener(this)
        }
    }

    fun scheduleAutoFit() {
        if (!callbacksEnabled || !canApply()) {
            debounceTimer.stop()
            return
        }
        debounceTimer.restart()
    }

    /**
     * Stops the pending debounce timer (if any) and fires the fit immediately.
     * Used by tests to avoid wall-clock waits for the 150 ms debounce window.
     */
    @TestOnly
    internal fun flushDebounceForTesting() {
        val hadPending = debounceTimer.isRunning
        debounceTimer.stop()
        if (hadPending && callbacksEnabled) {
            maxWidthProvider()?.let(::applyAutoFitWidth)
        }
    }

    private fun findTreeWithRetry(
        retriesLeft: Int = MAX_RETRIES,
        onFound: (JTree) -> Unit,
    ) {
        if (project.isDisposed || !canApply()) return
        val tree = findTree()
        if (tree != null) {
            onFound(tree)
            return
        }
        if (retriesLeft > 0) {
            retryTimer?.stop()
            retryTimer =
                Timer(
                    (MAX_RETRIES - retriesLeft + 1) *
                        RETRY_DELAY_MS,
                ) {
                    if (!project.isDisposed && callbacksEnabled && canApply()) {
                        findTreeWithRetry(retriesLeft - 1, onFound)
                    }
                }.apply {
                    isRepeats = false
                    start()
                }
        } else {
            LOG.info(
                "Auto-fit: tree not found for " +
                    "'$toolWindowId' after " +
                    "$MAX_RETRIES retries",
            )
        }
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

    /** Observes an already-created tool window without initializing its native content. */
    internal fun findCreatedTree(): JTree? {
        val window = ToolWindowManager.getInstance(project).getToolWindow(toolWindowId) ?: return null
        val content =
            window.contentManagerIfCreated
                ?.contents
                ?.firstOrNull()
                ?.component ?: return null
        return AutoFitCalculator.findFirstOfType(content, JTree::class.java) as? JTree
    }

    /** Captures a supported native width and existing listener state without starting work or holding callbacks. */
    internal fun prepareWidth(
        mode: PanelWidthMode,
        autoFitMaxWidth: Int,
        fixedWidth: Int,
        autoFitMinWidth: Int,
    ): WidthCheckpoint? {
        check(SwingUtilities.isEventDispatchThread()) { "Tool window width capture must run on EDT" }
        check(held == null) { "Tool window width recovery must settle before capture" }
        if (project.isDisposed || isApplying) return null
        if (mode != PanelWidthMode.DEFAULT && isSharingSidebar()) return null
        if (mode != PanelWidthMode.DEFAULT && !canApply()) return null
        val target =
            if (mode == PanelWidthMode.DEFAULT) {
                null
            } else {
                ToolWindowWidthTarget.capture(ToolWindowManager.getInstance(project), toolWindowId) ?: return null
            }
        if (mode == PanelWidthMode.AUTO_FIT && target?.tree == null) return null
        return WidthCheckpoint(target, mode, autoFitMaxWidth, fixedWidth, autoFitMinWidth)
    }

    internal inner class WidthCheckpoint internal constructor(
        private val target: ToolWindowWidthTarget?,
        private val mode: PanelWidthMode,
        private val maximum: Int,
        fixedWidth: Int,
        private val minimum: Int,
    ) : AccentWriteCheckpoint {
        private val beforeExpansion = expansion
        private val beforeListener = beforeExpansion?.snapshot()
        private val beforeFingerprint = lastFingerprint
        private val beforeCallbacks = callbacksEnabled
        private val beforeAutoFit = beforeExpansion != null || retryTimer?.isRunning == true || debounceTimer.isRunning
        private val measuredWidth =
            if (mode ==
                PanelWidthMode.AUTO_FIT
            ) {
                target?.tree?.let(AutoFitCalculator::measureTreeMaxRowWidth)
            } else {
                null
            }
        private val rowCount = target?.tree?.rowCount
        private val desired =
            when (mode) {
                PanelWidthMode.DEFAULT -> {
                    null
                }

                PanelWidthMode.FIXED -> {
                    fixedWidth
                }

                PanelWidthMode.AUTO_FIT -> {
                    AutoFitCalculator.calculateDesiredWidth(
                        checkNotNull(measuredWidth),
                        maximum,
                        minimum,
                    )
                }
            }
        private val plannedExpansion =
            if (mode == PanelWidthMode.AUTO_FIT) {
                beforeExpansion?.takeIf { it.tree === target?.tree } ?: ExpansionBinding(checkNotNull(target?.tree))
            } else {
                null
            }
        private val planned = target?.plan(desired)
        private var expected = target?.before
        private var expectedExpansion = beforeExpansion
        private var lastBeforeListener = beforeListener
        private var lastPlannedListener = plannedExpansion?.snapshot()
        private var applied = false
        private var settled = false
        private var accepted = false
        private var autoFitAfterSettlement = false
        private var isGeometryRelinquished = false
        private var areListenersRelinquished = false
        private val relinquished: Boolean
            get() = isGeometryRelinquished || areListenersRelinquished
        internal var deferredFit = false
        internal var deferredListener = false
        override var state = AccentWriteState.PREPARED
            private set

        val records: List<ToolWindowWidthRecord>
            get() =
                target
                    ?.let {
                        listOf(
                            ToolWindowWidthRecord(
                                it.component,
                                it.field,
                                it.before.value,
                                checkNotNull(planned),
                                checkNotNull(expected).value,
                                recordState(isGeometryRelinquished),
                            ),
                        )
                    }.orEmpty()

        val listenerRecords: List<ToolWindowListenerRecord>
            get() =
                buildList {
                    beforeExpansion?.let {
                        add(
                            ToolWindowListenerRecord(
                                it.tree,
                                it,
                                checkNotNull(beforeListener),
                                ToolWindowListenerValue(beforeListener.attached, it === plannedExpansion),
                                checkNotNull(lastBeforeListener),
                                recordState(areListenersRelinquished),
                            ),
                        )
                    }
                    plannedExpansion?.takeIf { it !== beforeExpansion }?.let {
                        add(
                            ToolWindowListenerRecord(
                                it.tree,
                                it,
                                ToolWindowListenerValue(attached = false, enabled = false),
                                ToolWindowListenerValue(attached = true, enabled = true),
                                checkNotNull(lastPlannedListener),
                                recordState(areListenersRelinquished),
                            ),
                        )
                    }
                }

        override val hasPending: Boolean
            get() = state == AccentWriteState.OWNED && !settled

        override fun apply(): Boolean {
            check(SwingUtilities.isEventDispatchThread()) { "Tool window width writes must run on EDT" }
            check(state == AccentWriteState.PREPARED) { "Tool window width application was already started" }
            if (!matchesBefore()) {
                isGeometryRelinquished = true
                areListenersRelinquished = true
                finish()
                return false
            }
            held = this
            state = AccentWriteState.OWNED
            try {
                beforeExpansion?.enabled = false
                expansion = plannedExpansion
                plannedExpansion?.enabled = true
                plannedExpansion?.attach()
                if (mode != PanelWidthMode.AUTO_FIT) lastFingerprint = null
                if (mode == PanelWidthMode.FIXED) {
                    applyWidth(checkNotNull(target).toolWindow, checkNotNull(desired))
                } else if (mode == PanelWidthMode.AUTO_FIT) {
                    fitTree(checkNotNull(target?.tree), target.toolWindow, maximum, minimum)
                }
                applied = true
            } finally {
                observeListeners()
                observeWrite(planned)
            }
            return !relinquished
        }

        override fun restore(blockedBy: List<AccentCheckpoint>) {
            check(SwingUtilities.isEventDispatchThread()) { "Tool window width restoration must run on EDT" }
            if (settled) {
                releaseResources()
                return
            }
            if (state == AccentWriteState.PREPARED) {
                finish()
                return
            }
            check(blockedBy.none { it is WidthCheckpoint && it.hasPending && it.overlaps(this) }) {
                "Tool window width restoration is blocked by a newer write"
            }
            restoreGeometry()
            restoreExpansion()
            autoFitAfterSettlement = beforeAutoFit
            finish()
        }

        override fun commit() {
            check(SwingUtilities.isEventDispatchThread()) { "Tool window width acceptance must run on EDT" }
            if (settled) {
                releaseResources()
                return
            }
            check(applied) { "Tool window width application has not completed" }
            if (!current() || target?.geometry() != expected) isGeometryRelinquished = true
            accepted = true
            callbacksEnabled = mode == PanelWidthMode.AUTO_FIT
            autoFitAfterSettlement = callbacksEnabled
            finish()
        }

        override fun releaseResources() {
            check(SwingUtilities.isEventDispatchThread()) { "Tool window width release must run on EDT" }
            if (!settled || held !== this) return
            val obsolete = beforeExpansion?.takeIf { accepted && it !== plannedExpansion }
            val resume = !relinquished && autoFitAfterSettlement && (deferredFit || deferredListener)
            if (obsolete != null || resume || project.isDisposed) {
                val settledExpansion = expansion
                AccentApplyPlanRunner.post {
                    settleCallbacks(obsolete, resume, settledExpansion)
                }
            }
            held = null
        }

        private fun settleCallbacks(
            obsolete: ExpansionBinding?,
            shouldResume: Boolean,
            settledExpansion: ExpansionBinding?,
        ) {
            if (obsolete != null && expansion !== obsolete) {
                try {
                    obsolete.detach()
                } finally {
                    lastBeforeListener = obsolete.snapshot()
                }
            }
            if (project.isDisposed) {
                removeExpansionListener()
                observeListeners()
                return
            }
            if (!shouldResume || !current() || !canApply()) return
            if (!callbacksEnabled || target?.geometry() != expected || expansion !== settledExpansion) return
            if (deferredListener) installExpansionListener()
            if (deferredFit) maxWidthProvider()?.let(::applyAutoFitWidth)
        }

        override fun retain() {
            check(!hasPending || held === this) { "Tool window width recovery lost its callback hold" }
        }

        private fun matchesBefore(): Boolean =
            held == null &&
                current() &&
                target?.matchesBefore() != false &&
                expansion === beforeExpansion &&
                beforeExpansion?.snapshot() == beforeListener &&
                lastFingerprint == beforeFingerprint &&
                callbacksEnabled == beforeCallbacks &&
                (mode == PanelWidthMode.DEFAULT || canApply()) &&
                measurementMatches()

        private fun measurementMatches(): Boolean =
            mode != PanelWidthMode.AUTO_FIT ||
                (
                    target?.tree?.rowCount == rowCount &&
                        target?.tree?.let(AutoFitCalculator::measureTreeMaxRowWidth) == measuredWidth
                )

        private fun current(): Boolean =
            !project.isDisposed &&
                (target == null || (!isSharingSidebar() && target.isCurrent(ToolWindowManager.getInstance(project))))

        private fun observeWrite(plannedValue: ToolWindowWidthValue?) {
            val observed = target?.geometry()
            if (current() && (observed == expected || observed?.value == plannedValue)) {
                expected = observed
            } else {
                isGeometryRelinquished = true
            }
        }

        private fun restoreGeometry() {
            if (target == null) return
            if (current() && target.geometry() == expected && !isGeometryRelinquished) {
                try {
                    target.restore()
                } finally {
                    observeWrite(target.before.value)
                }
            } else {
                isGeometryRelinquished = true
            }
        }

        private fun restoreExpansion() {
            if (expansion !== expectedExpansion ||
                beforeExpansion?.snapshot() != lastBeforeListener ||
                plannedExpansion?.snapshot() != lastPlannedListener
            ) {
                areListenersRelinquished = true
                return
            }
            try {
                if (plannedExpansion !== beforeExpansion) {
                    plannedExpansion?.enabled = false
                    plannedExpansion?.detach()
                }
                if (beforeListener?.attached == false) beforeExpansion?.detach()
                expansion = beforeExpansion
                beforeExpansion?.enabled = beforeListener?.enabled == true && !project.isDisposed
                callbacksEnabled = beforeCallbacks
                lastFingerprint = beforeFingerprint
            } finally {
                observeListeners()
            }
        }

        private fun observeListeners() {
            expectedExpansion = expansion
            lastBeforeListener = beforeExpansion?.snapshot()
            lastPlannedListener = plannedExpansion?.snapshot()
        }

        private fun overlaps(other: WidthCheckpoint): Boolean =
            (target != null && target.component === other.target?.component && target.field == other.target.field) ||
                (beforeExpansion != null && beforeExpansion === other.beforeExpansion)

        private fun recordState(isRelinquished: Boolean): AccentWriteState =
            when {
                isRelinquished -> AccentWriteState.RELINQUISHED
                settled -> AccentWriteState.RESTORED
                else -> state
            }

        private fun finish() {
            settled = true
            state = if (relinquished) AccentWriteState.RELINQUISHED else AccentWriteState.RESTORED
            releaseResources()
        }
    }

    /**
     * Deterministic mix keyed on the two dimensions that drive [applyAutoFitWidth]'s
     * desired width: `rowCount` and `maxRowWidth`. The mix only needs collision
     * resistance against common neighbouring tree shapes — not cryptographic strength.
     * Centralised here so future tweaks (different multiplier, additional inputs such
     * as expanded-row count) land in one place.
     */
    private fun computeFingerprint(
        rowCount: Int,
        maxRowWidth: Int,
    ): Int = (rowCount * FINGERPRINT_MIX) xor maxRowWidth

    companion object {
        private val LOG = logger<ToolWindowAutoFitter>()
        private const val DEBOUNCE_DELAY_MS = 150
        private const val DEFAULT_MAX_WIDTH = 400
        private const val MAX_RETRIES = 3
        private const val RETRY_DELAY_MS = 200

        // Odd multiplier to avoid collisions between common (rowCount, maxRowWidth)
        // pairs. Mersenne-prime-adjacent — the actual value is arbitrary; only its
        // role as a non-trivial mixer matters.
        private const val FINGERPRINT_MIX = 31
    }
}
