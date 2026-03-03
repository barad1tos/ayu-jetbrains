package dev.ayuislands.glow

import kotlin.test.Test
import kotlin.test.assertEquals

class GlowEnumsTest {
    // GlowStyle

    @Test
    fun `GlowStyle fromName returns correct entry for valid names`() {
        assertEquals(GlowStyle.SOFT, GlowStyle.fromName("SOFT"))
        assertEquals(GlowStyle.SHARP_NEON, GlowStyle.fromName("SHARP_NEON"))
        assertEquals(GlowStyle.GRADIENT, GlowStyle.fromName("GRADIENT"))
    }

    @Test
    fun `GlowStyle fromName falls back to SOFT for invalid name`() {
        assertEquals(GlowStyle.SOFT, GlowStyle.fromName("NONEXISTENT"))
        assertEquals(GlowStyle.SOFT, GlowStyle.fromName(""))
        assertEquals(GlowStyle.SOFT, GlowStyle.fromName("soft"))
    }

    @Test
    fun `GlowStyle entries have correct display names`() {
        assertEquals("Soft", GlowStyle.SOFT.displayName)
        assertEquals("Sharp Neon", GlowStyle.SHARP_NEON.displayName)
        assertEquals("Gradient", GlowStyle.GRADIENT.displayName)
    }

    @Test
    fun `GlowStyle entries have positive default intensity and width`() {
        for (style in GlowStyle.entries) {
            assert(style.defaultIntensity > 0) { "${style.name} intensity should be positive" }
            assert(style.defaultWidth > 0) { "${style.name} width should be positive" }
        }
    }

    // GlowTabMode

    @Test
    fun `GlowTabMode fromName returns correct entry for valid names`() {
        assertEquals(GlowTabMode.UNDERLINE, GlowTabMode.fromName("UNDERLINE"))
        assertEquals(GlowTabMode.FULL_BORDER, GlowTabMode.fromName("FULL_BORDER"))
        assertEquals(GlowTabMode.OFF, GlowTabMode.fromName("OFF"))
    }

    @Test
    fun `GlowTabMode fromName falls back to UNDERLINE for invalid name`() {
        assertEquals(GlowTabMode.UNDERLINE, GlowTabMode.fromName("NONEXISTENT"))
        assertEquals(GlowTabMode.UNDERLINE, GlowTabMode.fromName(""))
    }

    @Test
    fun `GlowTabMode entries have correct display names`() {
        assertEquals("Underline", GlowTabMode.UNDERLINE.displayName)
        assertEquals("Full Border", GlowTabMode.FULL_BORDER.displayName)
        assertEquals("Off", GlowTabMode.OFF.displayName)
    }

    // GlowAnimation

    @Test
    fun `GlowAnimation fromName returns correct entry for valid names`() {
        assertEquals(GlowAnimation.NONE, GlowAnimation.fromName("NONE"))
        assertEquals(GlowAnimation.PULSE, GlowAnimation.fromName("PULSE"))
        assertEquals(GlowAnimation.BREATHE, GlowAnimation.fromName("BREATHE"))
        assertEquals(GlowAnimation.REACTIVE, GlowAnimation.fromName("REACTIVE"))
    }

    @Test
    fun `GlowAnimation fromName falls back to NONE for invalid name`() {
        assertEquals(GlowAnimation.NONE, GlowAnimation.fromName("NONEXISTENT"))
        assertEquals(GlowAnimation.NONE, GlowAnimation.fromName(""))
    }

    @Test
    fun `GlowAnimation entries have correct display names`() {
        assertEquals("None", GlowAnimation.NONE.displayName)
        assertEquals("Pulse", GlowAnimation.PULSE.displayName)
        assertEquals("Breathe", GlowAnimation.BREATHE.displayName)
        assertEquals("Reactive", GlowAnimation.REACTIVE.displayName)
    }

    @Test
    fun `all three enums have exhaustive entries`() {
        assertEquals(3, GlowStyle.entries.size)
        assertEquals(3, GlowTabMode.entries.size)
        assertEquals(4, GlowAnimation.entries.size)
    }
}
