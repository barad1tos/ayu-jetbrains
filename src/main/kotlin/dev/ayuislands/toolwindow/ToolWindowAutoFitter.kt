package dev.ayuislands.toolwindow

import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.openapi.wm.ToolWindowType
import com.intellij.openapi.wm.ex.ToolWindowEx
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
) {
    private var expansionListener: TreeExpansionListener? = null
    private var expansionTree: JTree? = null
    private var retryTimer: Timer? = null
    private var isApplying = false

    // Defensive idempotence: fingerprint of the last successfully-measured tree shape.
    // Composed of (rowCount, maxRowWidth) — these are the only inputs that affect
    // [applyAutoFitWidth]'s desired width. When a stray re-apply (e.g. focus swap,
    // listener re-fire) arrives with unchanged content AND the resulting width would
    // be within jitter of the current width, skip the stretchWidth call.
    // Reset in [removeExpansionListener] so a re-installed listener forces a fresh measurement.
    private var lastFingerprint: Int? = null
    private val debounceTimer =
        Timer(DEBOUNCE_DELAY_MS) { applyAutoFitWidth(maxWidthProvider()) }
            .apply { isRepeats = false }

    /** Lambda to get the current max width from settings (called at fit time, not init time). */
    var maxWidthProvider: () -> Int = { DEFAULT_MAX_WIDTH }

    /** Lambda to get the current min width from settings (called at fit time, not init time). */
    var minWidthProvider: () -> Int = { minWidth }

    fun applyAutoFitWidth(maxWidth: Int) {
        assert(SwingUtilities.isEventDispatchThread()) {
            "applyAutoFitWidth must be called on EDT"
        }
        findTreeWithRetry { tree ->
            val toolWindowEx =
                resolveToolWindowEx("Auto-fit")
                    ?: return@findTreeWithRetry

            val maxRowWidth =
                AutoFitCalculator.measureTreeMaxRowWidth(tree)
            val desiredWidth =
                AutoFitCalculator.calculateDesiredWidth(
                    maxRowWidth,
                    maxWidth,
                    minWidthProvider(),
                )
            // Fingerprint guard: when tree content is unchanged AND the resulting width
            // would only differ by jitter, skip the stretchWidth call. Prevents stray
            // re-applies (focus swap, listener re-fire) from oscillating the tool window
            // because `JTree` row bounds extend slightly under focus.
            val fingerprint = computeFingerprint(tree.rowCount, maxRowWidth)
            val currentWidth = toolWindowEx.component.width
            if (fingerprint == lastFingerprint &&
                AutoFitCalculator.isJitterOnly(currentWidth, desiredWidth)
            ) {
                return@findTreeWithRetry
            }
            lastFingerprint = fingerprint
            applyWidth(toolWindowEx, desiredWidth)
        }
    }

    fun applyFixedWidth(targetWidth: Int) {
        assert(SwingUtilities.isEventDispatchThread()) {
            "applyFixedWidth must be called on EDT"
        }
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
                else -> toolWindow.stretchWidth(delta)
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
        findTreeWithRetry { tree ->
            if (expansionTree === tree && expansionListener != null) return@findTreeWithRetry

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
    }

    fun removeExpansionListener() {
        retryTimer?.stop()
        retryTimer = null
        // Reset fingerprint so a future re-install forces a fresh measurement
        // (the previous tree may have been disposed; its rowCount/maxRowWidth
        // is stale).
        lastFingerprint = null
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

    /**
     * Stops the pending debounce timer (if any) and fires the fit immediately.
     * Used by tests to avoid wall-clock waits for the 150 ms debounce window.
     */
    @TestOnly
    internal fun flushDebounceForTesting() {
        val hadPending = debounceTimer.isRunning
        debounceTimer.stop()
        if (hadPending) {
            applyAutoFitWidth(maxWidthProvider())
        }
    }

    private fun findTreeWithRetry(
        retriesLeft: Int = MAX_RETRIES,
        onFound: (JTree) -> Unit,
    ) {
        if (project.isDisposed) return
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
                    if (!project.isDisposed) {
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
