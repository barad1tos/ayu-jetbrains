package dev.ayuislands.glow

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class GlowPresetTest {
    @Test
    fun `all named presets have non-null style, intensity, width, animation`() {
        for (preset in GlowPreset.entries) {
            if (preset == GlowPreset.CUSTOM) continue
            assertNotNull(preset.style, "${preset.name} should have a style")
            assertNotNull(preset.intensity, "${preset.name} should have an intensity")
            assertNotNull(preset.width, "${preset.name} should have a width")
            assertNotNull(preset.animation, "${preset.name} should have an animation")
        }
    }

    @Test
    fun `CUSTOM preset has all null values`() {
        assertNull(GlowPreset.CUSTOM.style)
        assertNull(GlowPreset.CUSTOM.intensity)
        assertNull(GlowPreset.CUSTOM.width)
        assertNull(GlowPreset.CUSTOM.animation)
    }

    @Test
    fun `preset values match specification`() {
        assertEquals(GlowStyle.SOFT, GlowPreset.WHISPER.style)
        assertEquals(35, GlowPreset.WHISPER.intensity)
        assertEquals(8, GlowPreset.WHISPER.width)
        assertEquals(GlowAnimation.NONE, GlowPreset.WHISPER.animation)

        assertEquals(GlowStyle.GRADIENT, GlowPreset.AMBIENT.style)
        assertEquals(45, GlowPreset.AMBIENT.intensity)
        assertEquals(10, GlowPreset.AMBIENT.width)
        assertEquals(GlowAnimation.BREATHE, GlowPreset.AMBIENT.animation)

        assertEquals(GlowStyle.SHARP_NEON, GlowPreset.NEON.style)
        assertEquals(65, GlowPreset.NEON.intensity)
        assertEquals(8, GlowPreset.NEON.width)
        assertEquals(GlowAnimation.NONE, GlowPreset.NEON.animation)

        assertEquals(GlowStyle.SHARP_NEON, GlowPreset.CYBERPUNK.style)
        assertEquals(85, GlowPreset.CYBERPUNK.intensity)
        assertEquals(10, GlowPreset.CYBERPUNK.width)
        assertEquals(GlowAnimation.PULSE, GlowPreset.CYBERPUNK.animation)
    }

    @Test
    fun `fromName returns correct preset for valid names`() {
        for (preset in GlowPreset.entries) {
            assertEquals(preset, GlowPreset.fromName(preset.name))
        }
    }

    @Test
    fun `fromName falls back to CUSTOM for invalid names`() {
        assertEquals(GlowPreset.CUSTOM, GlowPreset.fromName("NONEXISTENT"))
        assertEquals(GlowPreset.CUSTOM, GlowPreset.fromName(""))
        assertEquals(GlowPreset.CUSTOM, GlowPreset.fromName("whisper"))
    }

    @Test
    fun `detect matches known presets`() {
        assertEquals(
            GlowPreset.WHISPER,
            GlowPreset.detect(GlowStyle.SOFT, 35, 8, GlowAnimation.NONE),
        )
        assertEquals(
            GlowPreset.AMBIENT,
            GlowPreset.detect(GlowStyle.GRADIENT, 45, 10, GlowAnimation.BREATHE),
        )
        assertEquals(
            GlowPreset.NEON,
            GlowPreset.detect(GlowStyle.SHARP_NEON, 65, 8, GlowAnimation.NONE),
        )
        assertEquals(
            GlowPreset.CYBERPUNK,
            GlowPreset.detect(GlowStyle.SHARP_NEON, 85, 10, GlowAnimation.PULSE),
        )
    }

    @Test
    fun `detect returns CUSTOM for non-matching values`() {
        assertEquals(
            GlowPreset.CUSTOM,
            GlowPreset.detect(GlowStyle.SOFT, 50, 10, GlowAnimation.PULSE),
        )
        assertEquals(
            GlowPreset.CUSTOM,
            GlowPreset.detect(GlowStyle.GRADIENT, 30, 6, GlowAnimation.NONE),
        )
    }

    @Test
    fun `display names are human-readable`() {
        assertEquals("Whisper", GlowPreset.WHISPER.displayName)
        assertEquals("Ambient", GlowPreset.AMBIENT.displayName)
        assertEquals("Neon", GlowPreset.NEON.displayName)
        assertEquals("Cyberpunk", GlowPreset.CYBERPUNK.displayName)
        assertEquals("Custom", GlowPreset.CUSTOM.displayName)
    }

    @Test
    fun `exhaustive preset count`() {
        assertEquals(5, GlowPreset.entries.size)
    }
}
