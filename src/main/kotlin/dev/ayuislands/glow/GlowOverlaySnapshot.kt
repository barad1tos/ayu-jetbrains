package dev.ayuislands.glow

import javax.swing.SwingUtilities

/** Captured overlay identities support local field ownership and a stricter whole-playback census. */
internal class GlowOverlaySnapshot(
    private val entries: MutableMap<String, GlowOverlayManager.OverlayEntry>,
    private val hold: GlowUpdateGate.Hold,
    selectedIds: Set<String> = entries.keys,
) {
    private val captured = entries.toMap()

    init {
        check(SwingUtilities.isEventDispatchThread()) { "Glow playback capture must run on EDT" }
        check(hold.isActive) { "Glow overlay hold is unavailable" }
        require(captured.keys.containsAll(selectedIds)) { "Glow playback target is unavailable" }
    }

    private val census =
        captured.map { (id, entry) -> RouteOverlay(id, entry.glassPane, entry.host, entry.layeredPane) }
    val selected: List<RouteOverlay> = java.util.List.copyOf(census.filter { it.id in selectedIds })

    fun checkCurrent() {
        check(SwingUtilities.isEventDispatchThread()) { "Glow playback validation must run on EDT" }
        check(hold.isActive) { "Glow overlay hold is unavailable" }
        check(entries.keys.toList() == captured.keys.toList()) { "Glow route membership changed" }
        census.forEach(::checkTarget)
    }

    fun checkTarget(target: RouteOverlay) {
        check(SwingUtilities.isEventDispatchThread()) { "Glow playback validation must run on EDT" }
        check(isCurrent(target.id)) { "Glow playback target was replaced: ${target.id}" }
    }

    fun capturePane(
        id: String,
        planned: GlowPaneAppearance,
    ): GlowPaneCheckpoint? {
        val entry = captured.getValue(id)
        check(isCurrent(id)) { "Glow pane is unavailable" }
        val actual = entry.glassPane.captureAppearance()
        if (GlowPaneField.entries.all { GlowPaneFields.matches(it, actual, planned) }) return null
        return GlowPaneCheckpoint(entry.glassPane, planned) { isCurrent(id) }
    }

    fun captureFailure(id: String): GlowFailureCheckpoint =
        GlowFailureCheckpoint(captured.getValue(id).glassPane) { isCurrent(id) }

    /** Geometry follows current host coordinates while retaining the same owned surface. */
    fun refreshBounds(id: String) {
        val entry = captured.getValue(id)
        updateGlowBounds(entry.glassPane, entry.host, entry.layeredPane, shouldReportFallback = false) {
            check(isCurrent(id)) { "Glow pane is unavailable" }
        }
    }

    fun prepareRemoval(id: String): GlowOverlayRemoval = GlowOverlayRemoval(id, captured.getValue(id), entries, hold)

    private fun isCurrent(id: String): Boolean {
        check(SwingUtilities.isEventDispatchThread()) { "Glow overlay validation must run on EDT" }
        if (!hold.isActive) return false
        val entry = captured[id] ?: return false
        return entries[id] === entry &&
            entry.glassPane.parent === entry.layeredPane &&
            SwingUtilities.getRootPane(entry.host)?.layeredPane === entry.layeredPane
    }
}
