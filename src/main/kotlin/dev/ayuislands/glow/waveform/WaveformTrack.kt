package dev.ayuislands.glow.waveform

import java.awt.Rectangle
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
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
    val signalAnchorDistance: Float,
    val signalSpan: Float,
) {
    val isClosed: Boolean = samples.isNotEmpty()

    internal fun sampleAt(distance: Float): WaveformSample {
        require(isClosed) { "Cannot sample an empty waveform track" }
        val wrappedDistance = ((distance % length) + length) % length
        val foundIndex = samples.binarySearch { sample -> sample.distance.compareTo(wrappedDistance) }
        if (foundIndex >= 0) return samples[foundIndex]

        val nextIndex = -foundIndex - 1
        check(nextIndex > 0) { "Waveform track samples must start at distance zero" }
        val previous = samples[nextIndex - 1]
        val next = if (nextIndex == samples.size) samples.first() else samples[nextIndex]
        val previousDistance = previous.distance
        val nextDistance = if (nextIndex == samples.size) next.distance + length else next.distance
        val progress = (wrappedDistance - previousDistance) / (nextDistance - previousDistance)
        val normalX = previous.normalX + (next.normalX - previous.normalX) * progress
        val normalY = previous.normalY + (next.normalY - previous.normalY) * progress
        val normalLength = hypot(normalX.toDouble(), normalY.toDouble()).toFloat().coerceAtLeast(MIN_NORMAL_LENGTH)
        return WaveformSample(
            x = previous.x + (next.x - previous.x) * progress,
            y = previous.y + (next.y - previous.y) * progress,
            normalX = normalX / normalLength,
            normalY = normalY / normalLength,
            distance = wrappedDistance,
            amplitudeMask =
                previous.amplitudeMask +
                    (next.amplitudeMask - previous.amplitudeMask) * progress,
        )
    }

    companion object {
        private const val DEFAULT_SAMPLE_STEP = 2f
        private const val HALF_DIVISOR = 2f
        private const val HALF_PI = PI / 2
        private const val THREE_HALVES_PI = PI * 1.5
        private const val MIN_NORMAL_LENGTH = 0.0001f

        fun create(
            overlayBounds: Rectangle,
            margin: Float,
            arcRadius: Float,
            motion: WaveformMotion,
            occupiedTopSpans: List<IntRange> = emptyList(),
        ): WaveformTrack {
            val left = overlayBounds.x + margin
            val top = overlayBounds.y + margin
            val right = overlayBounds.x + overlayBounds.width - margin
            val bottom = overlayBounds.y + overlayBounds.height - margin
            if (right <= left || bottom <= top) return WaveformTrack(emptyList(), 0f, 0f, 0f)

            val radius =
                min(
                    arcRadius.coerceAtLeast(0f),
                    min((right - left) / HALF_DIVISOR, (bottom - top) / HALF_DIVISOR),
                )
            val builder = TrackBuilder(DEFAULT_SAMPLE_STEP)
            builder.addLine(
                LineSpec(left + radius, top, right - radius, top, 0f, -1f),
                topLineMask(left + radius, right - radius, occupiedTopSpans, overlayBounds.x),
            )
            builder.addArc(
                ArcSpec(right - radius, top + radius, radius, -HALF_PI, 0.0),
                topArcMask(right - radius, radius, -HALF_PI, occupiedTopSpans, overlayBounds.x),
            )
            builder.addLine(LineSpec(right, top + radius, right, bottom - radius, 1f, 0f))
            builder.addArc(ArcSpec(right - radius, bottom - radius, radius, 0.0, HALF_PI))
            builder.addLine(LineSpec(right - radius, bottom, left + radius, bottom, 0f, 1f))
            builder.addArc(ArcSpec(left + radius, bottom - radius, radius, HALF_PI, PI))
            builder.addLine(LineSpec(left, bottom - radius, left, top + radius, -1f, 0f))
            builder.addArc(
                ArcSpec(left + radius, top + radius, radius, PI, THREE_HALVES_PI),
                topArcMask(left + radius, radius, PI, occupiedTopSpans, overlayBounds.x),
            )
            val track = builder.build()
            return withSignalGeometry(track, overlayBounds, margin, motion, occupiedTopSpans)
        }

        private fun topLineMask(
            startX: Float,
            endX: Float,
            occupiedSpans: List<IntRange>,
            originX: Int,
        ): (Float) -> Float =
            { progress ->
                val x = startX + (endX - startX) * progress
                signalMask(x - originX, occupiedSpans)
            }

        private fun topArcMask(
            centerX: Float,
            radius: Float,
            startAngle: Double,
            occupiedSpans: List<IntRange>,
            originX: Int,
        ): (Float) -> Float =
            { progress ->
                val angle = startAngle + HALF_PI * progress
                val x = centerX + radius * cos(angle).toFloat()
                signalMask(x - originX, occupiedSpans)
            }

        private fun signalMask(
            x: Float,
            occupiedSpans: List<IntRange>,
        ): Float =
            occupiedSpans.minOfOrNull { span ->
                when {
                    x < span.first -> smoothStep((span.first - x) / MASK_SHOULDER)
                    x > span.last -> smoothStep((x - span.last) / MASK_SHOULDER)
                    else -> 0f
                }
            } ?: 1f

        private fun withSignalGeometry(
            track: WaveformTrack,
            bounds: Rectangle,
            margin: Float,
            motion: WaveformMotion,
            occupiedSpans: List<IntRange>,
        ): WaveformTrack {
            if (!track.isClosed) return track

            val top = bounds.y + margin
            val right = bounds.x + bounds.width - margin
            val bottom = bounds.y + bounds.height - margin
            val freeSpan = largestFreeSpan(bounds, occupiedSpans)
            val useRightEdge = freeSpan != null && freeSpan.width < MIN_TOP_SPAN
            val anchorX =
                when {
                    useRightEdge -> right
                    freeSpan != null -> freeSpan.center
                    else -> bounds.x + bounds.width * FALLBACK_ANCHOR_FRACTION
                }
            val anchorY = if (useRightEdge) bounds.y + bounds.height * RIGHT_EDGE_FRACTION else top
            val anchorDistance = track.nearestDistance(anchorX, anchorY.coerceIn(top, bottom))
            val preferredSpan =
                when (motion) {
                    WaveformMotion.MONITOR -> max(DEFAULT_SIGNAL_SPAN, bounds.width * SIGNAL_SPAN_WIDTH_FRACTION)
                    WaveformMotion.STATIC_PULSE -> DEFAULT_SIGNAL_SPAN
                }
            val maximumSpan = min(preferredSpan, track.length * MAX_PERIMETER_FRACTION)
            val signalSpan =
                if (freeSpan != null && freeSpan.width >= MIN_TOP_SPAN) {
                    min(maximumSpan, freeSpan.width.toFloat())
                } else {
                    maximumSpan
                }
            return WaveformTrack(track.samples, track.length, anchorDistance, signalSpan)
        }

        private fun largestFreeSpan(
            bounds: Rectangle,
            occupiedSpans: List<IntRange>,
        ): FreeSpan? {
            if (occupiedSpans.isEmpty() || bounds.width <= 0) return null

            val occupied =
                occupiedSpans
                    .mapNotNull { span ->
                        val start = max(0, span.first)
                        val end = min(bounds.width - 1, span.last)
                        if (start <= end) start..end else null
                    }.sortedBy(IntRange::first)
            var cursor = 0
            val free = mutableListOf<FreeSpan>()
            for (span in occupied) {
                if (span.first > cursor) free += FreeSpan(cursor.toFloat(), span.first.toFloat())
                cursor = max(cursor, span.last + 1)
            }
            if (cursor < bounds.width) free += FreeSpan(cursor.toFloat(), bounds.width.toFloat())
            return free.maxByOrNull(FreeSpan::width)?.shifted(bounds.x.toFloat())
        }

        private fun WaveformTrack.nearestDistance(
            x: Float,
            y: Float,
        ): Float =
            samples
                .minBy { sample ->
                    val deltaX = sample.x - x
                    val deltaY = sample.y - y
                    deltaX * deltaX + deltaY * deltaY
                }.distance

        private data class FreeSpan(
            val start: Float,
            val end: Float,
        ) {
            val width: Int get() = (end - start).toInt()
            val center: Float get() = (start + end) / HALF_DIVISOR

            fun shifted(offset: Float): FreeSpan = FreeSpan(start + offset, end + offset)
        }

        private const val MASK_SHOULDER = 50f
        private const val DEFAULT_SIGNAL_SPAN = 220f
        private const val SIGNAL_SPAN_WIDTH_FRACTION = 0.55f
        private const val MIN_TOP_SPAN = 140
        private const val MAX_PERIMETER_FRACTION = 0.3f
        private const val FALLBACK_ANCHOR_FRACTION = 2f / 3f
        private const val RIGHT_EDGE_FRACTION = 1f / 3f
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
        if (length <= 0f) return
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
        if (length <= 0f) return
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
        if (samples.isEmpty()) return WaveformTrack(emptyList(), 0f, 0f, 0f)

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
        return WaveformTrack(measured, perimeter, 0f, 0f)
    }

    private fun distanceBetween(
        first: RawSample,
        second: RawSample,
    ): Float = hypot((second.x - first.x).toDouble(), (second.y - first.y).toDouble()).toFloat()
}
