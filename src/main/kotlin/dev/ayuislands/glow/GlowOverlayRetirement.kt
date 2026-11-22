package dev.ayuislands.glow

import javax.swing.SwingUtilities

/** Hides a retained pane during Apply. Only acceptance can remove its native membership. */
internal class GlowOverlayRetirement(
    override val id: String,
    override val entry: GlowOverlayManager.OverlayEntry,
    private val entries: MutableMap<String, GlowOverlayManager.OverlayEntry>,
    private val isHeld: () -> Boolean,
    private val isDisposed: () -> Boolean,
    private val requestRefresh: () -> Unit,
) : GlowOverlayChange {
    private val pane = entry.glassPane
    private val visibility =
        GlowPaneCheckpoint(
            pane,
            pane.captureAppearance().copy(isVisible = false),
            setOf(GlowPaneField.VISIBILITY),
            ::isMounted,
        )

    private var settlement: Settlement? = null

    val wasVisible: Boolean get() = visibility.before.isVisible
    override val phase: GlowCheckpointPhase get() = settlement?.phase ?: visibility.record.phase
    override val state: GlowFieldState
        get() =
            settlement?.state ?: if (phase == GlowCheckpointPhase.PREPARED) {
                GlowFieldState.PREPARED
            } else {
                GlowFieldState.WRITING
            }

    override fun apply() = visibility.apply()

    override fun restore(isBlocked: Boolean) {
        checkCanSettle()
        check(phase != GlowCheckpointPhase.ACCEPTED) { "Accepted Glow retirement cannot be restored" }
        if (settlement != null) return
        val field = GlowPaneField.VISIBILITY
        visibility.restore(
            blockedBy = if (isBlocked) setOf(field) else emptySet(),
            targetRetired = !isMounted(),
        )
        val visibilityWasLost = visibility.record.fields[field] == GlowFieldState.FOREIGN
        settlement = if (visibilityWasLost) Settlement.FOREIGN else Settlement.RESTORED
    }

    override fun accept() {
        checkCanSettle()
        if (phase == GlowCheckpointPhase.ACCEPTED) return
        check(phase == GlowCheckpointPhase.APPLIED) { "Glow retirement has not completed" }
        val isOwned = !isDisposed() && entries[id] === entry && (pane.parent !== entry.layeredPane || !pane.isVisible)
        if (isOwned) {
            unmountGlowOverlay(entry)
            if (entries[id] === entry) {
                entries.remove(id)
                requestRefresh()
            }
        }
        settlement = if (isOwned) Settlement.ACCEPTED else Settlement.RELINQUISHED
    }

    private fun isMounted(): Boolean =
        isHeld() &&
            entries[id] === entry &&
            pane.parent === entry.layeredPane &&
            SwingUtilities.getRootPane(entry.host)?.layeredPane === entry.layeredPane

    private fun checkCanSettle() {
        check(SwingUtilities.isEventDispatchThread()) { "Glow retirement must run on EDT" }
        check(isDisposed() || isHeld()) { "Glow manager hold is unavailable" }
    }

    private enum class Settlement(
        val phase: GlowCheckpointPhase,
        val state: GlowFieldState,
    ) {
        RESTORED(GlowCheckpointPhase.RESTORED, GlowFieldState.RESTORED),
        FOREIGN(GlowCheckpointPhase.RESTORED, GlowFieldState.FOREIGN),
        ACCEPTED(GlowCheckpointPhase.ACCEPTED, GlowFieldState.WRITING),
        RELINQUISHED(GlowCheckpointPhase.ACCEPTED, GlowFieldState.FOREIGN),
    }
}
