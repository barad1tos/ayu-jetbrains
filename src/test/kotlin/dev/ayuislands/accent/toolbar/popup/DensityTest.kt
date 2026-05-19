package dev.ayuislands.accent.toolbar.popup

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Locks every spacing constant in [Density] against accidental drift. All ten constants
 * are pre-scale (consumer wraps in `JBUI.scale(...)`); `CARD_ARC` stays a `Float`
 * because it feeds `RoundRectangle2D.Float` directly. A test failure means the
 * constant table was mutated unintentionally.
 */
class DensityTest {
    @Test
    fun `POPUP_PAD equals 8`() {
        assertEquals(8, Density.POPUP_PAD)
    }

    @Test
    fun `CARD_GAP equals 6`() {
        assertEquals(6, Density.CARD_GAP)
    }

    @Test
    fun `CARD_CONTENT_PAD equals 8`() {
        assertEquals(8, Density.CARD_CONTENT_PAD)
    }

    @Test
    fun `CARD_ARC equals 6f`() {
        assertEquals(6f, Density.CARD_ARC)
    }

    @Test
    fun `SWATCH_GAP equals 3`() {
        assertEquals(3, Density.SWATCH_GAP)
    }

    @Test
    fun `SWATCH_ARC equals 6`() {
        assertEquals(6, Density.SWATCH_ARC)
    }

    @Test
    fun `TILE_GAP equals 6`() {
        assertEquals(6, Density.TILE_GAP)
    }

    @Test
    fun `ACTION_GAP equals 4`() {
        assertEquals(4, Density.ACTION_GAP)
    }

    @Test
    fun `SECTION_HEADER_H equals 18`() {
        assertEquals(18, Density.SECTION_HEADER_H)
    }

    @Test
    fun `BLOCK_SEPARATOR_PAD equals 8`() {
        assertEquals(8, Density.BLOCK_SEPARATOR_PAD)
    }
}
