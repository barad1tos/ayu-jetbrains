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
import java.util.Collections
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.floor
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

internal enum class WaveformEdge {
    TOP,
    RIGHT,
    BOTTOM,
    LEFT,
}

private fun WaveformSample.horizontalOffset(
    outwardOffset: Float,
    inwardOffset: Float,
    inwardEdges: Set<WaveformEdge>,
): Float {
    val edge =
        when {
            normalX > 0f -> WaveformEdge.RIGHT
            normalX < 0f -> WaveformEdge.LEFT
            else -> return outwardOffset
        }
    return if (edge in inwardEdges) inwardOffset else outwardOffset
}

private fun WaveformSample.verticalOffset(
    outwardOffset: Float,
    inwardOffset: Float,
    inwardEdges: Set<WaveformEdge>,
): Float {
    val edge =
        when {
            normalY < 0f -> WaveformEdge.TOP
            normalY > 0f -> WaveformEdge.BOTTOM
            else -> return outwardOffset
        }
    return if (edge in inwardEdges) inwardOffset else outwardOffset
}

internal data class WaveformPaintRequest(
    val bounds: Rectangle,
    val arcWidth: Int,
    val accent: Color,
    val frame: WaveformFrame,
    val solidFrame: SolidFrameSpec,
    val displacementScale: Float = 1f,
    val occupiedTopSpans: List<IntRange> = emptyList(),
    val inwardEdges: Set<WaveformEdge> = emptySet(),
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
        val track =
            createTrack(
                request.bounds,
                request.arcWidth,
                frame.config,
                solidWidth,
                request.occupiedTopSpans,
            )
        val dirtyRegions = dirtyRegions(request.bounds, amplitude, solidWidth)
        if (!track.isClosed) return WaveformPaintResult(track, dirtyRegions)

        val intensity = frame.config.intensity.coerceIn(MIN_WAVEFORM_INTENSITY, MAX_WAVEFORM_INTENSITY)
        val strength = intensity / PERCENT_DIVISOR * frame.brightness.coerceIn(0f, 1f)
        if (strength <= 0f) return WaveformPaintResult(track, dirtyRegions)

        val displacementAmplitude = amplitude * request.displacementScale.coerceIn(0f, 1f)
        val signal = sampledSignal(track, frame)
        // Keep `CENTERED` symmetric on the border; only an outside trace may move inward to avoid clipping.
        val inwardEdges =
            if (frame.config.baseline == WaveformBaseline.OUTSIDE) request.inwardEdges else emptySet()
        val layers =
            SignalLayers(
                glow =
                    signalPaths(
                        signal = signal,
                        frame = frame,
                        amplitude = displacementAmplitude,
                        maximumDisplacement = MAX_GLOW_DISPLACEMENT,
                        inwardEdges = inwardEdges,
                    ),
                core =
                    signalPaths(
                        signal = signal,
                        frame = frame,
                        amplitude = displacementAmplitude,
                        maximumDisplacement = 1f,
                        inwardEdges = inwardEdges,
                    ),
            )
        graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        paintSignal(graphics, request.accent, strokeWidths(solidWidth), layers, strength)
        return WaveformPaintResult(track, dirtyRegions)
    }

    private fun paintSignal(
        graphics: Graphics2D,
        accent: Color,
        strokes: SignalStrokes,
        layers: SignalLayers,
        strength: Float,
    ) {
        val glowPaths = layers.glow
        val corePaths = layers.core
        glowPaths.bands.forEachIndexed { band, path ->
            val bandAlpha = (band + 1f) / ALPHA_BANDS
            if (band >= OUTER_MIN_BAND) {
                paintPass(
                    graphics,
                    path,
                    accent,
                    signalStroke(strokes.outer, isCore = false),
                    OUTER_ALPHA * strength * bandAlpha,
                )
            }
            if (band >= MID_MIN_BAND) {
                paintPass(
                    graphics,
                    path,
                    accent,
                    signalStroke(strokes.bloom, isCore = false),
                    MID_ALPHA * strength * bandAlpha,
                )
            }
            paintPass(
                graphics,
                path,
                accent,
                signalStroke(strokes.inner, isCore = false),
                INNER_ALPHA * strength * bandAlpha,
            )
        }
        corePaths.bands.forEachIndexed { band, path ->
            val bandAlpha = (band + 1f) / ALPHA_BANDS
            paintPass(
                graphics,
                path,
                accent,
                signalStroke(strokes.core, isCore = true),
                CORE_ALPHA * strength * bandAlpha,
            )
        }
        corePaths.heads.forEach { head ->
            paintPass(
                graphics,
                head.path,
                towardWhite(accent, HEAD_CORE_HEAT),
                signalStroke(strokes.core, isCore = true),
                CORE_ALPHA * strength,
            )
            paintPass(
                graphics,
                head.path,
                towardWhite(accent, HEAD_INNER_HEAT),
                signalStroke(strokes.inner, isCore = false),
                INNER_ALPHA * strength,
            )
        }
    }

    fun trackLength(
        bounds: Rectangle,
        arcWidth: Int,
        config: WaveformConfig,
        solidWidth: Int,
        occupiedTopSpans: List<IntRange> = emptyList(),
    ): Float = createTrack(bounds, arcWidth, config, solidWidth, occupiedTopSpans).length

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
        config: WaveformConfig,
        solidWidth: Int,
        occupiedTopSpans: List<IntRange>,
    ): WaveformTrack {
        val amplitude = config.amplitude.coerceIn(MIN_WAVEFORM_AMPLITUDE, MAX_WAVEFORM_AMPLITUDE)
        val key =
            TrackKey(
                Rectangle(bounds),
                arcWidth,
                amplitude,
                solidWidth,
                config.motion,
                config.direction,
                config.baseline,
                config.effectiveTraceLength,
                occupiedTopSpans,
            )
        if (key == trackKey) return requireNotNull(cachedTrack)

        val outerMargin = marginFor(amplitude, solidWidth)
        val baselineInset = baselineInsetFor(config.baseline, solidWidth)
        return WaveformTrack
            .create(
                overlayBounds = bounds,
                margin = outerMargin + baselineInset,
                arcRadius =
                    (arcWidth.coerceAtLeast(0) / ARC_DIAMETER_DIVISOR - baselineInset)
                        .coerceAtLeast(0f),
                config = config,
                occupiedTopSpans =
                    occupiedTopSpans.map { span ->
                        (span.first + outerMargin.toInt())..(span.last + outerMargin.toInt())
                    },
            ).also { track ->
                trackKey = key
                cachedTrack = track
            }
    }

    private fun signalPaths(
        signal: SampledSignal,
        frame: WaveformFrame,
        amplitude: Float,
        maximumDisplacement: Float,
        inwardEdges: Set<WaveformEdge>,
    ): SignalPathSet {
        val bands = List(ALPHA_BANDS) { Path2D.Float() }
        val heads = mutableListOf<HeadPath>()
        val energy = frame.energy.coerceIn(0f, 1f)
        val amplitudeScale =
            when (frame.config.motion) {
                WaveformMotion.MONITOR -> 1f
                WaveformMotion.STATIC_PULSE -> REST_AMPLITUDE_SCALE + energy * ACTIVE_AMPLITUDE_RANGE
            }
        val scaledAmplitude = amplitude * amplitudeScale
        appendSignal(signal, scaledAmplitude, maximumDisplacement, inwardEdges, bands)?.let { heads += it }
        return SignalPathSet(bands, heads)
    }

    private fun appendSignal(
        signal: SampledSignal,
        amplitude: Float,
        maximumDisplacement: Float,
        inwardEdges: Set<WaveformEdge>,
        bands: List<Path2D.Float>,
    ): HeadPath? {
        val lastIndex = IntArray(ALPHA_BANDS) { NO_INDEX }
        val headPath = Path2D.Float()
        var headLastIndex = NO_INDEX
        var previousPoint: SignalPoint? = null
        val track = signal.track
        val spec = signal.spec

        signal.samples.forEachIndexed { index, sample ->
            val delta = circularDelta(sample.distance, spec.center, track.length)
            val phase = R_PEAK_PHASE + delta * spec.travelSign / track.signalSpan
            val alpha = signalAlpha(spec, phase) * sample.amplitudeMask
            if (alpha <= 0f) {
                previousPoint = null
                return@forEachIndexed
            }

            val morphology = signalValue(spec, phase)
            val outwardOffset = signalOffset(morphology, spec.baseline)
            val inwardOffset = -signalOffset(morphology, WaveformBaseline.OUTSIDE)
            val horizontalDisplacement =
                sample
                    .horizontalOffset(outwardOffset, inwardOffset, inwardEdges)
                    .coerceIn(-maximumDisplacement, maximumDisplacement) *
                    amplitude * sample.amplitudeMask
            val verticalDisplacement =
                sample
                    .verticalOffset(outwardOffset, inwardOffset, inwardEdges)
                    .coerceIn(-maximumDisplacement, maximumDisplacement) *
                    amplitude * sample.amplitudeMask
            val x = sample.x + sample.normalX * horizontalDisplacement
            val y = sample.y + sample.normalY * verticalDisplacement
            val band = min((alpha * ALPHA_BANDS).toInt(), ALPHA_BANDS - 1)
            if (lastIndex[band] == index - 1) {
                bands[band].lineTo(x, y)
            } else {
                previousPoint?.let { previous ->
                    bands[band].moveTo(previous.x, previous.y)
                    bands[band].lineTo(x, y)
                } ?: bands[band].moveTo(x, y)
            }
            lastIndex[band] = index
            previousPoint = SignalPoint(x, y)

            if (spec.moving && phase >= HEAD_PHASE - HEAD_HOT_SPAN && phase <= HEAD_PHASE + HEAD_LEAD) {
                if (headLastIndex == index - 1) headPath.lineTo(x, y) else headPath.moveTo(x, y)
                headLastIndex = index
            }
        }
        return if (headLastIndex != NO_INDEX) HeadPath(headPath) else null
    }

    private fun sampledSignal(
        track: WaveformTrack,
        frame: WaveformFrame,
    ): SampledSignal {
        val spec = signalSpec(track, frame)
        return SampledSignal(track, buildSignalSamples(track, spec), spec)
    }

    internal fun signalSamples(
        track: WaveformTrack,
        frame: WaveformFrame,
    ): List<WaveformSample> = sampledSignal(track, frame).samples

    private fun buildSignalSamples(
        track: WaveformTrack,
        spec: SignalSpec,
    ): List<WaveformSample> {
        val vertices =
            if (spec.moving) {
                traceVertexDistances(track, spec).map(track::sampleAt)
            } else {
                emptyList()
            }
        if (vertices.isEmpty()) return rotateAtSeam(track, spec, track.samples)

        val samples = mergeSamples(track.samples, vertices)
        seamStart(track, spec, samples)?.let { startIndex ->
            Collections.rotate(samples, -startIndex)
        }
        return samples
    }

    private fun mergeSamples(
        trackSamples: List<WaveformSample>,
        vertices: List<WaveformSample>,
    ): MutableList<WaveformSample> {
        val merged = ArrayList<WaveformSample>(trackSamples.size + vertices.size)
        var vertexIndex = 0
        trackSamples.forEach { sample ->
            while (
                vertexIndex < vertices.size &&
                vertices[vertexIndex].distance < sample.distance - SAMPLE_DISTANCE_EPSILON
            ) {
                merged += vertices[vertexIndex++]
            }
            while (
                vertexIndex < vertices.size &&
                vertices[vertexIndex].distance <= sample.distance + SAMPLE_DISTANCE_EPSILON
            ) {
                vertexIndex++
            }
            merged += sample
        }
        while (vertexIndex < vertices.size) merged += vertices[vertexIndex++]
        return merged
    }

    private fun rotateAtSeam(
        track: WaveformTrack,
        spec: SignalSpec,
        samples: List<WaveformSample>,
    ): List<WaveformSample> {
        val startIndex = seamStart(track, spec, samples) ?: return samples
        return List(samples.size) { offset -> samples[(startIndex + offset) % samples.size] }
    }

    private fun seamStart(
        track: WaveformTrack,
        spec: SignalSpec,
        samples: List<WaveformSample>,
    ): Int? {
        val firstPhase = samplePhase(track, spec, samples.first())
        val lastPhase = samplePhase(track, spec, samples.last())
        if (signalAlpha(spec, firstPhase) <= 0f || signalAlpha(spec, lastPhase) <= 0f) return null

        val cutDistance = wrap(spec.center + track.length / HALF_DIVISOR, track.length)
        val foundIndex = samples.binarySearch { sample -> sample.distance.compareTo(cutDistance) }
        val insertionIndex = if (foundIndex >= 0) foundIndex else -foundIndex - 1
        return if (insertionIndex == samples.size || insertionIndex == 0) null else insertionIndex
    }

    private fun samplePhase(
        track: WaveformTrack,
        spec: SignalSpec,
        sample: WaveformSample,
    ): Float {
        val delta = circularDelta(sample.distance, spec.center, track.length)
        return R_PEAK_PHASE + delta * spec.travelSign / track.signalSpan
    }

    private fun traceVertexDistances(
        track: WaveformTrack,
        spec: SignalSpec,
    ): List<Float> {
        val firstCycle = floor(spec.tracePhase - HEAD_PHASE * spec.complexCount).toInt()
        val lastCycle = floor(spec.tracePhase + HEAD_LEAD * spec.complexCount).toInt()
        return (firstCycle..lastCycle)
            .flatMap { cycle ->
                val historyIndex = -cycle
                if (historyIndex < 0) return@flatMap emptyList()
                val morphology = spec.history.getOrElse(historyIndex) { spec.history.last() }
                morphology.vertexPhases().mapNotNull { vertexPhase ->
                    val windowPhase =
                        HEAD_PHASE +
                            (cycle + vertexPhase - spec.tracePhase) /
                            spec.complexCount
                    if (signalAlpha(spec, windowPhase) <= 0f) return@mapNotNull null
                    val delta =
                        (windowPhase - R_PEAK_PHASE) * track.signalSpan /
                            spec.travelSign
                    wrap(spec.center + delta, track.length)
                }
            }.distinct()
            .sorted()
    }

    private fun signalSpec(
        track: WaveformTrack,
        frame: WaveformFrame,
    ): SignalSpec {
        val trace = frame.trace.takeIf { frame.config.motion == WaveformMotion.MONITOR }
        if (trace == null) {
            return SignalSpec(
                center = track.signalAnchorDistance,
                history = listOf(frame.morphology),
                tracePhase = 0f,
                travelSign = 1f,
                moving = false,
                complexCount = 1,
                baseline = frame.config.baseline,
            )
        }
        return SignalSpec(
            center = wrap(track.signalAnchorDistance + trace.anchorOffset, track.length),
            history = trace.history,
            tracePhase = trace.phase,
            travelSign = frame.config.direction.travelSign,
            moving = true,
            complexCount = frame.config.traceComplexCount,
            baseline = frame.config.baseline,
        )
    }

    private fun signalValue(
        spec: SignalSpec,
        windowPhase: Float,
    ): Float {
        if (!spec.moving) return spec.history.first().valueAt(windowPhase)

        val sourcePhase = spec.tracePhase - (HEAD_PHASE - windowPhase) * spec.complexCount
        val cycle = floor(sourcePhase).toInt()
        val historyIndex = -cycle
        if (historyIndex < 0) return 0f
        val morphology = spec.history.getOrElse(historyIndex) { spec.history.last() }
        return morphology.valueAt(sourcePhase - cycle)
    }

    private fun signalAlpha(
        spec: SignalSpec,
        phase: Float,
    ): Float = if (spec.moving) cometAlpha(phase) else windowAlpha(phase)

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
        stroke: BasicStroke,
        alpha: Float,
    ) {
        val colorAlpha = (MAX_COLOR_ALPHA * alpha).roundToInt().coerceIn(0, MAX_COLOR_ALPHA)
        if (colorAlpha == 0) return
        graphics.stroke = stroke
        graphics.color = ColorUtil.toAlpha(accent, colorAlpha)
        graphics.draw(path)
    }

    private fun signalStroke(
        width: Float,
        isCore: Boolean,
    ): BasicStroke =
        if (isCore) {
            BasicStroke(width, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, CORE_MITER_LIMIT)
        } else {
            BasicStroke(width, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
        }

    private data class TrackKey(
        val bounds: Rectangle,
        val arcWidth: Int,
        val amplitude: Int,
        val solidWidth: Int,
        val motion: WaveformMotion,
        val direction: WaveformDirection,
        val baseline: WaveformBaseline,
        val traceLength: Int,
        val occupiedTopSpans: List<IntRange>,
    )

    private data class SignalSpec(
        val center: Float,
        val history: List<BeatMorphology>,
        val tracePhase: Float,
        val travelSign: Float,
        val moving: Boolean,
        val complexCount: Int,
        val baseline: WaveformBaseline,
    )

    private data class SampledSignal(
        val track: WaveformTrack,
        val samples: List<WaveformSample>,
        val spec: SignalSpec,
    )

    private data class SignalPoint(
        val x: Float,
        val y: Float,
    )

    private class SignalPathSet(
        val bands: List<Path2D.Float>,
        val heads: List<HeadPath>,
    )

    private class SignalLayers(
        val glow: SignalPathSet,
        val core: SignalPathSet,
    )

    private class HeadPath(
        val path: Path2D.Float,
    )

    internal data class SignalStrokes(
        val core: Float,
        val inner: Float,
        val bloom: Float,
        val outer: Float,
    )

    internal companion object {
        private const val OUTER_PADDING = 2f
        private const val PERCENT_DIVISOR = 100f
        private const val ARC_DIAMETER_DIVISOR = 2f
        private const val HALF_DIVISOR = 2f
        internal const val R_PEAK_PHASE = TRACE_ANCHOR_PHASE
        private const val BASE_FRAME_STRENGTH = 0.2f
        private const val REST_AMPLITUDE_SCALE = 0.4f
        private const val ACTIVE_AMPLITUDE_RANGE = 1f - REST_AMPLITUDE_SCALE
        private const val WINDOW_SHOULDER = 0.1f
        private const val MID_ALPHA = 0.18f
        private const val OUTER_ALPHA = 0.08f
        private const val INNER_ALPHA = 0.42f
        private const val CORE_ALPHA = 0.95f
        private const val ALPHA_BANDS = 6
        private const val MID_MIN_BAND = 2
        private const val OUTER_MIN_BAND = 4
        private const val MAX_COLOR_ALPHA = 255
        private const val NO_INDEX = -2
        private const val CORE_WIDTH_FACTOR = 0.5f
        private const val BLOOM_WIDTH_FACTOR = 2f
        private const val OUTER_WIDTH_FACTOR = 3.5f
        private const val SIGNAL_BASELINE_OFFSET = 0.22f
        private const val CORE_MITER_LIMIT = 3f
        private const val MAX_GLOW_DISPLACEMENT = 0.72f
        private const val SAMPLE_DISTANCE_EPSILON = 0.001f
        internal const val HEAD_PHASE = 0.72f
        internal const val HEAD_LEAD = TRACE_PHASE_SPAN - HEAD_PHASE
        private const val HEAD_HOT_SPAN = 0.08f
        private const val SLOW_DECAY_TAU = 1.2f
        private const val PHOSPHOR_KNEE = 0.45f
        private const val FAST_DECAY_TAU = 0.10f
        private val KNEE_LEVEL = exp(-PHOSPHOR_KNEE / SLOW_DECAY_TAU)
        private const val HEAD_CORE_HEAT = 0.8f
        private const val HEAD_INNER_HEAT = 0.45f
        private const val WHITE_CHANNEL = 255

        fun signalOffset(
            morphology: Float,
            baseline: WaveformBaseline = WaveformBaseline.OUTSIDE,
        ): Float =
            when (baseline) {
                WaveformBaseline.OUTSIDE -> {
                    val displacement =
                        if (morphology >= 0f) {
                            SIGNAL_BASELINE_OFFSET + morphology * (1f - SIGNAL_BASELINE_OFFSET)
                        } else {
                            SIGNAL_BASELINE_OFFSET + morphology
                        }
                    displacement.coerceIn(0f, 1f)
                }

                WaveformBaseline.CENTERED -> morphology.coerceIn(-1f, 1f)
            }

        /**
         * Asymmetric phosphor-decay window for the moving trace: a hard cut just
         * ahead of the head, a shallow decay through the T and R waves behind
         * it, then a fast decay that lets the old trail die out like a CRT
         * comet tail.
         */
        fun cometAlpha(phase: Float): Float {
            if (phase < 0f || phase > TRACE_PHASE_SPAN) return 0f
            if (phase > HEAD_PHASE) return smoothStep((TRACE_PHASE_SPAN - phase) / HEAD_LEAD)
            val behind = HEAD_PHASE - phase
            val decay =
                if (behind <= PHOSPHOR_KNEE) {
                    exp(-behind / SLOW_DECAY_TAU)
                } else {
                    KNEE_LEVEL * exp(-(behind - PHOSPHOR_KNEE) / FAST_DECAY_TAU)
                }
            return decay * smoothStep(phase / WINDOW_SHOULDER)
        }

        /** Per-channel lerp toward white; heat 0 keeps the color, heat 1 is pure white. */
        fun towardWhite(
            color: Color,
            heat: Float,
        ): Color {
            val amount = heat.coerceIn(0f, 1f)
            return Color(
                color.red + ((WHITE_CHANNEL - color.red) * amount).roundToInt(),
                color.green + ((WHITE_CHANNEL - color.green) * amount).roundToInt(),
                color.blue + ((WHITE_CHANNEL - color.blue) * amount).roundToInt(),
            )
        }

        fun strokeWidths(solidWidth: Int): SignalStrokes {
            val width = solidWidth.coerceAtLeast(1).toFloat()
            return SignalStrokes(
                core = width * CORE_WIDTH_FACTOR,
                inner = width,
                bloom = width * BLOOM_WIDTH_FACTOR,
                outer = width * OUTER_WIDTH_FACTOR,
            )
        }

        fun bloomRadiusFor(solidWidth: Int): Float = strokeWidths(solidWidth).outer / 2f

        fun baselineInsetFor(
            baseline: WaveformBaseline,
            solidWidth: Int,
        ): Float =
            when (baseline) {
                WaveformBaseline.OUTSIDE -> 0f
                WaveformBaseline.CENTERED -> solidWidth.coerceAtLeast(1) / HALF_DIVISOR
            }

        fun marginFor(
            amplitude: Int,
            solidWidth: Int,
        ): Float =
            amplitude.coerceIn(MIN_WAVEFORM_AMPLITUDE, MAX_WAVEFORM_AMPLITUDE) +
                bloomRadiusFor(solidWidth) +
                OUTER_PADDING
    }
}
