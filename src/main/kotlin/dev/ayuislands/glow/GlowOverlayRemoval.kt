package dev.ayuislands.glow

import javax.swing.SwingUtilities

/** Removes a retained disabled member only after durable acceptance and playback handoff. */
internal class GlowOverlayRemoval(
    private val id: String,
    val entry: GlowOverlayManager.OverlayEntry,
    private val entries: MutableMap<String, GlowOverlayManager.OverlayEntry>,
    private val hold: GlowUpdateGate.Hold,
) {
    init {
        checkActive()
    }

    var isComplete = false
        private set

    fun cleanup() {
        check(SwingUtilities.isEventDispatchThread()) { "Glow overlay cleanup must run on EDT" }
        if (isComplete) return
        checkActive()
        entry.componentListener?.let { listener ->
            if (entry.host.componentListeners.any { it === listener }) entry.host.removeComponentListener(listener)
            checkActive()
            check(entry.host.componentListeners.none { it === listener }) { "Glow component listener remains attached" }
        }
        entry.hierarchyBoundsListener?.let { listener ->
            if (entry.host.hierarchyBoundsListeners.any { it === listener }) {
                entry.host.removeHierarchyBoundsListener(listener)
            }
            checkActive()
            check(entry.host.hierarchyBoundsListeners.none { it === listener }) {
                "Glow bounds listener remains attached"
            }
        }
        val pane = entry.glassPane
        val parent = pane.parent
        check(parent == null || parent === entry.layeredPane) { "Glow pane moved to another parent" }
        if (parent === entry.layeredPane) entry.layeredPane.remove(pane)
        checkActive()
        check(pane.parent == null) { "Glow pane remains attached" }
        if (entries[id] === entry) entries.remove(id)
        entry.layeredPane.repaint(pane.x, pane.y, pane.width, pane.height)
        checkActive()
        hold.defer(null)
        isComplete = true
    }

    private fun checkActive() {
        check(SwingUtilities.isEventDispatchThread()) { "Glow overlay cleanup must run on EDT" }
        check(hold.isActive) { "Glow overlay hold is unavailable" }
    }
}
