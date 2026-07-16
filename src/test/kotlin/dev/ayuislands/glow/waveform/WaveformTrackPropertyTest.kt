package dev.ayuislands.glow.waveform

import java.awt.Rectangle
import kotlin.math.hypot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class WaveformTrackPropertyTest {
    @Test
    fun `perimeter is closed with monotonic distance and outward unit normals`() {
        val track =
            WaveformTrack.create(
                overlayBounds = Rectangle(0, 0, 240, 160),
                margin = 16f,
                arcRadius = 12f,
                config = WaveformConfig(),
            )

        assertTrue(track.isClosed)
        assertTrue(track.samples.size > 100)
        assertEquals(0f, track.samples.first().distance)
        assertTrue(track.samples.zipWithNext().all { (left, right) -> right.distance > left.distance })
        assertTrue(track.length > track.samples.last().distance)
        assertTrue(
            track.samples.all { sample ->
                hypot(sample.normalX.toDouble(), sample.normalY.toDouble()) in 0.999..1.001
            },
        )

        val first = track.samples.first()
        assertEquals(0f, first.normalX, 0.001f)
        assertEquals(-1f, first.normalY, 0.001f)
        assertTrue(track.samples.any { it.normalX > 0.999f }, "right edge normal must point outward")
        assertTrue(track.samples.any { it.normalY > 0.999f }, "bottom edge normal must point outward")
        assertTrue(track.samples.any { it.normalX < -0.999f }, "left edge normal must point outward")
    }

    @Test
    fun `occupied top spans mask only the signal with smooth shoulders`() {
        val track =
            WaveformTrack.create(
                overlayBounds = Rectangle(0, 0, 600, 240),
                margin = 16f,
                arcRadius = 12f,
                config = WaveformConfig(),
                occupiedTopSpans = listOf(0..220),
            )
        val topEdge = track.samples.filter { it.normalY < -0.999f && it.normalX in -0.001f..0.001f }
        val shoulders = track.samples.filter { it.amplitudeMask > 0f && it.amplitudeMask < 1f }

        assertTrue(topEdge.isNotEmpty())
        assertTrue(topEdge.filter { it.x <= 220f }.all { it.amplitudeMask == 0f })
        assertTrue(topEdge.any { it.x >= 300f && it.amplitudeMask == 1f })
        assertTrue(shoulders.isNotEmpty())
        assertTrue(track.samples.all { it.amplitudeMask in 0f..1f })
        assertTrue(track.samples.any { it.amplitudeMask == 1f })
    }

    @Test
    fun `empty occupancy anchors the signal at two thirds of the top edge`() {
        val track =
            WaveformTrack.create(
                overlayBounds = Rectangle(0, 0, 900, 400),
                margin = 16f,
                arcRadius = 12f,
                config = WaveformConfig(),
            )

        val anchor = track.sampleNearest(track.signalAnchorDistance)

        assertEquals(600f, anchor.x, 3f)
        assertEquals(16f, anchor.y, 0.1f)
    }

    @Test
    fun `largest free top span centers a full complex`() {
        val direction = WaveformDirection.CLOCKWISE
        val track =
            WaveformTrack.create(
                overlayBounds = Rectangle(0, 0, 900, 400),
                margin = 16f,
                arcRadius = 12f,
                config = WaveformConfig(direction = direction),
                occupiedTopSpans = listOf(0..300, 700..899),
            )

        val midpoint = track.visibleTraceMidpoint(direction)

        assertEquals(500f, midpoint.x, 3f)
        assertEquals(16f, midpoint.y, 0.1f)
        assertEquals(DEFAULT_TRACE_LENGTH.toFloat(), track.signalSpan * TRACE_PHASE_SPAN, 0.1f)
    }

    @Test
    fun `moving trace stays centered inside a free top span in both directions`() {
        for (direction in WaveformDirection.entries) {
            val config = WaveformConfig(direction = direction, traceLength = 360)
            val track =
                WaveformTrack.create(
                    overlayBounds = Rectangle(0, 0, 900, 400),
                    margin = 16f,
                    arcRadius = 12f,
                    config = config,
                    occupiedTopSpans = listOf(0..300, 700..899),
                )
            val beginning =
                track.sampleAt(
                    track.signalAnchorDistance -
                        WaveformPainter.R_PEAK_PHASE * track.signalSpan / config.direction.travelSign,
                )
            val end =
                track.sampleAt(
                    track.signalAnchorDistance +
                        (TRACE_PHASE_SPAN - WaveformPainter.R_PEAK_PHASE) *
                        track.signalSpan / config.direction.travelSign,
                )
            val midpoint = track.sampleAt((beginning.distance + end.distance) / 2f)

            assertTrue(beginning.x in 301f..700f, "$direction trace start escaped the free span")
            assertTrue(end.x in 301f..700f, "$direction trace end escaped the free span")
            assertEquals(500f, midpoint.x, 3f)
        }
    }

    @Test
    fun `tab occupancy moves the perimeter trace to the right edge without changing its length`() {
        for (direction in WaveformDirection.entries) {
            val config = WaveformConfig(direction = direction, traceLength = 360)
            val roomyTrack =
                WaveformTrack.create(
                    overlayBounds = Rectangle(0, 0, 900, 400),
                    margin = 16f,
                    arcRadius = 12f,
                    config = config,
                    occupiedTopSpans = listOf(0..300, 700..899),
                )
            val crowdedTrack =
                WaveformTrack.create(
                    overlayBounds = Rectangle(0, 0, 900, 400),
                    margin = 16f,
                    arcRadius = 12f,
                    config = config,
                    occupiedTopSpans = listOf(0..400, 650..899),
                )
            assertEquals(360f, roomyTrack.signalSpan * TRACE_PHASE_SPAN, 0.1f)
            assertEquals(360f, crowdedTrack.signalSpan * TRACE_PHASE_SPAN, 0.1f)
            assertTrue(roomyTrack.sampleNearest(roomyTrack.signalAnchorDistance).normalY < -0.999f)
            assertTrue(crowdedTrack.sampleNearest(crowdedTrack.signalAnchorDistance).normalX > 0.999f)
        }
    }

    @Test
    fun `top gaps below minimum move the anchor to the upper right edge`() {
        val direction = WaveformDirection.CLOCKWISE
        val traceLength = 167
        val track =
            WaveformTrack.create(
                overlayBounds = Rectangle(0, 0, 500, 360),
                margin = 16f,
                arcRadius = 12f,
                config =
                    WaveformConfig(
                        direction = direction,
                        baseline = WaveformBaseline.OUTSIDE,
                        traceLength = traceLength,
                    ),
                occupiedTopSpans = listOf(0..170, 290..499),
            )

        val midpoint = track.visibleTraceMidpoint(direction)

        assertEquals(484f, midpoint.x, 0.1f)
        assertEquals(120f, midpoint.y, 2f)
        assertEquals(
            traceLength.toFloat().coerceAtMost(track.length * 0.3f),
            track.signalSpan * TRACE_PHASE_SPAN,
            0.1f,
        )
    }

    @Test
    fun `right edge keeps a fitting trace on its straight segment in both directions`() {
        val bounds = Rectangle(40, 20, 900, 400)
        val margin = 16f
        val radius = 12f
        val traceLength = 240

        for (direction in WaveformDirection.entries) {
            val track =
                WaveformTrack.create(
                    overlayBounds = bounds,
                    margin = margin,
                    arcRadius = radius,
                    config = WaveformConfig(direction = direction, traceLength = traceLength),
                    occupiedTopSpans = listOf(0..400, 600..899),
                )
            val (beginning, end) = track.visibleTraceEndpoints(direction)
            val midpoint = track.visibleTraceMidpoint(direction)

            assertEquals(bounds.maxX.toFloat() - margin, midpoint.x, 0.1f)
            assertEquals(bounds.y + margin + radius + traceLength / 2f, midpoint.y, 0.1f)
            assertTrue(beginning.normalX > 0.999f, "$direction trace start escaped the right edge")
            assertTrue(end.normalX > 0.999f, "$direction trace end escaped the right edge")
        }
    }

    @Test
    fun `right edge preserves the requested midpoint when the trace cannot fit in both directions`() {
        val bounds = Rectangle(40, 20, 900, 400)
        val margin = 16f
        val radius = 12f
        val traceLength = 360

        for (direction in WaveformDirection.entries) {
            val track =
                WaveformTrack.create(
                    overlayBounds = bounds,
                    margin = margin,
                    arcRadius = radius,
                    config = WaveformConfig(direction = direction, traceLength = traceLength),
                    occupiedTopSpans = listOf(0..400, 650..899),
                )
            val midpoint = track.visibleTraceMidpoint(direction)

            assertTrue(traceLength > bounds.height - 2f * (margin + radius))
            assertEquals(bounds.maxX.toFloat() - margin, midpoint.x, 0.1f)
            assertEquals(bounds.y + bounds.height / 3f, midpoint.y, 1f)
        }
    }

    @Test
    fun `complex span is capped at thirty percent of a small perimeter`() {
        val track =
            WaveformTrack.create(
                overlayBounds = Rectangle(0, 0, 180, 120),
                margin = 8f,
                arcRadius = 8f,
                config = WaveformConfig(),
            )

        assertEquals(track.length * 0.3f, track.signalSpan * TRACE_PHASE_SPAN, 0.1f)
    }

    @Test
    fun `perimeter trace keeps the configured span on large islands`() {
        val compactTrack =
            WaveformTrack.create(
                overlayBounds = Rectangle(0, 0, 256, 1_323),
                margin = 16f,
                arcRadius = 12f,
                config = WaveformConfig(traceLength = 360),
            )
        val wideTrack =
            WaveformTrack.create(
                overlayBounds = Rectangle(0, 0, 1_498, 1_323),
                margin = 16f,
                arcRadius = 12f,
                config = WaveformConfig(traceLength = 360),
            )

        assertEquals(360f, compactTrack.signalSpan * TRACE_PHASE_SPAN, 0.1f)
        assertEquals(360f, wideTrack.signalSpan * TRACE_PHASE_SPAN, 0.1f)
    }

    @Test
    fun `perimeter trace clamps raw configured lengths before rendering`() {
        val minimum =
            WaveformTrack.create(
                overlayBounds = Rectangle(0, 0, 1_498, 1_323),
                margin = 16f,
                arcRadius = 12f,
                config = WaveformConfig(traceLength = Int.MIN_VALUE),
            )
        val maximum =
            WaveformTrack.create(
                overlayBounds = Rectangle(0, 0, 1_498, 1_323),
                margin = 16f,
                arcRadius = 12f,
                config = WaveformConfig(traceLength = Int.MAX_VALUE),
            )

        assertEquals(
            MIN_TRACE_LENGTH.toFloat(),
            minimum.signalSpan * TRACE_PHASE_SPAN,
            0.1f,
        )
        assertEquals(
            MAX_TRACE_LENGTH.toFloat(),
            maximum.signalSpan * TRACE_PHASE_SPAN,
            0.1f,
        )
    }

    @Test
    fun `bounds smaller than the outward margin produce an empty track`() {
        val track =
            WaveformTrack.create(
                overlayBounds = Rectangle(0, 0, 20, 20),
                margin = 12f,
                arcRadius = 8f,
                config = WaveformConfig(),
            )

        assertTrue(track.samples.isEmpty())
        assertEquals(0f, track.length)
        assertTrue(!track.isClosed)
    }

    @Test
    fun `zero radius track keeps one strictly increasing representation of each corner`() {
        val track =
            WaveformTrack.create(
                overlayBounds = Rectangle(0, 0, 240, 160),
                margin = 16f,
                arcRadius = 0f,
                config = WaveformConfig(),
            )

        assertTrue(track.samples.zipWithNext().all { (left, right) -> left.distance < right.distance })
        assertTrue(track.samples.last().distance < track.length)
    }

    @Test
    fun `interpolated samples remain distinct and continuous across the track seam`() {
        val track =
            WaveformTrack.create(
                overlayBounds = Rectangle(0, 0, 240, 160),
                margin = 16f,
                arcRadius = 12f,
                config = WaveformConfig(),
            )
        val distances = listOf(track.length - 0.75f, track.length - 0.25f, 0.25f, 0.75f)
        val samples = distances.map(track::sampleAt)

        assertEquals(distances[0], samples[0].distance, 0.001f)
        assertEquals(distances[1], samples[1].distance, 0.001f)
        assertEquals(distances[2], samples[2].distance, 0.001f)
        assertEquals(distances[3], samples[3].distance, 0.001f)
        assertTrue(
            samples.zipWithNext().all { (left, right) ->
                hypot((right.x - left.x).toDouble(), (right.y - left.y).toDouble()) in 0.1..1.0
            },
        )
        assertTrue(
            samples.all { sample ->
                hypot(sample.normalX.toDouble(), sample.normalY.toDouble()) in 0.999..1.001
            },
        )
    }

    @Test
    fun `interpolation rejects a track that does not start at distance zero`() {
        val invalidTrack =
            WaveformTrack(
                samples =
                    listOf(
                        WaveformSample(0f, 0f, 0f, -1f, 1f, 1f),
                        WaveformSample(1f, 0f, 0f, -1f, 2f, 1f),
                    ),
                length = 3f,
                signalAnchorDistance = 0f,
                signalSpan = 1f,
            )

        assertFailsWith<IllegalStateException> { invalidTrack.sampleAt(0f) }
    }

    private fun WaveformTrack.sampleNearest(distance: Float): WaveformSample =
        samples.minBy { sample -> kotlin.math.abs(sample.distance - distance) }

    private fun WaveformTrack.visibleTraceEndpoints(
        direction: WaveformDirection,
    ): Pair<WaveformSample, WaveformSample> =
        sampleAt(signalAnchorDistance - TRACE_ANCHOR_PHASE * signalSpan / direction.travelSign) to
            sampleAt(
                signalAnchorDistance +
                    (TRACE_PHASE_SPAN - TRACE_ANCHOR_PHASE) * signalSpan / direction.travelSign,
            )

    private fun WaveformTrack.visibleTraceMidpoint(direction: WaveformDirection): WaveformSample =
        sampleAt(
            signalAnchorDistance +
                (TRACE_PHASE_SPAN / 2f - TRACE_ANCHOR_PHASE) * signalSpan / direction.travelSign,
        )
}
