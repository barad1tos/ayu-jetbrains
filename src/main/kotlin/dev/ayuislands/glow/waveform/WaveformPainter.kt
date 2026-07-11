package dev.ayuislands.glow.waveform

import com.intellij.ui.ColorUtil
import dev.ayuislands.glow.GlowRenderer
import dev.ayuislands.glow.GlowStyle
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Graphics2D
import java.awt.Rectangle
import java.awt.RenderingHints
import java.awt.geom.Path2D
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

internal data class WaveformPaintResult(
    val track: WaveformTrack,
    val dirtyRegions: List<Rectangle>,
)

internal data class SolidFrameSpec(
    val bounds: Rectangle,
    val style: GlowStyle,
    val intensity: Int,
    val width: Int,
)

internal data class WaveformPaintRequest(
    val bounds: Rectangle,
    val arcWidth: Int,
    val accent: Color,
    val frame: WaveformFrame,
    val solidFrame: SolidFrameSpec,
    val displacementScale: Float = 1f,
    val occupiedTopSpans: List<IntRange> = emptyList(),
)

internal open class WaveformPainter(
    private val baseRenderer: GlowRenderer = GlowRenderer(),
) {
    private var trackKey: TrackKey? = null
    private var cachedTrack: WaveformTrack? = null

    open fun paint(
        graphics: Graphics2D,
        request: WaveformPaintRequest,
    ): WaveformPaintResult {
        paintBase(graphics, request)

        val frame = request.frame
        val amplitude = frame.config.amplitude.coerceIn(MIN_WAVEFORM_AMPLITUDE, MAX_WAVEFORM_AMPLITUDE)
        val solidWidth = request.solidFrame.width.coerceAtLeast(1)
        val track = createTrack(request.bounds, request.arcWidth, amplitude, solidWidth, request.occupiedTopSpans)
        val dirtyRegions = dirtyRegions(request.bounds, amplitude, solidWidth)
        if (!track.isClosed) return WaveformPaintResult(track, dirtyRegions)

        val intensity = frame.config.intensity.coerceIn(MIN_WAVEFORM_INTENSITY, MAX_WAVEFORM_INTENSITY)
        val strength = intensity / PERCENT_DIVISOR * frame.brightness.coerceIn(0f, 1f)
        if (strength <= 0f) return WaveformPaintResult(track, dirtyRegions)

        val paths =
            signalPaths(
                track = track,
                frame = frame,
                amplitude = amplitude * request.displacementScale.coerceIn(0f, 1f),
            )
        val strokes = strokeWidths(solidWidth)
        graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        paths.forEachIndexed { band, path ->
            val bandAlpha = (band + 1f) / ALPHA_BANDS
            paintPass(graphics, path, request.accent, strokes.bloom, BLOOM_ALPHA * strength * bandAlpha)
            paintPass(graphics, path, request.accent, strokes.inner, INNER_ALPHA * strength * bandAlpha)
            paintPass(graphics, path, request.accent, strokes.core, CORE_ALPHA * strength * bandAlpha)
        }
        return WaveformPaintResult(track, dirtyRegions)
    }

    fun trackLength(
        bounds: Rectangle,
        arcWidth: Int,
        config: WaveformConfig,
        solidWidth: Int,
        occupiedTopSpans: List<IntRange> = emptyList(),
    ): Float {
        val amplitude = config.amplitude.coerceIn(MIN_WAVEFORM_AMPLITUDE, MAX_WAVEFORM_AMPLITUDE)
        return createTrack(bounds, arcWidth, amplitude, solidWidth, occupiedTopSpans).length
    }

    fun dirtyRegions(
        bounds: Rectangle,
        amplitude: Int,
        solidWidth: Int,
    ): List<Rectangle> {
        if (bounds.width <= 0 || bounds.height <= 0) return emptyList()
        val effectiveAmplitude = amplitude.coerceIn(MIN_WAVEFORM_AMPLITUDE, MAX_WAVEFORM_AMPLITUDE)
        val margin = marginFor(effectiveAmplitude, solidWidth)
        val band = ceil(margin + effectiveAmplitude + bloomRadiusFor(solidWidth)).toInt()
        val horizontalBand = band.coerceAtMost(bounds.height)
        val verticalBand = band.coerceAtMost(bounds.width)
        return listOf(
            Rectangle(bounds.x, bounds.y, bounds.width, horizontalBand),
            Rectangle(bounds.x, bounds.y + bounds.height - horizontalBand, bounds.width, horizontalBand),
            Rectangle(bounds.x, bounds.y, verticalBand, bounds.height),
            Rectangle(bounds.x + bounds.width - verticalBand, bounds.y, verticalBand, bounds.height),
        )
    }

    private fun paintBase(
        graphics: Graphics2D,
        request: WaveformPaintRequest,
    ) {
        val solid = request.solidFrame
        val baseIntensity =
            (solid.intensity.coerceIn(MIN_WAVEFORM_INTENSITY, MAX_WAVEFORM_INTENSITY) * BASE_FRAME_STRENGTH)
                .roundToInt()
        if (baseIntensity <= 0) return

        val baseGraphics = graphics.create() as Graphics2D
        try {
            baseRenderer.ensureCache(request.accent, solid.style, baseIntensity, solid.width)
            baseRenderer.paintGlow(baseGraphics, solid.bounds, solid.width, request.arcWidth)
        } finally {
            baseGraphics.dispose()
        }
    }

    private fun createTrack(
        bounds: Rectangle,
        arcWidth: Int,
        amplitude: Int,
        solidWidth: Int,
        occupiedTopSpans: List<IntRange>,
    ): WaveformTrack {
        val key = TrackKey(Rectangle(bounds), arcWidth, amplitude, solidWidth, occupiedTopSpans)
        if (key == trackKey) return requireNotNull(cachedTrack)

        val margin = marginFor(amplitude, solidWidth)
        return WaveformTrack
            .create(
                overlayBounds = bounds,
                margin = margin,
                arcRadius = arcWidth.coerceAtLeast(0) / ARC_DIAMETER_DIVISOR,
                occupiedTopSpans =
                    occupiedTopSpans.map { span ->
                        (span.first + margin.toInt())..(span.last + margin.toInt())
                    },
            ).also { track ->
                trackKey = key
                cachedTrack = track
            }
    }

    private fun signalPaths(
        track: WaveformTrack,
        frame: WaveformFrame,
        amplitude: Float,
    ): List<Path2D.Float> {
        val paths = List(ALPHA_BANDS) { Path2D.Float() }
        val lastIndex = IntArray(ALPHA_BANDS) { NO_INDEX }
        val signal = signalFor(track, frame)
        val energy = frame.energy.coerceIn(0f, 1f)
        val amplitudeScale = REST_AMPLITUDE_SCALE + energy * ACTIVE_AMPLITUDE_RANGE
        var previousPoint: SignalPoint? = null

        track.samples.forEachIndexed { index, sample ->
            val delta = circularDelta(sample.distance, signal.center, track.length)
            val phase = R_PEAK_PHASE + delta * signal.travelSign / track.signalSpan
            val windowAlpha = windowAlpha(phase) * sample.amplitudeMask
            if (windowAlpha <= 0f) {
                previousPoint = null
                return@forEachIndexed
            }

            val displacement =
                max(0f, signal.morphology.valueAt(phase)) * amplitude * amplitudeScale * sample.amplitudeMask
            val x = sample.x + sample.normalX * displacement
            val y = sample.y + sample.normalY * displacement
            val band = min((windowAlpha * ALPHA_BANDS).toInt(), ALPHA_BANDS - 1)
            if (lastIndex[band] == index - 1) {
                paths[band].lineTo(x, y)
            } else {
                previousPoint?.let { previous ->
                    paths[band].moveTo(previous.x, previous.y)
                    paths[band].lineTo(x, y)
                } ?: paths[band].moveTo(x, y)
            }
            lastIndex[band] = index
            previousPoint = SignalPoint(x, y)
        }
        return paths
    }

    private fun signalFor(
        track: WaveformTrack,
        frame: WaveformFrame,
    ): SignalSpec {
        val movingBeat = frame.beats.firstOrNull().takeIf { frame.config.motion == WaveformMotion.MONITOR }
        return SignalSpec(
            center =
                if (movingBeat != null) {
                    wrap(track.signalAnchorDistance + movingBeat.centerDistance, track.length)
                } else {
                    track.signalAnchorDistance
                },
            morphology = movingBeat?.morphology ?: frame.morphology,
            travelSign = if (movingBeat != null) frame.config.direction.travelSign else 1f,
        )
    }

    private fun windowAlpha(phase: Float): Float =
        when {
            phase !in 0f..1f -> 0f
            phase < WINDOW_SHOULDER -> smoothStep(phase / WINDOW_SHOULDER)
            phase > 1f - WINDOW_SHOULDER -> smoothStep((1f - phase) / WINDOW_SHOULDER)
            else -> 1f
        }

    private fun circularDelta(
        sampleDistance: Float,
        centerDistance: Float,
        trackLength: Float,
    ): Float {
        val half = trackLength / HALF_DIVISOR
        return ((sampleDistance - centerDistance + half) % trackLength + trackLength) % trackLength - half
    }

    private fun wrap(
        distance: Float,
        length: Float,
    ): Float = ((distance % length) + length) % length

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
        graphics.color = ColorUtil.toAlpha(accent, colorAlpha)
        graphics.draw(path)
    }

    private data class TrackKey(
        val bounds: Rectangle,
        val arcWidth: Int,
        val amplitude: Int,
        val solidWidth: Int,
        val occupiedTopSpans: List<IntRange>,
    )

    private data class SignalSpec(
        val center: Float,
        val morphology: BeatMorphology,
        val travelSign: Float,
    )

    private data class SignalPoint(
        val x: Float,
        val y: Float,
    )

    internal data class SignalStrokes(
        val core: Float,
        val inner: Float,
        val bloom: Float,
    )

    internal companion object {
        private const val OUTER_PADDING = 2f
        private const val PERCENT_DIVISOR = 100f
        private const val ARC_DIAMETER_DIVISOR = 2f
        private const val HALF_DIVISOR = 2f
        private const val R_PEAK_PHASE = 0.289f
        private const val BASE_FRAME_STRENGTH = 0.2f
        private const val REST_AMPLITUDE_SCALE = 0.4f
        private const val ACTIVE_AMPLITUDE_RANGE = 1f - REST_AMPLITUDE_SCALE
        private const val WINDOW_SHOULDER = 0.1f
        private const val BLOOM_ALPHA = 0.16f
        private const val INNER_ALPHA = 0.38f
        private const val CORE_ALPHA = 0.95f
        private const val ALPHA_BANDS = 8
        private const val MAX_COLOR_ALPHA = 255
        private const val NO_INDEX = -2
        private const val CORE_WIDTH_FACTOR = 0.5f
        private const val BLOOM_WIDTH_FACTOR = 2f

        fun strokeWidths(solidWidth: Int): SignalStrokes {
            val width = solidWidth.coerceAtLeast(1).toFloat()
            return SignalStrokes(
                core = width * CORE_WIDTH_FACTOR,
                inner = width,
                bloom = width * BLOOM_WIDTH_FACTOR,
            )
        }

        fun bloomRadiusFor(solidWidth: Int): Float = strokeWidths(solidWidth).bloom / 2f

        fun marginFor(
            amplitude: Int,
            solidWidth: Int,
        ): Float =
            amplitude.coerceIn(MIN_WAVEFORM_AMPLITUDE, MAX_WAVEFORM_AMPLITUDE) +
                bloomRadiusFor(solidWidth) +
                OUTER_PADDING
    }
}
