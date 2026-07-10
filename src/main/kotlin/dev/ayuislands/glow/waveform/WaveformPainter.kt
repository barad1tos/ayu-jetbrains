package dev.ayuislands.glow.waveform

import java.awt.BasicStroke
import java.awt.Color
import java.awt.Graphics2D
import java.awt.Rectangle
import java.awt.RenderingHints
import java.awt.geom.Path2D
import kotlin.math.ceil
import kotlin.math.roundToInt
import kotlin.random.Random

internal data class WaveformPaintResult(
    val track: WaveformTrack,
    val dirtyRegions: List<Rectangle>,
)

internal data class WaveformPaintRequest(
    val bounds: Rectangle,
    val arcWidth: Int,
    val accent: Color,
    val frame: WaveformFrame,
    val isEditorOverlay: Boolean,
)

internal open class WaveformPainter {
    private val staticMorphology = BeatMorphology.random(Random(STATIC_MORPHOLOGY_SEED))
    private var trackKey: TrackKey? = null
    private var cachedTrack: WaveformTrack? = null

    open fun paint(
        graphics: Graphics2D,
        request: WaveformPaintRequest,
    ): WaveformPaintResult {
        val frame = request.frame
        val amplitude = frame.config.amplitude.coerceIn(MIN_WAVEFORM_AMPLITUDE, MAX_WAVEFORM_AMPLITUDE)
        val track = createTrack(request.bounds, request.arcWidth, amplitude, request.isEditorOverlay)
        val dirtyRegions = dirtyRegions(request.bounds, amplitude)
        if (!track.isClosed) return WaveformPaintResult(track, dirtyRegions)

        val path = buildPath(track, frame, amplitude.toFloat())
        val intensity = frame.config.intensity.coerceIn(MIN_WAVEFORM_INTENSITY, MAX_WAVEFORM_INTENSITY)
        val strength = intensity / PERCENT_DIVISOR * frame.brightness.coerceIn(0f, 1f)
        if (strength <= 0f) return WaveformPaintResult(track, dirtyRegions)

        graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        paintPass(graphics, path, request.accent, BLOOM_WIDTH, BLOOM_ALPHA * strength)
        paintPass(graphics, path, request.accent, MID_WIDTH, MID_ALPHA * strength)
        paintPass(graphics, path, request.accent, CORE_WIDTH, CORE_ALPHA * strength)
        return WaveformPaintResult(track, dirtyRegions)
    }

    fun trackLength(
        bounds: Rectangle,
        arcWidth: Int,
        config: WaveformConfig,
        isEditorOverlay: Boolean,
    ): Float {
        val amplitude = config.amplitude.coerceIn(MIN_WAVEFORM_AMPLITUDE, MAX_WAVEFORM_AMPLITUDE)
        return createTrack(bounds, arcWidth, amplitude, isEditorOverlay).length
    }

    fun dirtyRegions(
        bounds: Rectangle,
        amplitude: Int,
    ): List<Rectangle> {
        if (bounds.width <= 0 || bounds.height <= 0) return emptyList()
        val effectiveAmplitude = amplitude.coerceIn(MIN_WAVEFORM_AMPLITUDE, MAX_WAVEFORM_AMPLITUDE)
        val margin = marginFor(effectiveAmplitude)
        val band = ceil(margin + effectiveAmplitude + BLOOM_RADIUS).toInt()
        val horizontalBand = band.coerceAtMost(bounds.height)
        val verticalBand = band.coerceAtMost(bounds.width)
        return listOf(
            Rectangle(bounds.x, bounds.y, bounds.width, horizontalBand),
            Rectangle(bounds.x, bounds.y + bounds.height - horizontalBand, bounds.width, horizontalBand),
            Rectangle(bounds.x, bounds.y, verticalBand, bounds.height),
            Rectangle(bounds.x + bounds.width - verticalBand, bounds.y, verticalBand, bounds.height),
        )
    }

    private fun createTrack(
        bounds: Rectangle,
        arcWidth: Int,
        amplitude: Int,
        isEditorOverlay: Boolean,
    ): WaveformTrack {
        val key = TrackKey(bounds.x, bounds.y, bounds.width, bounds.height, arcWidth, amplitude, isEditorOverlay)
        if (key == trackKey) return requireNotNull(cachedTrack)

        return WaveformTrack
            .create(
                overlayBounds = bounds,
                margin = marginFor(amplitude),
                arcRadius = arcWidth.coerceAtLeast(0) / ARC_DIAMETER_DIVISOR,
                flattenTopEdge = isEditorOverlay,
            ).also { track ->
                trackKey = key
                cachedTrack = track
            }
    }

