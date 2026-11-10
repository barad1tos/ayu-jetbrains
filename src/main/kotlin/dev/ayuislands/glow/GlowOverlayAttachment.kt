package dev.ayuislands.glow

import dev.ayuislands.settings.GlowSettings
import java.awt.Color
import javax.swing.JComponent
import javax.swing.JLayeredPane
import javax.swing.SwingUtilities

/** Retains one new native member until the outer operation accepts or removes it. */
internal class GlowOverlayAttachment(
    private val id: String,
    val entry: GlowOverlayManager.OverlayEntry,
    private val entries: MutableMap<String, GlowOverlayManager.OverlayEntry>,
    private val isAvailable: () -> Boolean,
    private val refreshBounds: (GlowOverlayManager.OverlayEntry) -> Unit,
    private val detach: (GlowOverlayManager.OverlayEntry) -> Unit,
    private val afterAttach: () -> Unit,
) {
    var phase: GlowCheckpointPhase = GlowCheckpointPhase.PREPARED
        private set

    val hasPending: Boolean
        get() =
            phase == GlowCheckpointPhase.APPLYING ||
                phase == GlowCheckpointPhase.APPLIED ||
                phase == GlowCheckpointPhase.RESTORING

    fun apply() {
        checkEdt()
        check(phase == GlowCheckpointPhase.PREPARED) { "Glow attachment was already started" }
        checkHost()
        check(entries[id] == null && entry.glassPane.parent == null) { "Glow target is already occupied" }
        phase = GlowCheckpointPhase.APPLYING
        entries[id] = entry
        installGlowOverlay(entry, ::checkOwned, refreshBounds)
        afterAttach()
        phase = GlowCheckpointPhase.APPLIED
    }

    /** Existing v2 entrypoints keep their immediate installation until the production cutover. */
    fun installLegacy() {
        checkEdt()
        check(phase == GlowCheckpointPhase.PREPARED) { "Glow attachment was already started" }
        installGlowOverlay(entry, ::checkHost, refreshBounds)
        entries[id] = entry
        afterAttach()
        phase = GlowCheckpointPhase.ACCEPTED
    }

    fun restore(isBlocked: Boolean = false) {
        checkEdt()
        check(phase != GlowCheckpointPhase.ACCEPTED) { "Accepted Glow attachments cannot be restored" }
        if (phase == GlowCheckpointPhase.RESTORED) return
        if (hasPending) {
            check(!isBlocked) { "A newer Glow receipt owns this attachment" }
            phase = GlowCheckpointPhase.RESTORING
            detach(entry)
            check(entry.glassPane.parent == null) { "Glow pane remains attached" }
            check(entry.host.componentListeners.none { it === entry.componentListener }) {
                "Glow component listener remains attached"
            }
            check(entry.host.hierarchyBoundsListeners.none { it === entry.hierarchyBoundsListener }) {
                "Glow bounds listener remains attached"
            }
            if (entries[id] === entry) entries.remove(id)
        }
        phase = GlowCheckpointPhase.RESTORED
    }

    fun accept() {
        checkEdt()
        check(phase == GlowCheckpointPhase.APPLIED || phase == GlowCheckpointPhase.ACCEPTED) {
            "Glow attachment has not completed"
        }
        if (phase == GlowCheckpointPhase.ACCEPTED) return
        checkOwned()
        checkGlowAttachment(entry)
        phase = GlowCheckpointPhase.ACCEPTED
    }

    private fun checkOwned() {
        checkHost()
        check(entries[id] === entry) { "Glow attachment was replaced" }
        val parent = entry.glassPane.parent
        check(parent == null || parent === entry.layeredPane) { "Glow pane moved to another parent" }
    }

    private fun checkHost() {
        check(isAvailable()) { "Glow manager is unavailable" }
        check(SwingUtilities.getRootPane(entry.host)?.layeredPane === entry.layeredPane) {
            "Glow host moved to another root"
        }
    }

    private fun checkEdt() {
        check(SwingUtilities.isEventDispatchThread()) { "Glow attachments must run on EDT" }
    }
}

internal fun installGlowOverlay(
    entry: GlowOverlayManager.OverlayEntry,
    verifyTarget: () -> Unit,
    refreshBounds: (GlowOverlayManager.OverlayEntry) -> Unit,
) {
    val (glassPane, host, layeredPane) = entry
    val componentListener = entry.componentListener
    val boundsListener = entry.hierarchyBoundsListener
    layeredPane.setLayer(glassPane, JLayeredPane.PALETTE_LAYER)
    verifyTarget()
    layeredPane.add(glassPane)
    verifyTarget()
    check(glassPane.parent === layeredPane) { "Glow pane insertion did not settle" }
    refreshBounds(entry)
    verifyTarget()
    componentListener?.let(host::addComponentListener)
    verifyTarget()
    boundsListener?.let(host::addHierarchyBoundsListener)
    verifyTarget()
    checkGlowAttachment(entry)
}

private fun checkGlowAttachment(entry: GlowOverlayManager.OverlayEntry) {
    check(entry.glassPane.parent === entry.layeredPane) { "Glow pane insertion did not settle" }
    check(entry.componentListener == null || entry.host.componentListeners.any { it === entry.componentListener }) {
        "Glow component listener insertion did not settle"
    }
    val boundsListener = entry.hierarchyBoundsListener
    check(boundsListener == null || entry.host.hierarchyBoundsListeners.any { it === boundsListener }) {
        "Glow bounds listener insertion did not settle"
    }
}

internal fun createGlowPane(
    host: JComponent,
    isEditor: Boolean,
    settings: GlowSettings,
    accent: Color,
): GlowGlassPane {
    val style = settings.style
    return GlowGlassPane(
        glowColor = accent,
        glowStyle = style,
        glowIntensity = settings.intensity.getValue(style),
        glowWidth = settings.width.getValue(style),
        isEditorOverlay = isEditor,
        glowPlacement = resolveGlowPlacement(isEditor, settings),
    ).apply {
        if (isEditor) {
            topSpansProvider = {
                EditorTabGeometry.editorOverlayGeometry(host, shouldReportFallback = false).occupiedTopSpans
            }
        }
        configureWaveform(settings.shape, resolveWaveformConfig(settings))
    }
}
