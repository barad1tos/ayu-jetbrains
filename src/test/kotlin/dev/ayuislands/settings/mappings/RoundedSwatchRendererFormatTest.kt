package dev.ayuislands.settings.mappings

import dev.ayuislands.accent.AYU_ACCENT_PRESETS
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pure-function coverage for [RoundedSwatchRenderer.formatLabel] — the label shown
 * in the Overrides table's Color column. Preset matches inherit the curated name,
 * custom colors fall back to plain uppercase hex.
 */
class RoundedSwatchRendererFormatTest {
    @Test
    fun `formatLabel returns empty string for null`() {
        assertEquals("", RoundedSwatchRenderer.formatLabel(null))
    }

    @Test
    fun `formatLabel returns empty string for empty input`() {
        assertEquals("", RoundedSwatchRenderer.formatLabel(""))
    }

    @Test
    fun `formatLabel returns empty string for blank input`() {
        assertEquals("", RoundedSwatchRenderer.formatLabel("   "))
    }

    @Test
    fun `formatLabel decorates known preset hex with its curated name`() {
        assertEquals("Gold (#FFCD66)", RoundedSwatchRenderer.formatLabel("#FFCD66"))
    }

    @Test
    fun `formatLabel preset match is case-insensitive on hex — output upper`() {
        assertEquals("Gold (#FFCD66)", RoundedSwatchRenderer.formatLabel("#ffcd66"))
    }

    @Test
    fun `formatLabel returns plain uppercase hex for custom colors`() {
        assertEquals("#ABCDEF", RoundedSwatchRenderer.formatLabel("#abcdef"))
    }

    @Test
    fun `formatLabel returns plain hex when hex shape is unusual`() {
        // No preset match, not validated — the label passes through uppercase-only.
        assertEquals("#12345678", RoundedSwatchRenderer.formatLabel("#12345678"))
    }

    @Test
    fun `formatLabel renders a label for every curated preset`() {
        for (preset in AYU_ACCENT_PRESETS) {
            val label = RoundedSwatchRenderer.formatLabel(preset.hex)
            val expected = "${preset.name} (${preset.hex.uppercase()})"
            assertEquals(expected, label, "Preset ${preset.name} should render as \"$expected\"")
        }
    }
}
