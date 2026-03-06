package dev.ayuislands.indent

import dev.ayuislands.accent.AyuVariant
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class IndentPaletteTest {
    private val hexPattern = Regex("^[0-9A-Fa-f]{6}$")
    private val aarrggbbPattern = Regex("^[0-9A-Fa-f]{8}$")

    @Test
    fun `each variant has exactly 6 indent colors`() {
        for (variant in AyuVariant.entries) {
            val palette = IndentPalette.forVariant(variant)
            assertEquals(
                6,
                palette.indentColors.size,
                "${variant.name} should have 6 indent colors",
            )
        }
    }

    @Test
    fun `each variant has non-empty error color`() {
        for (variant in AyuVariant.entries) {
            val palette = IndentPalette.forVariant(variant)
            assertTrue(
                palette.errorColor.isNotBlank(),
                "${variant.name} should have non-empty error color",
            )
        }
    }

    @Test
    fun `toColorStrings produces 7 AARRGGBB strings`() {
        for (variant in AyuVariant.entries) {
            val palette = IndentPalette.forVariant(variant)
            val colors = palette.toColorStrings(0x2E)
            assertEquals(
                7,
                colors.size,
                "${variant.name} should produce 7 color strings (1 error + 6 indent)",
            )
        }
    }

    @Test
    fun `toColorStrings format is 8 hex chars`() {
        for (variant in AyuVariant.entries) {
            val palette = IndentPalette.forVariant(variant)
            val colors = palette.toColorStrings(0x2E)
            for ((index, color) in colors.withIndex()) {
                assertTrue(
                    aarrggbbPattern.matches(color),
                    "${variant.name} color[$index]='$color' should be 8 hex chars",
                )
            }
        }
    }

    @Test
    fun `error color is at index 0`() {
        for (variant in AyuVariant.entries) {
            val palette = IndentPalette.forVariant(variant)
            val colors = palette.toColorStrings(0x2E)
            assertEquals(
                palette.errorColor,
                colors[0].substring(2),
                "${variant.name} error color should be at index 0",
            )
        }
    }

    @Test
    fun `all raw colors are valid 6-char hex`() {
        for (variant in AyuVariant.entries) {
            val palette = IndentPalette.forVariant(variant)
            assertTrue(
                hexPattern.matches(palette.errorColor),
                "${variant.name} errorColor should be 6 hex chars",
            )
            for ((index, color) in palette.indentColors.withIndex()) {
                assertTrue(
                    hexPattern.matches(color),
                    "${variant.name} indentColors[$index]='$color' should be 6 hex chars",
                )
            }
        }
    }

    @Test
    fun `alpha is correctly prepended`() {
        val palette = IndentPalette.forVariant(AyuVariant.MIRAGE)
        val colors = palette.toColorStrings(0x1A)
        for (color in colors) {
            assertTrue(
                color.startsWith("1A"),
                "Color '$color' should start with alpha '1A'",
            )
        }
    }

    @Test
    fun `all variants produce distinct palettes`() {
        val palettes = AyuVariant.entries.map { IndentPalette.forVariant(it) }
        for (i in palettes.indices) {
            for (j in i + 1 until palettes.size) {
                assertNotEquals(
                    palettes[i],
                    palettes[j],
                    "${AyuVariant.entries[i].name} and ${AyuVariant.entries[j].name} " +
                        "should have distinct palettes",
                )
            }
        }
    }
}
