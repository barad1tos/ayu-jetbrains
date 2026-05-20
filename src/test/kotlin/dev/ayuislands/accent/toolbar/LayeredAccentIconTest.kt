package dev.ayuislands.accent.toolbar

import com.intellij.ui.ColorUtil
import dev.ayuislands.accent.AccentHex
import dev.ayuislands.accent.color.AccentHsl
import java.awt.Color
import java.awt.image.BufferedImage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Locks the [LayeredAccentIcon] paint + geometry contract:
 *
 *  - Outer-ring tint matches the accent hex (every chip-level test asserts
 *    this colour so a regression bleeds across the suite).
 *  - Inner-square paint differs by `pinned` flag — filled with
 *    `AccentHsl.darken(accent)` when pinned, outlined in the outer-ring
 *    colour when unpinned.
 *  - Geometry helper `isInsideInnerIslandHitBox` matches the actual inner-
 *    square paint bounds — the chip's `mousePressed` differentiator
 *    must agree with what the user sees.
 *
 * Pattern I seam: `paintForTest(g2)` lets tests rasterize into a
 * `BufferedImage` without booting an IDE.
 */
class LayeredAccentIconTest {
    @Test
    fun `accentColor exposes the constructor hex as a java awt Color`() {
        val icon = LayeredAccentIcon(SIZE_PX, AccentHex.unsafeOf("#FFB454"), pinned = true)
        assertEquals(Color(0xFF, 0xB4, 0x54), icon.accentColor)
    }

    @Test
    fun `isPinned mirrors the constructor flag`() {
        val pinnedIcon = LayeredAccentIcon(SIZE_PX, AccentHex.unsafeOf("#FFB454"), pinned = true)
        val unpinnedIcon = LayeredAccentIcon(SIZE_PX, AccentHex.unsafeOf("#FFB454"), pinned = false)
        assertTrue(pinnedIcon.isPinned)
        assertFalse(unpinnedIcon.isPinned)
    }

    @Test
    fun `iconWidth and iconHeight equal the constructor sizePx`() {
        val icon = LayeredAccentIcon(SIZE_PX, AccentHex.unsafeOf("#FFB454"), pinned = false)
        assertEquals(SIZE_PX, icon.iconWidth)
        assertEquals(SIZE_PX, icon.iconHeight)
    }

    @Test
    fun `outer ring paints the accent colour at the outer-ring band`() {
        val icon = LayeredAccentIcon(SIZE_PX, AccentHex.unsafeOf("#FFB454"), pinned = true)
        val image = paint(icon)

        // Sample a point inside the outer ring (just below the outer edge):
        // outer ring spans roughly inset=8.3% → middle=16.7%, so the band
        // center sits at ~12.5% of side length. At 64px that's pixel ≈ 8.
        val sampleX = (SIZE_PX * 0.125f).toInt()
        val sampleY = SIZE_PX / 2
        val sampled = Color(image.getRGB(sampleX, sampleY), true)
        assertEquals(Color(0xFF, 0xB4, 0x54), sampled, "Outer ring must paint the accent verbatim")
    }

    @Test
    fun `inner square is filled with darkened accent when pinned`() {
        val accent = AccentHex.unsafeOf("#FFB454")
        val icon = LayeredAccentIcon(SIZE_PX, accent, pinned = true)
        val image = paint(icon)

        val sampled = Color(image.getRGB(SIZE_PX / 2, SIZE_PX / 2), true)
        val expected = ColorUtil.fromHex(AccentHsl.darken(accent).value)
        assertEquals(expected, sampled, "Pinned inner island must paint AccentHsl.darken(accent)")
    }

    @Test
    fun `inner square is hollow (transparent centre) when not pinned`() {
        val icon = LayeredAccentIcon(SIZE_PX, AccentHex.unsafeOf("#FFB454"), pinned = false)
        val image = paint(icon)

        // Centre pixel must be transparent (alpha = 0) for the hollow state —
        // only the inner-square outline is painted, not the fill.
        val centre = Color(image.getRGB(SIZE_PX / 2, SIZE_PX / 2), true)
        assertEquals(0, centre.alpha, "Unpinned inner square must have a transparent centre (outline only)")
    }

    @Test
    fun `inner square outline paints the accent colour at the inner-square edge when unpinned`() {
        val accent = AccentHex.unsafeOf("#FFB454")
        val icon = LayeredAccentIcon(SIZE_PX, accent, pinned = false)
        val image = paint(icon)

        // The inner-square outline sits on the boundary at INNER_INSET_RATIO
        // from the chip edge. At 64px that's pixel 16. Sample slightly inside
        // the boundary so the anti-aliased stroke is fully opaque.
        val edge = (SIZE_PX * LayeredAccentIcon.INNER_INSET_RATIO + 1).toInt()
        val sampled = Color(image.getRGB(edge, SIZE_PX / 2), true)
        // Outline colour matches the outer ring (accent), not the darkened inner.
        assertNotEquals(0, sampled.alpha, "Unpinned outline must be opaque at the inner-square edge")
        assertEquals(accent.value, "#%02X%02X%02X".format(sampled.red, sampled.green, sampled.blue))
    }

    @Test
    fun `isInsideInnerIslandHitBox returns true for the centre and false for the outer ring`() {
        val size = SIZE_PX
        // Centre is inside.
        assertTrue(LayeredAccentIcon.isInsideInnerIslandHitBox(size / 2, size / 2, size))
        // Edge of the chip is outside.
        assertFalse(LayeredAccentIcon.isInsideInnerIslandHitBox(0, 0, size))
        assertFalse(LayeredAccentIcon.isInsideInnerIslandHitBox(size - 1, size - 1, size))
        // Just outside the inner-square boundary (within outer ring) is also outside.
        val outsideInner = (size * LayeredAccentIcon.INNER_INSET_RATIO - 1).toInt()
        assertFalse(LayeredAccentIcon.isInsideInnerIslandHitBox(outsideInner, outsideInner, size))
    }

    private fun paint(icon: LayeredAccentIcon): BufferedImage {
        val image = BufferedImage(SIZE_PX, SIZE_PX, BufferedImage.TYPE_INT_ARGB)
        val g2 = image.createGraphics()
        try {
            icon.paintForTest(g2)
        } finally {
            g2.dispose()
        }
        return image
    }

    private companion object {
        // 64px is large enough that anti-aliased rounding doesn't blur the
        // sampled pixels into background colour, yet small enough that test
        // execution is instant. Production chip is 13px; geometry is ratio-
        // based so any size renders the same shape.
        const val SIZE_PX: Int = 64
    }
}
