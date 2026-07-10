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
                flattenTopEdge = false,
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
    fun `editor track flattens the top edge with smooth corner shoulders`() {
        val track =
            WaveformTrack.create(
                overlayBounds = Rectangle(0, 0, 240, 160),
                margin = 16f,
                arcRadius = 12f,
                flattenTopEdge = true,
            )
        val topEdge = track.samples.filter { it.normalY < -0.999f && it.normalX in -0.001f..0.001f }
        val shoulders = track.samples.filter { it.amplitudeMask > 0f && it.amplitudeMask < 1f }

        assertTrue(topEdge.isNotEmpty())
        assertTrue(topEdge.all { it.amplitudeMask == 0f })
        assertTrue(shoulders.isNotEmpty())
        assertTrue(track.samples.all { it.amplitudeMask in 0f..1f })
        assertTrue(track.samples.any { it.amplitudeMask == 1f })
    }

    @Test
    fun `bounds smaller than the outward margin produce an empty track`() {
        val track =
            WaveformTrack.create(
                overlayBounds = Rectangle(0, 0, 20, 20),
                margin = 12f,
                arcRadius = 8f,
                flattenTopEdge = false,
            )

        assertTrue(track.samples.isEmpty())
        assertEquals(0f, track.length)
        assertTrue(!track.isClosed)
    }
}
