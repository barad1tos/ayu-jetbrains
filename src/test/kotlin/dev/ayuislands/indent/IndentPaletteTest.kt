package dev.ayuislands.indent

import dev.ayuislands.accent.AyuVariant
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class IndentPaletteTest {
    private val hexPattern = Regex("^[0-9A-Fa-f]{6}$")
    private val aarrggbbPattern = Regex("^[0-9A-Fa-f]{8}$")
    private val testAccent = "#FFCC66"

    @Test
    fun `forAccent uses accent color for indent colors`() {
        val palette = IndentPalette.forAccent(testAccent, AyuVariant.MIRAGE)
        assertEquals("FFCC66", palette.accentColor)
    }

    @Test
    fun `forAccent strips hash from accent`() {
        val palette = IndentPalette.forAccent("#E6B450", AyuVariant.DARK)
        assertEquals("E6B450", palette.accentColor)
    }

    @Test
    fun `each variant has non-empty error color`() {
        for (variant in AyuVariant.entries) {
            val palette = IndentPalette.forAccent(testAccent, variant)
            assertTrue(
                hexPattern.matches(palette.errorColor),
                "${variant.name} errorColor should be 6 hex chars",
            )
        }
    }

    @Test
    fun `toColorStrings produces 11 AARRGGBB strings`() {
        for (variant in AyuVariant.entries) {
            val palette = IndentPalette.forAccent(testAccent, variant)
            val colors = palette.toColorStrings(0x2E)
            assertEquals(
                11,
                colors.size,
                "${variant.name} should produce 11 color strings (1 error + 10 indent)",
            )
        }
    }

    @Test
    fun `toColorStrings format is 8 hex chars`() {
        val palette = IndentPalette.forAccent(testAccent, AyuVariant.MIRAGE)
        val colors = palette.toColorStrings(0x2E)
        for ((index, color) in colors.withIndex()) {
            assertTrue(
                aarrggbbPattern.matches(color),
                "color[$index]='$color' should be 8 hex chars",
            )
        }
    }

    @Test
    fun `error color is at index 0`() {
        val palette = IndentPalette.forAccent(testAccent, AyuVariant.MIRAGE)
        val colors = palette.toColorStrings(0x2E)
        assertEquals(
            palette.errorColor,
            colors[0].substring(2),
            "error color should be at index 0",
        )
    }

    @Test
    fun `indent colors use pyramid alpha pattern`() {
        val palette = IndentPalette.forAccent(testAccent, AyuVariant.MIRAGE)
        val alpha = 0x3C
        val colors = palette.toColorStrings(alpha)

        val indentAlphas = colors.drop(1).map { it.substring(0, 2).toInt(16) }
        assertEquals(10, indentAlphas.size, "Should have 10 indent colors")

        // First 6 ascending (steps 1..6)
        for (i in 0 until 5) {
            assertTrue(
                indentAlphas[i] < indentAlphas[i + 1],
                "Ascending phase: ${indentAlphas[i]} < ${indentAlphas[i + 1]}",
            )
        }

        // Last 4 descending (steps 5,4,3,2)
        for (i in 5 until 9) {
            assertTrue(
                indentAlphas[i] > indentAlphas[i + 1],
                "Descending phase: ${indentAlphas[i]} > ${indentAlphas[i + 1]}",
            )
        }
    }

    @Test
    fun `all indent colors share the same accent RGB`() {
        val palette = IndentPalette.forAccent(testAccent, AyuVariant.MIRAGE)
        val colors = palette.toColorStrings(0x2E)
        val indentRgbs = colors.drop(1).map { it.substring(2) }
        for (rgb in indentRgbs) {
            assertEquals("FFCC66", rgb, "All indent colors should use the accent RGB")
        }
    }

    @Test
    fun `different accents produce different palettes`() {
        val palette1 = IndentPalette.forAccent("#FFCC66", AyuVariant.MIRAGE)
        val palette2 = IndentPalette.forAccent("#E6B450", AyuVariant.MIRAGE)
        assertNotEquals(palette1.accentColor, palette2.accentColor)
    }

    @Test
    fun `error color is red when highlightErrors is true`() {
        val palette = IndentPalette.forAccent(testAccent, AyuVariant.MIRAGE)
        val colors = palette.toColorStrings(0x2E, highlightErrors = true)
        assertEquals(
            palette.errorColor,
            colors[0].substring(2),
            "error color should use red variant when highlightErrors=true",
        )
    }

    @Test
    fun `error color matches first indent color when highlightErrors is false`() {
        val palette = IndentPalette.forAccent(testAccent, AyuVariant.MIRAGE)
        val colors = palette.toColorStrings(0x2E, highlightErrors = false)

        // Error (index 0) should use accent color, not the red error color
        assertEquals(
            palette.accentColor,
            colors[0].substring(2),
            "error color should use accent when highlightErrors=false",
        )

        // Error alpha should match first indent step alpha
        assertEquals(
            colors[1].substring(0, 2),
            colors[0].substring(0, 2),
            "error alpha should match first indent step alpha",
        )
    }
}
