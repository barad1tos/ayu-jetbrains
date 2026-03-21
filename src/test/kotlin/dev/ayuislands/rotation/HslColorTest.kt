package dev.ayuislands.rotation

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.awt.Color

class HslColorTest {
    @Test
    fun `toColor converts pure red at hue 0`() {
        val color = HslColor.toColor(0f, 1f, 0.5f)
        assertEquals(Color(255, 0, 0), color)
    }

    @Test
    fun `toColor converts pure green at hue 120`() {
        val color = HslColor.toColor(120f, 1f, 0.5f)
        assertEquals(Color(0, 255, 0), color)
    }

    @Test
    fun `toColor converts pure blue at hue 240`() {
        val color = HslColor.toColor(240f, 1f, 0.5f)
        assertEquals(Color(0, 0, 255), color)
    }

    @Test
    fun `toColor converts grey at zero saturation`() {
        val color = HslColor.toColor(0f, 0f, 0.5f)
        assertEquals(Color(128, 128, 128), color)
    }

    @Test
    fun `toColor converts black at zero lightness`() {
        val color = HslColor.toColor(0f, 0f, 0f)
        assertEquals(Color(0, 0, 0), color)
    }

    @Test
    fun `toColor converts white at full lightness`() {
        val color = HslColor.toColor(0f, 0f, 1f)
        assertEquals(Color(255, 255, 255), color)
    }

    @Test
    fun `toHex returns uppercase hex for pure red`() {
        val hex = HslColor.toHex(0f, 1f, 0.5f)
        assertEquals("#FF0000", hex)
    }

    @Test
    fun `fromColor round-trips pure red`() {
        val (hue, saturation, lightness) = HslColor.fromColor(Color(255, 0, 0))
        assertEquals(0f, hue, 0.5f)
        assertEquals(1f, saturation, 0.01f)
        assertEquals(0.5f, lightness, 0.01f)
    }

    @Test
    fun `fromColor round-trips grey`() {
        val (hue, saturation, lightness) = HslColor.fromColor(Color(128, 128, 128))
        assertEquals(0f, saturation, 0.01f)
        assertEquals(0.5f, lightness, 0.02f)
    }
}
