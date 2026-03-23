package dev.ayuislands.rotation

import dev.ayuislands.accent.AYU_ACCENT_PRESETS
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AccentRotationServiceTest {
    @Test
    fun `nextPresetHex wraps from last preset back to first`() {
        val (index, hex) = nextPresetHex(AYU_ACCENT_PRESETS.size - 1)
        assertEquals(0, index)
        assertEquals(AYU_ACCENT_PRESETS[0].hex, hex)
    }

    @Test
    fun `nextPresetHex advances index by one`() {
        val (index, hex) = nextPresetHex(0)
        assertEquals(1, index)
        assertEquals(AYU_ACCENT_PRESETS[1].hex, hex)
    }

    @Test
    fun `nextPresetHex wraps index 11 to 0 for 12 presets`() {
        assertEquals(12, AYU_ACCENT_PRESETS.size, "Expected 12 presets")
        val (index, _) = nextPresetHex(11)
        assertEquals(0, index)
    }

    @Test
    fun `nextPresetHex returns valid hex for every index`() {
        for (i in AYU_ACCENT_PRESETS.indices) {
            val (_, hex) = nextPresetHex(i)
            assertTrue(hex.matches(Regex("#[0-9A-Fa-f]{6}")), "Invalid hex: $hex at index $i")
        }
    }

    @Test
    fun `fromName PRESET round-trips`() {
        assertEquals(AccentRotationMode.PRESET, AccentRotationMode.fromName("PRESET"))
    }

    @Test
    fun `fromName RANDOM round-trips`() {
        assertEquals(AccentRotationMode.RANDOM, AccentRotationMode.fromName("RANDOM"))
    }

    @Test
    fun `fromName returns PRESET for null`() {
        assertEquals(AccentRotationMode.PRESET, AccentRotationMode.fromName(null))
    }

    @Test
    fun `fromName returns PRESET for unknown string`() {
        assertEquals(AccentRotationMode.PRESET, AccentRotationMode.fromName("garbage"))
    }

    @Test
    fun `all modes round-trip through name`() {
        for (mode in AccentRotationMode.entries) {
            assertEquals(mode, AccentRotationMode.fromName(mode.name))
        }
    }
}
