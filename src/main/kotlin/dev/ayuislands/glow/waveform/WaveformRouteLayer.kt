package dev.ayuislands.glow.waveform

import dev.ayuislands.glow.GlowStyle
import java.awt.AlphaComposite
import java.awt.Color
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Rectangle
import java.awt.RenderingHints
import javax.swing.JComponent

internal data class RouteLayerStyle(
    val accent: Color,
    val glowStyle: GlowStyle,
    val intensity: Int,
    val width: Int,
    val arcWidth: Int,
    val config: WaveformConfig,
)

internal class WaveformRouteLayer(
    val rootId: RouteRootId,
    private val onFailure: (RuntimeException) -> Unit,
) : JComponent() {
    private val painter = WaveformPainter()
    private var style: RouteLayerStyle? = null
    private var plans: List<WaveformRenderPlan> = emptyList()
    private var frameAlpha = 1f
    private var failureReported = false

    init {
        isOpaque = false
    }

    override fun contains(
        x: Int,
        y: Int,
    ): Boolean = false

    fun updateStyle(style: RouteLayerStyle) {
        if (this.style == style) return
        this.style = style
        failureReported = false
        replacePlans(emptyList(), frameAlpha = 1f)
    }

    fun showFrame(
        frame: RouteFrame,
        slices: List<RouteSlice>,
    ) {
        val currentStyle = style ?: return
        try {
            val nextPlans =
                slices
                    .asSequence()
                    .filter { slice ->
                        val target = slice.target as? RoutePaintTarget.Root
                        target?.rootId == rootId
                    }.map { slice -> preparePlan(frame, slice, currentStyle) }
                    .toList()
            replacePlans(nextPlans, frame.alpha.coerceIn(0f, 1f))
        } catch (exception: RuntimeException) {
            reportFailure(exception)
        }
    }

    fun clearFrame() {
        replacePlans(emptyList(), frameAlpha = 1f)
    }

    override fun paintComponent(graphics: Graphics) {
        if (frameAlpha <= 0f || plans.isEmpty()) return

        val routeGraphics = graphics.create() as Graphics2D
        try {
            routeGraphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            routeGraphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
            routeGraphics.composite = AlphaComposite.SrcOver.derive(frameAlpha)
            plans.forEach { plan -> painter.paint(routeGraphics, plan) }
        } catch (exception: RuntimeException) {
            reportFailure(exception)
        } finally {
            routeGraphics.dispose()
        }
    }

    private fun preparePlan(
        frame: RouteFrame,
        slice: RouteSlice,
        style: RouteLayerStyle,
    ): WaveformRenderPlan {
        val track = openWaveformTrack(slice.samples)
        val paintBounds = Rectangle(0, 0, width, height)
        return painter.prepare(
            WaveformPaintRequest(
                bounds = paintBounds,
                arcWidth = style.arcWidth,
                accent = style.accent,
                frame = frame.signal.copy(config = style.config),
                solidFrame =
                    SolidFrameSpec(
                        bounds = paintBounds,
                        style = style.glowStyle,
                        intensity = style.intensity,
                        width = style.width,
                    ),
                inwardEdges = slice.inwardEdges,
                routedTrack =
                    RoutedTrack(
                        track = track,
                        distanceOffset = slice.distanceOffset,
                        centerDistance = frame.centerDistance,
                        signalSpan = frame.signalSpan,
                    ),
                paintsBase = false,
            ),
        )
    }

    private fun replacePlans(
        nextPlans: List<WaveformRenderPlan>,
        frameAlpha: Float,
    ) {
        val oldBounds = planBounds(plans)
        val newBounds = planBounds(nextPlans)
        val dirtyBounds = oldBounds?.let { old -> newBounds?.let(old::union) ?: old } ?: newBounds
        plans = nextPlans
        this.frameAlpha = frameAlpha
        dirtyBounds?.let { dirty ->
            repaint(dirty.x, dirty.y, dirty.width, dirty.height)
        }
    }

    private fun reportFailure(exception: RuntimeException) {
        val oldBounds = planBounds(plans)
        plans = emptyList()
        oldBounds?.let { dirty -> repaint(dirty.x, dirty.y, dirty.width, dirty.height) }
        if (failureReported) return
        failureReported = true
        onFailure(exception)
    }

    private fun planBounds(renderPlans: List<WaveformRenderPlan>): Rectangle? =
        renderPlans
            .mapNotNull(WaveformRenderPlan::signalBounds)
            .reduceOrNull(Rectangle::union)
}
