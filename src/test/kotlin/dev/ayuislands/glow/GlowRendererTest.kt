package dev.ayuislands.glow

import java.awt.Color
import java.awt.Rectangle
import java.awt.image.BufferedImage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GlowRendererTest {
    private fun rendererWithStyle(
        style: GlowStyle,
        baseAlpha: Int = 200,
    ): GlowRenderer {
        val renderer = GlowRenderer()
        renderer.cachedStyle = style
        renderer.cachedBaseAlpha = baseAlpha
        return renderer
    }

    @Test
    fun `SOFT alpha decreases linearly from edge to center`() {
        val renderer = rendererWithStyle(GlowStyle.SOFT, baseAlpha = 255)
        val alphaAtEdge = renderer.computeAlpha(0.0f)
        val alphaAtMid = renderer.computeAlpha(0.5f)
        val alphaAtCenter = renderer.computeAlpha(1.0f)

        assertTrue(alphaAtEdge > alphaAtMid, "Edge alpha ($alphaAtEdge) should exceed mid ($alphaAtMid)")
        assertTrue(alphaAtMid > alphaAtCenter, "Mid alpha ($alphaAtMid) should exceed center ($alphaAtCenter)")
        assertEquals(0, alphaAtCenter, "Alpha at progress=1.0 should be 0")
    }

    @Test
    fun `SHARP_NEON has bright core then rapid falloff`() {
        val renderer = rendererWithStyle(GlowStyle.SHARP_NEON, baseAlpha = 200)

        val alphaInCore = renderer.computeAlpha(0.1f)
        val alphaAtCoreEdge = renderer.computeAlpha(0.29f)
        val alphaInBloom = renderer.computeAlpha(0.5f)
        val alphaAtEnd = renderer.computeAlpha(1.0f)

        assertEquals(200, alphaInCore, "Core alpha should equal baseAlpha")
        assertEquals(200, alphaAtCoreEdge, "Core edge alpha should equal baseAlpha")
        assertTrue(alphaInBloom < alphaInCore, "Bloom alpha ($alphaInBloom) should be less than core ($alphaInCore)")
        assertEquals(0, alphaAtEnd, "Alpha at end should be 0")
    }

    @Test
    fun `GRADIENT alpha decreases with half divisor`() {
        val renderer = rendererWithStyle(GlowStyle.GRADIENT, baseAlpha = 200)
        val alphaAtEdge = renderer.computeAlpha(0.0f)
        val alphaAtMid = renderer.computeAlpha(0.5f)
        val alphaAtEnd = renderer.computeAlpha(1.0f)

        assertTrue(alphaAtEdge > alphaAtMid)
        assertEquals(0, alphaAtEnd)

        val softRenderer = rendererWithStyle(GlowStyle.SOFT, baseAlpha = 200)
        val softAtEdge = softRenderer.computeAlpha(0.0f)
        assertTrue(
            alphaAtEdge > softAtEdge,
            "Gradient edge ($alphaAtEdge) should be brighter than Soft edge ($softAtEdge) due to smaller divisor",
        )
    }

    @Test
    fun `computeAlpha clamps to 0-255 range`() {
        val renderer = rendererWithStyle(GlowStyle.SOFT, baseAlpha = 255)
        for (progress in listOf(0.0f, 0.25f, 0.5f, 0.75f, 1.0f)) {
            val alpha = renderer.computeAlpha(progress)
            assertTrue(alpha in 0..255, "Alpha $alpha at progress $progress should be in 0..255")
        }
    }

    @Test
    fun `ensureCache invalidates frame cache on style change`() {
        val renderer = GlowRenderer()
        renderer.ensureCache(Color.RED, GlowStyle.SOFT, 40, 12)

        val image = BufferedImage(100, 100, BufferedImage.TYPE_INT_ARGB)
        val g2 = image.createGraphics()
        renderer.paintGlow(g2, Rectangle(0, 0, 100, 100), 12, 8)
        g2.dispose()

        renderer.ensureCache(Color.BLUE, GlowStyle.SHARP_NEON, 85, 20)

        renderer.invalidateCache()
        // After invalidation, accessing ensureCache with new params should work without error
        renderer.ensureCache(Color.GREEN, GlowStyle.GRADIENT, 50, 12)
    }

    @Test
    fun `paintGlow skips rendering for zero-size bounds`() {
        val renderer = GlowRenderer()
        renderer.ensureCache(Color.RED, GlowStyle.SOFT, 40, 12)

        val image = BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB)
        val g2 = image.createGraphics()

        // Should not throw for zero/negative bounds
        renderer.paintGlow(g2, Rectangle(0, 0, 0, 0), 12, 8)
        renderer.paintGlow(g2, Rectangle(0, 0, -1, -1), 12, 8)
        g2.dispose()
    }
}
