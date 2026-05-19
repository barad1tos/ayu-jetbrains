package dev.ayuislands.accent.toolbar.popup

import com.intellij.util.ui.JBUI
import java.awt.Color
import java.awt.image.BufferedImage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Locks the [BlockSeparator] hairline contract:
 *   - preferred height is 1 px JBUI-scaled,
 *   - inset BLOCK_SEPARATOR_PAD pixels on each side (transparent on the inset),
 *   - line color falls back to a non-transparent pixel inside the painted band.
 */
class BlockSeparatorTest {
    @Test
    fun `preferred height is one px JBUI scaled`() {
        val separator = BlockSeparator()
        assertEquals(JBUI.scale(1), separator.preferredSize.height)
    }

    @Test
    fun `paint draws hairline inset BLOCK_SEPARATOR_PAD on each side`() {
        val width = 200
        val height = 4
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        val separator =
            BlockSeparator().apply {
                setSize(width, height)
            }
        val g2 = image.createGraphics()
        try {
            separator.paintForTest(g2)
        } finally {
            g2.dispose()
        }

        val pad = JBUI.scale(Density.BLOCK_SEPARATOR_PAD)
        val insidePixel = image.getRGB(width / 2, height / 2)
        val outsidePixel = image.getRGB(pad / 2, height / 2)
        assertTrue(Color(insidePixel, true).alpha > 0, "Inside-inset pixel must be drawn")
        assertEquals(0, Color(outsidePixel, true).alpha, "Pixel inside the side-pad must stay transparent")
    }

    @Test
    fun `paint is a no-op when width is smaller than 2 x pad`() {
        val pad = JBUI.scale(Density.BLOCK_SEPARATOR_PAD)
        val image = BufferedImage(maxOf(1, pad / 2), 4, BufferedImage.TYPE_INT_ARGB)
        val separator =
            BlockSeparator().apply {
                setSize(image.width, image.height)
            }
        val g2 = image.createGraphics()
        try {
            separator.paintForTest(g2)
        } finally {
            g2.dispose()
        }
        // Image stays fully transparent — no line painted, no exception thrown.
        for (x in 0 until image.width) {
            for (y in 0 until image.height) {
                assertEquals(0, Color(image.getRGB(x, y), true).alpha)
            }
        }
    }
}
