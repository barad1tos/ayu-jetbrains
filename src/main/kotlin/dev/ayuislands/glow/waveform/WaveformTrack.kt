package dev.ayuislands.glow.waveform

import java.awt.Rectangle
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin

/** One arc-length sample on the closed island perimeter. */
data class WaveformSample(
    val x: Float,
    val y: Float,
    val normalX: Float,
    val normalY: Float,
    val distance: Float,
    val amplitudeMask: Float,
)

/** Clockwise samples of one closed rounded island perimeter. */
class WaveformTrack internal constructor(
    val samples: List<WaveformSample>,
    val length: Float,
) {
    val isClosed: Boolean = samples.isNotEmpty()

    companion object {
        private const val DEFAULT_SAMPLE_STEP = 2f
        private const val MIN_SAMPLE_STEP = 0.5f
        private const val HALF_DIVISOR = 2f
        private const val SMOOTH_HIGH = 3f
        private const val SMOOTH_LOW = 2f
        private const val HALF_PI = PI / 2
        private const val THREE_HALVES_PI = PI * 1.5

        fun create(
            overlayBounds: Rectangle,
            margin: Float,
            arcRadius: Float,
            flattenTopEdge: Boolean,
            sampleStep: Float = DEFAULT_SAMPLE_STEP,
        ): WaveformTrack {
            val left = overlayBounds.x + margin
            val top = overlayBounds.y + margin
            val right = overlayBounds.x + overlayBounds.width - margin
            val bottom = overlayBounds.y + overlayBounds.height - margin
            if (right <= left || bottom <= top) return WaveformTrack(emptyList(), 0f)

            val radius =
                min(
                    arcRadius.coerceAtLeast(0f),
                    min((right - left) / HALF_DIVISOR, (bottom - top) / HALF_DIVISOR),
                )
            val builder = TrackBuilder(sampleStep.coerceAtLeast(MIN_SAMPLE_STEP))
            builder.addLine(
                LineSpec(left + radius, top, right - radius, top, 0f, -1f),
                topMask(flattenTopEdge),
            )
            builder.addArc(
                ArcSpec(right - radius, top + radius, radius, -HALF_PI, 0.0),
                topRightMask(flattenTopEdge),
            )
            builder.addLine(LineSpec(right, top + radius, right, bottom - radius, 1f, 0f))
            builder.addArc(ArcSpec(right - radius, bottom - radius, radius, 0.0, HALF_PI))
            builder.addLine(LineSpec(right - radius, bottom, left + radius, bottom, 0f, 1f))
            builder.addArc(ArcSpec(left + radius, bottom - radius, radius, HALF_PI, PI))
            builder.addLine(LineSpec(left, bottom - radius, left, top + radius, -1f, 0f))
            builder.addArc(
                ArcSpec(left + radius, top + radius, radius, PI, THREE_HALVES_PI),
                topLeftMask(flattenTopEdge),
            )
            return builder.build()
        }

        private fun topMask(flatten: Boolean): (Float) -> Float = { if (flatten) 0f else 1f }

        private fun topRightMask(flatten: Boolean): (Float) -> Float =
            { progress ->
                if (flatten) smoothStep(progress) else 1f
            }

        private fun topLeftMask(flatten: Boolean): (Float) -> Float =
            { progress ->
                if (flatten) smoothStep(1f - progress) else 1f
            }

        private fun smoothStep(progress: Float): Float {
            val value = progress.coerceIn(0f, 1f)
            return value * value * (SMOOTH_HIGH - SMOOTH_LOW * value)
        }
    }
}

private data class LineSpec(
    val startX: Float,
    val startY: Float,
    val endX: Float,
    val endY: Float,
    val normalX: Float,
    val normalY: Float,
)

private data class ArcSpec(
    val centerX: Float,
    val centerY: Float,
    val radius: Float,
    val startAngle: Double,
    val endAngle: Double,
)

private class TrackBuilder(
    private val sampleStep: Float,
) {
    private data class RawSample(
        val x: Float,
        val y: Float,
        val normalX: Float,
        val normalY: Float,
        val amplitudeMask: Float,
    )

    private val samples = mutableListOf<RawSample>()

    fun addLine(
        spec: LineSpec,
        mask: (Float) -> Float = { 1f },
    ) {
        val length = hypot((spec.endX - spec.startX).toDouble(), (spec.endY - spec.startY).toDouble()).toFloat()
        val count = ceil(length / sampleStep).toInt().coerceAtLeast(1)
        repeat(count) { index ->
            val progress = index.toFloat() / count
            samples +=
                RawSample(
                    x = spec.startX + (spec.endX - spec.startX) * progress,
                    y = spec.startY + (spec.endY - spec.startY) * progress,
                    normalX = spec.normalX,
                    normalY = spec.normalY,
                    amplitudeMask = mask(progress).coerceIn(0f, 1f),
                )
        }
    }

    fun addArc(
        spec: ArcSpec,
        mask: (Float) -> Float = { 1f },
    ) {
        val length = ((spec.endAngle - spec.startAngle) * spec.radius).toFloat()
        val count = ceil(length / sampleStep).toInt().coerceAtLeast(1)
        repeat(count) { index ->
            val progress = index.toFloat() / count
            val angle = spec.startAngle + (spec.endAngle - spec.startAngle) * progress
            val normalX = cos(angle).toFloat()
            val normalY = sin(angle).toFloat()
            samples +=
                RawSample(
                    x = spec.centerX + spec.radius * normalX,
                    y = spec.centerY + spec.radius * normalY,
                    normalX = normalX,
                    normalY = normalY,
                    amplitudeMask = mask(progress).coerceIn(0f, 1f),
                )
        }
    }

    fun build(): WaveformTrack {
        if (samples.isEmpty()) return WaveformTrack(emptyList(), 0f)

        var distance = 0f
        val measured =
            samples.mapIndexed { index, sample ->
                if (index > 0) distance += distanceBetween(samples[index - 1], sample)
                WaveformSample(
                    x = sample.x,
                    y = sample.y,
                    normalX = sample.normalX,
                    normalY = sample.normalY,
                    distance = distance,
                    amplitudeMask = sample.amplitudeMask,
                )
            }
        val perimeter = distance + distanceBetween(samples.last(), samples.first())
        return WaveformTrack(measured, perimeter)
    }

    private fun distanceBetween(
        first: RawSample,
        second: RawSample,
    ): Float = hypot((second.x - first.x).toDouble(), (second.y - first.y).toDouble()).toFloat()
}
