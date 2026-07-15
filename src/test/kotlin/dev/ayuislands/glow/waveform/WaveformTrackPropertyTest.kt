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
                config = WaveformConfig(motion = WaveformMotion.STATIC_PULSE),
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
                config = WaveformConfig(motion = WaveformMotion.STATIC_PULSE),
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
                config = WaveformConfig(motion = WaveformMotion.STATIC_PULSE),
            )

        val anchor = track.sampleNearest(track.signalAnchorDistance)

        assertEquals(600f, anchor.x, 3f)
        assertEquals(16f, anchor.y, 0.1f)
    }

    @Test
    fun `largest free top span centers a full complex`() {
        val track =
            WaveformTrack.create(
                overlayBounds = Rectangle(0, 0, 900, 400),
                margin = 16f,
                arcRadius = 12f,
                config = WaveformConfig(motion = WaveformMotion.STATIC_PULSE),
                occupiedTopSpans = listOf(0..300, 700..899),
            )

        val anchor = track.sampleNearest(track.signalAnchorDistance)

        assertEquals(500f, anchor.x, 3f)
        assertEquals(220f, track.signalSpan, 0.1f)
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
    fun `tab occupancy moves the monitor to the right edge without changing its length`() {
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
            val beginning =
                crowdedTrack.sampleAt(
                    crowdedTrack.signalAnchorDistance -
                        TRACE_ANCHOR_PHASE * crowdedTrack.signalSpan / direction.travelSign,
                )
            val end =
                crowdedTrack.sampleAt(
                    crowdedTrack.signalAnchorDistance +
                        (TRACE_PHASE_SPAN - TRACE_ANCHOR_PHASE) *
                        crowdedTrack.signalSpan / direction.travelSign,
                )

            assertEquals(360f, roomyTrack.signalSpan * TRACE_PHASE_SPAN, 0.1f)
            assertEquals(360f, crowdedTrack.signalSpan * TRACE_PHASE_SPAN, 0.1f)
            assertTrue(roomyTrack.sampleNearest(roomyTrack.signalAnchorDistance).normalY < -0.999f)
            assertTrue(crowdedTrack.sampleNearest(crowdedTrack.signalAnchorDistance).normalX > 0.999f)
            assertEquals(1f, beginning.amplitudeMask, 0.001f)
            assertEquals(1f, end.amplitudeMask, 0.001f)
        }
    }

    @Test
    fun `narrow free top span compresses horizontal complex length`() {
        val track =
            WaveformTrack.create(
                overlayBounds = Rectangle(0, 0, 600, 300),
                margin = 16f,
                arcRadius = 12f,
                config = WaveformConfig(motion = WaveformMotion.STATIC_PULSE),
                occupiedTopSpans = listOf(0..199, 380..599),
            )

        val anchor = track.sampleNearest(track.signalAnchorDistance)

        assertEquals(290f, anchor.x, 3f)
        assertTrue(track.signalSpan in 178f..181f)
    }

    @Test
    fun `top gaps below minimum move the anchor to the upper right edge`() {
        val track =
            WaveformTrack.create(
                overlayBounds = Rectangle(0, 0, 500, 360),
                margin = 16f,
                arcRadius = 12f,
                config = WaveformConfig(motion = WaveformMotion.STATIC_PULSE),
                occupiedTopSpans = listOf(0..170, 290..499),
            )

        val anchor = track.sampleNearest(track.signalAnchorDistance)

        assertEquals(484f, anchor.x, 0.1f)
        assertEquals(120f, anchor.y, 4f)
        assertEquals(220f.coerceAtMost(track.length * 0.3f), track.signalSpan, 0.1f)
    }

    @Test
    fun `complex span is capped at thirty percent of a small perimeter`() {
        val track =
            WaveformTrack.create(
                overlayBounds = Rectangle(0, 0, 180, 120),
                margin = 8f,
                arcRadius = 8f,
                config = WaveformConfig(motion = WaveformMotion.STATIC_PULSE),
            )

        assertEquals(track.length * 0.3f, track.signalSpan, 0.1f)
    }

    @Test
    fun `monitor trace keeps the configured span on large islands`() {
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
    fun `monitor trace clamps raw configured lengths before rendering`() {
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
                config = WaveformConfig(motion = WaveformMotion.STATIC_PULSE),
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
                config = WaveformConfig(motion = WaveformMotion.MONITOR),
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
                config = WaveformConfig(motion = WaveformMotion.MONITOR),
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
}
