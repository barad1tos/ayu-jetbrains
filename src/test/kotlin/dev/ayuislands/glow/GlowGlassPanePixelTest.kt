package dev.ayuislands.glow

import java.awt.Color
import java.awt.image.BufferedImage
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Pixel-level locks for glow placements rendered through the real
 * [GlowGlassPane.paint] path: SIDE_EDGES must light BOTH vertical edges as
 * straight full-height strips — uniform from top to bottom, with no rounded
 * corner hooks — and nothing else.
 */
class GlowGlassPanePixelTest {
    private companion object {
        const val WIDTH = 400
        const val HEIGHT = 300
        const val ALPHA_FLOOR = 8
        const val MAX_FADE_STEPS = 40
    }

    private fun paintedImage(
        placement: GlowPlacement,
        width: Int = WIDTH,
        height: Int = HEIGHT,
    ): BufferedImage {
        val pane =
            GlowGlassPane(
                glowColor = Color(0xFF8F40),
                glowStyle = GlowStyle.SOFT,
                glowIntensity = 80,
                glowWidth = 12,
                isEditorOverlay = false,
                glowPlacement = placement,
            )
        pane.setSize(width, height)
        // paintComponent guards on fade-in alpha; tests bypass the animation timer.
        GlowGlassPane::class.java.getDeclaredField("fadeAlpha").apply {
            isAccessible = true
            setFloat(pane, 1.0f)
        }

        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
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
    fun `side edges strips run straight from top to bottom without corner hooks`() {
        val image = paintedImage(GlowPlacement.SIDE_EDGES)

        // A straight strip means the edge column carries the same alpha at the
        // very top, the middle, and the very bottom — a clipped rounded frame
        // would bend away from the edge near the corners.
        val top = (image.getRGB(2, 0) ushr 24) and 0xFF
        val middle = (image.getRGB(2, HEIGHT / 2) ushr 24) and 0xFF
        val bottom = (image.getRGB(2, HEIGHT - 1) ushr 24) and 0xFF
        assertTrue(top == middle && middle == bottom, "edge column alpha must be uniform: $top/$middle/$bottom")
        assertTrue(middle > ALPHA_FLOOR, "edge column must actually glow")

        // No horizontal spill beyond the strip at the corners (the old clipped
        // frame leaked arc segments there).
        val cornerSpill = (image.getRGB(WIDTH / 2, 1) ushr 24) and 0xFF
        assertTrue(cornerSpill <= ALPHA_FLOOR, "top row must stay clear outside the vertical strips")
    }

    @Test
    fun `fade converges onto a fractional target from both directions`() {
        val pane =
            GlowGlassPane(
                glowColor = Color(0xFF8F40),
                glowStyle = GlowStyle.SOFT,
                glowIntensity = 80,
                glowWidth = 12,
            )
        val alphaField =
            GlowGlassPane::class.java.getDeclaredField("fadeAlpha").apply { isAccessible = true }
        val targetField =
            GlowGlassPane::class.java.getDeclaredField("fadeTarget").apply { isAccessible = true }

        // From above: a focused overlay dimming down to 30% must stop at 30%.
        alphaField.setFloat(pane, 1.0f)
        targetField.setFloat(pane, 0.3f)
        repeat(MAX_FADE_STEPS) { pane.advanceFade() }
        assertTrue(alphaField.getFloat(pane) == 0.3f, "fade must settle exactly on the dim target")

        // From below: an attached overlay brightening up to the dim level.
        alphaField.setFloat(pane, 0.0f)
        repeat(MAX_FADE_STEPS) { pane.advanceFade() }
        assertTrue(alphaField.getFloat(pane) == 0.3f, "fade must rise to the dim target and hold")
    }

    @Test
    fun `narrow odd-width overlay paints its center column once, not as a bright seam`() {
        // Strips meet in the middle on a 7px-wide overlay; the shared center
        // column must carry single-pass alpha, so the falloff stays monotonic
        // toward the middle instead of spiking where the passes overlap.
        val image = paintedImage(GlowPlacement.SIDE_EDGES, width = 7, height = 50)

        val nearEdge = (image.getRGB(2, 25) ushr 24) and 0xFF
        val center = (image.getRGB(3, 25) ushr 24) and 0xFF
        assertTrue(
            center <= nearEdge,
            "center column ($center) must not outshine the column nearer the edge ($nearEdge)",
        )
    }

    @Test
    fun `island placement paints the full frame including top and bottom`() {
        val image = paintedImage(GlowPlacement.ISLAND)

        assertTrue(image.rowHasGlow(2), "top edge must glow on the full frame")
        assertTrue(image.rowHasGlow(HEIGHT - 3), "bottom edge must glow on the full frame")
    }
}
