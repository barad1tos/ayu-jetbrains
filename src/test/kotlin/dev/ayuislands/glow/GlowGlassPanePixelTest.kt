package dev.ayuislands.glow

import java.awt.Color
import java.awt.image.BufferedImage
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Pixel-level locks for partial glow placements rendered through the real
 * [GlowGlassPane.paint] path: SIDE_EDGES must light BOTH vertical edges and
 * nothing else. Guards against clip regressions that unit tests on
 * [GlowPlacementGeometry] rectangles alone cannot see.
 */
class GlowGlassPanePixelTest {
    private companion object {
        const val WIDTH = 400
        const val HEIGHT = 300
        const val ALPHA_FLOOR = 8
    }

    private fun paintedImage(placement: GlowPlacement): BufferedImage {
        val pane =
            GlowGlassPane(
                glowColor = Color(0xFF8F40),
                glowStyle = GlowStyle.SOFT,
                glowIntensity = 80,
                glowWidth = 12,
                isEditorOverlay = false,
                glowPlacement = placement,
            )
        pane.setSize(WIDTH, HEIGHT)
        // paintComponent guards on fade-in alpha; tests bypass the animation timer.
        GlowGlassPane::class.java.getDeclaredField("fadeAlpha").apply {
            isAccessible = true
            setFloat(pane, 1.0f)
        }

        val image = BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_ARGB)
        val graphics = image.createGraphics()
        try {
            pane.paint(graphics)
        } finally {
            graphics.dispose()
        }
        return image
    }

    private fun BufferedImage.columnHasGlow(x: Int): Boolean =
        (0 until height).any { y -> (getRGB(x, y) ushr 24) and 0xFF > ALPHA_FLOOR }

    private fun BufferedImage.rowHasGlow(y: Int): Boolean =
        (0 until width).any { x -> (getRGB(x, y) ushr 24) and 0xFF > ALPHA_FLOOR }

    @Test
    fun `side edges paints both left and right vertical strips`() {
        val image = paintedImage(GlowPlacement.SIDE_EDGES)

        assertTrue(image.columnHasGlow(2), "left edge must glow")
        assertTrue(image.columnHasGlow(WIDTH - 3), "right edge must glow")
    }

    @Test
    fun `side edges leaves the horizontal middle unpainted`() {
        val image = paintedImage(GlowPlacement.SIDE_EDGES)

        val middleGlow = (0 until HEIGHT).any { y -> (image.getRGB(WIDTH / 2, y) ushr 24) and 0xFF > ALPHA_FLOOR }
        assertTrue(!middleGlow, "center column must stay clear so only side edges light up")
    }

    @Test
    fun `island placement paints the full frame including top and bottom`() {
        val image = paintedImage(GlowPlacement.ISLAND)

        assertTrue(image.rowHasGlow(2), "top edge must glow on the full frame")
        assertTrue(image.rowHasGlow(HEIGHT - 3), "bottom edge must glow on the full frame")
    }
}
