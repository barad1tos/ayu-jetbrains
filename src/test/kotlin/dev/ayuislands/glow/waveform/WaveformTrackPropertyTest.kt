package dev.ayuislands.glow.waveform

import java.awt.Rectangle
import kotlin.math.hypot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WaveformTrackPropertyTest {
    @Test
    fun `perimeter is closed with monotonic distance and outward unit normals`() {
        val track =
            WaveformTrack.create(
                overlayBounds = Rectangle(0, 0, 240, 160),
                margin = 16f,
                arcRadius = 12f,
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
                occupiedTopSpans = listOf(0..300, 700..899),
            )

        val anchor = track.sampleNearest(track.signalAnchorDistance)

        assertEquals(500f, anchor.x, 3f)
        assertEquals(220f, track.signalSpan, 0.1f)
    }

    @Test
    fun `narrow free top span compresses horizontal complex length`() {
        val track =
            WaveformTrack.create(
                overlayBounds = Rectangle(0, 0, 600, 300),
                margin = 16f,
                arcRadius = 12f,
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
            )

        assertEquals(track.length * 0.3f, track.signalSpan, 0.1f)
    }

    @Test
    fun `bounds smaller than the outward margin produce an empty track`() {
        val track =
            WaveformTrack.create(
                overlayBounds = Rectangle(0, 0, 20, 20),
                margin = 12f,
                arcRadius = 8f,
            )

        assertTrue(track.samples.isEmpty())
        assertEquals(0f, track.length)
        assertTrue(!track.isClosed)
    }

    private fun WaveformTrack.sampleNearest(distance: Float): WaveformSample =
        samples.minBy { sample -> kotlin.math.abs(sample.distance - distance) }
}
