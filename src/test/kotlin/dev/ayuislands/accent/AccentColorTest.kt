package dev.ayuislands.accent

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AccentColorTest {
    @Test
    fun `preset list contains exactly 12 colors`() {
        assertEquals(12, AYU_ACCENT_PRESETS.size)
    }

    @Test
    fun `all preset hex values are valid 7-char hex strings`() {
        val hexPattern = Regex("^#[0-9A-Fa-f]{6}$")
        for (preset in AYU_ACCENT_PRESETS) {
            assertTrue(
                hexPattern.matches(preset.hex),
                "${preset.name} hex '${preset.hex}' should be a valid hex color",
            )
        }
    }

    @Test
    fun `all preset names are non-empty`() {
        for (preset in AYU_ACCENT_PRESETS) {
            assertTrue(preset.name.isNotBlank(), "Preset name should not be blank")
        }
    }

    @Test
    fun `preset names are unique`() {
        val names = AYU_ACCENT_PRESETS.map { it.name }
        assertEquals(names.size, names.toSet().size, "Preset names should be unique")
    }

    @Test
    fun `preset hex values are unique`() {
        val hexValues = AYU_ACCENT_PRESETS.map { it.hex }
        assertEquals(hexValues.size, hexValues.toSet().size, "Preset hex values should be unique")
    }

    @Test
    fun `preset order matches specification`() {
        val expectedNames =
            listOf(
                "Coral",
                "Amber",
                "Orange",
                "Gold",
                "Sand",
                "Lavender",
                "Lime",
                "Mint",
                "Sky",
                "Cyan",
                "Rose",
                "Slate",
            )
        assertEquals(expectedNames, AYU_ACCENT_PRESETS.map { it.name })
    }

    @Test
    fun `AccentColor data class supports equality`() {
        val a = AccentColor("#FF0000", "Red")
        val b = AccentColor("#FF0000", "Red")
        assertEquals(a, b)
    }

    @Test
    fun `AccentColor data class supports copy`() {
        val original = AccentColor("#FF0000", "Red")
        val modified = original.copy(hex = "#00FF00")
        assertEquals("#00FF00", modified.hex)
        assertEquals("Red", modified.name)
    }
}
