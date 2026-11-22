package dev.ayuislands.glow

import javax.swing.JComponent
import javax.swing.JLayeredPane
import javax.swing.SwingUtilities

/** A resolved host and accepted appearance for one new native pane. */
internal data class GlowOverlayAddition(
    val id: String,
    val host: JComponent,
    val appearance: GlowPaneAppearance,
    val isEditor: Boolean = false,
)

/** One native membership change retained until Apply has a durable outcome. */
internal sealed interface GlowOverlayChange {
    val id: String
    val entry: GlowOverlayManager.OverlayEntry
    val phase: GlowCheckpointPhase
    val state: GlowFieldState

    fun apply()

    fun restore(isBlocked: Boolean)

    fun accept()
}

/** Keeps exact native membership until the journal commits or restores the addition. */
internal class GlowOverlayAttachment(
    override val id: String,
    override val entry: GlowOverlayManager.OverlayEntry,
    private val entries: MutableMap<String, GlowOverlayManager.OverlayEntry>,
    private val isHeld: () -> Boolean,
    private val layout: () -> Unit,
    private val requestRefresh: () -> Unit,
) : GlowOverlayChange {
    override var phase = GlowCheckpointPhase.PREPARED
        private set

    override var state = GlowFieldState.PREPARED
        private set

    override fun apply() {
        checkEdt()
        check(phase == GlowCheckpointPhase.PREPARED) { "Glow attachment has already started" }
        checkHost()
        check(entries[id] == null && entry.glassPane.parent == null) { "Glow target is already occupied" }
        phase = GlowCheckpointPhase.APPLYING
        state = GlowFieldState.WRITING
        entries[id] = entry
        mountGlowOverlay(entry, layout, ::verifyOwned)
        checkListeners()
        phase = GlowCheckpointPhase.APPLIED
    }

    override fun restore(isBlocked: Boolean) {
        checkEdt()
        check(phase != GlowCheckpointPhase.ACCEPTED) { "Accepted Glow attachment cannot be restored" }
        if (phase == GlowCheckpointPhase.RESTORED) return
        if (state == GlowFieldState.WRITING) {
            check(!isBlocked) { "A newer receipt owns this Glow attachment" }
            phase = GlowCheckpointPhase.RESTORING
            unmountGlowOverlay(entry)
            if (entries[id] === entry) entries.remove(id)
        }
        state =
            if (state == GlowFieldState.WRITING && (entries[id] != null || entry.glassPane.parent != null)) {
                GlowFieldState.FOREIGN
            } else {
                GlowFieldState.RESTORED
            }
        phase = GlowCheckpointPhase.RESTORED
    }

    override fun accept() {
        checkEdt()
        if (phase == GlowCheckpointPhase.ACCEPTED) return
        check(phase == GlowCheckpointPhase.APPLIED) { "Glow attachment has not completed" }
        verifyOwned(mounted = true)
        checkListeners()
        requestRefresh()
        phase = GlowCheckpointPhase.ACCEPTED
    }

    private fun verifyOwned(mounted: Boolean) {
        checkHost()
        check(entries[id] === entry) { "Glow attachment was replaced" }
        val expectedParent = entry.layeredPane.takeIf { mounted }
        check(entry.glassPane.parent === expectedParent) { "Glow pane moved to another parent" }
    }

    private fun checkListeners() {
        val host = entry.host
        check(host.componentListeners.any { it === entry.componentListener }) {
            "Glow component listener was not mounted"
        }
        check(host.hierarchyBoundsListeners.any { it === entry.hierarchyBoundsListener }) {
            "Glow bounds listener was not mounted"
        }
    }

    private fun checkHost() {
        check(isHeld()) { "Glow manager hold is unavailable" }
        val currentLayer = SwingUtilities.getRootPane(entry.host)?.layeredPane
        check(currentLayer === entry.layeredPane) { "Glow host moved to another root" }
    }

    private fun checkEdt() {
        check(SwingUtilities.isEventDispatchThread()) { "Glow attachment must run on EDT" }
    }
}

internal fun unmountGlowOverlay(entry: GlowOverlayManager.OverlayEntry) {
    val (pane, host, layer) = entry
    val componentListener = entry.componentListener
    val boundsListener = entry.hierarchyBoundsListener
    host.removeComponentListener(componentListener)
    host.removeHierarchyBoundsListener(boundsListener)
    val bounds = pane.bounds
    if (pane.parent === layer) {
        layer.remove(pane)
        layer.repaint(bounds)
    }
    check(pane.parent !== layer) { "Glow pane remains mounted" }
    check(host.componentListeners.none { it === componentListener }) { "Glow component listener remains mounted" }
    check(host.hierarchyBoundsListeners.none { it === boundsListener }) { "Glow bounds listener remains mounted" }
    if (pane.parent == null) pane.stopAnimation()
}

internal fun mountGlowOverlay(
    entry: GlowOverlayManager.OverlayEntry,
    layout: () -> Unit,
    verify: (mounted: Boolean) -> Unit = {},
) {
    val (pane, host, layer) = entry
    layer.setLayer(pane, JLayeredPane.PALETTE_LAYER)
    verify(false)
    layer.add(pane)
    verify(true)
    layout()
    verify(true)
    entry.componentListener?.let(host::addComponentListener)
    verify(true)
    entry.hierarchyBoundsListener?.let(host::addHierarchyBoundsListener)
    verify(true)
}
