package dev.ayuislands.glow

import dev.ayuislands.glow.waveform.WaveformEdge
import java.awt.Rectangle
import javax.swing.JComponent
import javax.swing.JLayeredPane
import javax.swing.SwingUtilities

/** Shared geometry writer; retained operations validate ownership around every repaint-capable write. */
internal fun updateGlowBounds(
    pane: GlowGlassPane,
    host: JComponent,
    layer: JLayeredPane,
    shouldReportFallback: Boolean,
    checkCurrent: () -> Unit = {},
) {
    checkCurrent()
    if (!host.isShowing) return
    val point = SwingUtilities.convertPoint(host, 0, 0, layer)
    val geometry =
        if (pane.isEditorOverlay) {
            EditorTabGeometry.editorOverlayGeometry(host, shouldReportFallback)
        } else {
            EditorOverlayGeometry(Rectangle(0, 0, host.width, host.height), emptyList())
        }
    val content = geometry.contentBounds
    if (pane.usesWaveformBounds) {
        checkCurrent()
        pane.waveformTopSpans = geometry.occupiedTopSpans
        checkCurrent()
        val margin = pane.waveformMargin
        val bounds =
            Rectangle(
                point.x + content.x - margin,
                point.y + content.y - margin,
                content.width + margin * 2,
                content.height + margin * 2,
            )
        pane.bounds = bounds
        checkCurrent()
        val edges = clippedWaveformEdges(bounds, layer.visibleRect)
        checkCurrent()
        pane.waveformInwardEdges = edges
    } else {
        checkCurrent()
        pane.waveformInwardEdges = emptySet()
        checkCurrent()
        pane.bounds = Rectangle(point.x + content.x, point.y + content.y, content.width, content.height)
    }
    checkCurrent()
}

private fun clippedWaveformEdges(
    overlayBounds: Rectangle,
    visibleBounds: Rectangle,
): Set<WaveformEdge> =
    buildSet {
        // Redirect before the phosphor halo leaves the Swing root so the complete trace stays visible.
        if (overlayBounds.y < visibleBounds.y) add(WaveformEdge.TOP)
        if (overlayBounds.maxX > visibleBounds.maxX) add(WaveformEdge.RIGHT)
        if (overlayBounds.maxY > visibleBounds.maxY) add(WaveformEdge.BOTTOM)
        if (overlayBounds.x < visibleBounds.x) add(WaveformEdge.LEFT)
    }
