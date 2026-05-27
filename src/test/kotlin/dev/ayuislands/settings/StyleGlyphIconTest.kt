package dev.ayuislands.settings

import com.intellij.util.ui.JBUI
import io.mockk.every
import io.mockk.mockk
import java.awt.Color
import java.awt.Font
import java.awt.Graphics
import java.awt.image.BufferedImage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class StyleGlyphIconTest {
    @Test
    fun `icon is a square whose side equals the configured cell`() {
        val icon = StyleGlyphIcon("B", Font.BOLD, Color.WHITE, cell = CELL, glyphSize = GLYPH)
        assertEquals(CELL, icon.iconWidth, "icon width must equal the configured cell")
        assertEquals(CELL, icon.iconHeight, "icon height must equal the configured cell")
        assertEquals(icon.iconWidth, icon.iconHeight, "the toggle cell must be square")
    }

    @Test
    fun `default cell and glyph sizes are the DPI-scaled companion constants`() {
        val icon = StyleGlyphIcon("I", Font.ITALIC, Color.WHITE)
        assertEquals(
            JBUI.scale(StyleGlyphIcon.ICON_CELL),
            icon.iconWidth,
            "default cell side must be the DPI-scaled ICON_CELL constant",
        )
    }

    @Test
    fun `at-rest icon leaves the cell corners transparent`() {
        val icon = StyleGlyphIcon("B", Font.BOLD, OPAQUE_FG, background = null, cell = CELL, glyphSize = GLYPH)
        val image = paint(icon)

        val corner = Color(image.getRGB(0, 0), true)
        assertEquals(0, corner.alpha, "at-rest icon must not paint a background")
    }

    @Test
    fun `engaged icon fills the cell interior with the pressed background colour`() {
        val fill = Color(0x33, 0x66, 0x99)
        val icon = StyleGlyphIcon("B", Font.BOLD, OPAQUE_FG, background = fill, cell = CELL, glyphSize = GLYPH)
        val image = paint(icon)

        val sampled = Color(image.getRGB(3, CELL - 3), true)
        assertNotEquals(0, sampled.alpha, "engaged icon must paint an opaque pressed-fill")
        assertEquals(fill.rgb, sampled.rgb, "the engaged pressed-fill must paint the supplied background colour")
    }

    @Test
    fun `bordered icon paints a quiet chip outline without requiring a fill`() {
        val border = Color(0x44, 0x55, 0x66)
        val icon =
            StyleGlyphIcon(
                "",
                Font.PLAIN,
                OPAQUE_FG,
                background = null,
                border = border,
                cell = CELL,
                glyphSize = GLYPH,
            )
        val image = paint(icon)

        var painted = 0
        for (x in 0 until CELL) {
            for (y in 0..1) {
                if (Color(image.getRGB(x, y), true).alpha != 0) painted++
            }
        }
        assertTrue(painted > 0, "bordered icon must paint a visible chip outline")
    }

    @Test
    fun `engaged and at-rest renders differ so the cue is never color-only`() {
        val restIcon = StyleGlyphIcon("I", Font.ITALIC, DIM_FG, background = null, cell = CELL, glyphSize = GLYPH)
        val engagedIcon =
            StyleGlyphIcon(
                "I",
                Font.ITALIC,
                OPAQUE_FG,
                background = Color(0x33, 0x66, 0x99),
                cell = CELL,
                glyphSize = GLYPH,
            )
        val restImage = paint(restIcon)
        val engagedImage = paint(engagedIcon)
        assertNotEquals(
            restImage.getRGB(3, CELL - 3),
            engagedImage.getRGB(3, CELL - 3),
            "engaged fill vs at-rest transparency must differ",
        )
    }

    @Test
    fun `the glyph itself rasterises non-transparent pixels`() {
        val icon = StyleGlyphIcon("B", Font.BOLD, OPAQUE_FG, background = null, cell = CELL, glyphSize = GLYPH)
        val image = paint(icon)

        var painted = 0
        for (x in 0 until CELL) {
            for (y in 0 until CELL) {
                if (Color(image.getRGB(x, y), true).alpha != 0) painted++
            }
        }
        assertTrue(painted > 0, "the glyph must draw at least some opaque pixels")
    }

    @Test
    fun `paintIcon tolerates a non-Graphics2D context via the null-guard`() {
        val icon = StyleGlyphIcon("B", Font.BOLD, OPAQUE_FG, cell = CELL, glyphSize = GLYPH)
        val plainGraphics = mockk<Graphics>(relaxed = true)
        val outerGraphics = mockk<Graphics>(relaxed = true)
        every { outerGraphics.create() } returns plainGraphics

        icon.paintIcon(null, outerGraphics, 0, 0)
    }

    private fun paint(icon: StyleGlyphIcon): BufferedImage {
        val image = BufferedImage(CELL, CELL, BufferedImage.TYPE_INT_ARGB)
        val graphics = image.createGraphics()
        try {
            icon.paintIcon(null, graphics, 0, 0)
        } finally {
            graphics.dispose()
        }
        return image
    }

    private companion object {
        const val CELL = 32
        const val GLYPH = 24
        val OPAQUE_FG = Color(0xFF, 0xFF, 0xFF)
        val DIM_FG = Color(0x88, 0x88, 0x88)
    }
}