    private data class TrackKey(
        val x: Int,
        val y: Int,
        val width: Int,
        val height: Int,
        val arcWidth: Int,
        val amplitude: Int,
        val isEditorOverlay: Boolean,
    )

    private fun buildPath(
        track: WaveformTrack,
        frame: WaveformFrame,
        amplitude: Float,
    ): Path2D.Float {
        val path = Path2D.Float(Path2D.WIND_NON_ZERO, track.samples.size)
        track.samples.forEachIndexed { index, sample ->
            val displacement = sampleDisplacement(sample, track, frame) * amplitude * sample.amplitudeMask
            val x = sample.x + sample.normalX * displacement
            val y = sample.y + sample.normalY * displacement
            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.closePath()
        return path
    }

    private fun sampleDisplacement(
        sample: WaveformSample,
        track: WaveformTrack,
        frame: WaveformFrame,
    ): Float =
        when (frame.config.motion) {
            WaveformMotion.MONITOR ->
                if (frame.beats.isEmpty()) {
                    staticValue(sample.distance, track.length) * MONITOR_IDLE_STRENGTH
                } else {
                    frame.beats
                        .sumOf { beat -> monitorValue(sample.distance, track.length, beat).toDouble() }
                        .toFloat()
                        .coerceIn(MIN_DISPLACEMENT, MAX_DISPLACEMENT)
                }
            WaveformMotion.STATIC_PULSE ->
                staticValue(sample.distance, track.length) *
                    (
                        STATIC_IDLE_STRENGTH +
                            frame.staticBoost.coerceIn(0f, 1f) * (1f - STATIC_IDLE_STRENGTH)
                    )
        }

    private fun monitorValue(
        sampleDistance: Float,
        trackLength: Float,
        beat: FrameBeat,
    ): Float {
        val delta = circularDelta(sampleDistance, beat.centerDistance, trackLength)
        val phase = R_PEAK_PHASE + delta / BEAT_SPAN
        return beat.morphology.valueAt(phase) * beat.opacity.coerceIn(0f, 1f)
    }

    private fun staticValue(
        sampleDistance: Float,
        trackLength: Float,
    ): Float {
        val center = trackLength * STATIC_CENTER_FRACTION
        val delta = circularDelta(sampleDistance, center, trackLength)
        return staticMorphology.valueAt(R_PEAK_PHASE + delta / BEAT_SPAN)
    }

    private fun circularDelta(
        sampleDistance: Float,
        centerDistance: Float,
        trackLength: Float,
    ): Float {
        val half = trackLength / HALF_DIVISOR
        return ((sampleDistance - centerDistance + half) % trackLength + trackLength) % trackLength - half
    }

    private fun paintPass(
        graphics: Graphics2D,
        path: Path2D,
        accent: Color,
        width: Float,
        alpha: Float,
    ) {
        val colorAlpha = (MAX_COLOR_ALPHA * alpha).roundToInt().coerceIn(0, MAX_COLOR_ALPHA)
        if (colorAlpha == 0) return
        graphics.stroke = BasicStroke(width, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
        graphics.color = Color(accent.red, accent.green, accent.blue, colorAlpha)
        graphics.draw(path)
    }

    internal companion object {
        const val BLOOM_RADIUS = 7f

        private const val BLOOM_WIDTH = BLOOM_RADIUS * 2f
        private const val MID_WIDTH = 6f
        private const val CORE_WIDTH = 2f
        private const val BLOOM_ALPHA = 0.16f
        private const val MID_ALPHA = 0.38f
        private const val CORE_ALPHA = 0.95f
        private const val OUTER_PADDING = 2f
        private const val PERCENT_DIVISOR = 100f
        private const val ARC_DIAMETER_DIVISOR = 2f
        private const val HALF_DIVISOR = 2f
        private const val BEAT_SPAN = 76f
        private const val R_PEAK_PHASE = 0.289f
        const val STATIC_CENTER_FRACTION = 0.25f
        private const val MONITOR_IDLE_STRENGTH = 0.55f
        private const val STATIC_IDLE_STRENGTH = 0.12f
        const val STATIC_MORPHOLOGY_SEED = 2_026_0710
        private const val MIN_DISPLACEMENT = -1.25f
        private const val MAX_DISPLACEMENT = 1.25f
        private const val MAX_COLOR_ALPHA = 255

        fun marginFor(amplitude: Int): Float =
            amplitude.coerceIn(MIN_WAVEFORM_AMPLITUDE, MAX_WAVEFORM_AMPLITUDE) + BLOOM_RADIUS + OUTER_PADDING
    }
}
