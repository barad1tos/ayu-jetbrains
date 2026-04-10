package dev.ayuislands.indent

import dev.ayuislands.accent.AyuVariant
import io.kotest.property.Arb
import io.kotest.property.arbitrary.int
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class IndentPalettePropertyTest {
    @Test
    fun `toColorStrings always returns exactly 11 elements`(): Unit =
        runBlocking {
            checkAll(Arb.int(1..255)) { alpha ->
                val palette = IndentPalette(errorColor = "F27983", accentColor = "FFCC66")
                val colors = palette.toColorStrings(alpha)
                assertEquals(
                    11,
                    colors.size,
                    "toColorStrings must return 11 elements for alpha=$alpha, got ${colors.size}",
                )
            }
        }

    @Test
    fun `toColorStrings elements are always 8-char hex strings`(): Unit =
        runBlocking {
            checkAll(Arb.int(1..255)) { alpha ->
                val palette = IndentPalette(errorColor = "F27983", accentColor = "FFCC66")
                val colors = palette.toColorStrings(alpha)
                for ((index, color) in colors.withIndex()) {
                    assertTrue(
                        color.matches(Regex("[0-9A-Fa-f]{8}")),
                        "Color at index $index must be 8 hex chars: '$color' (alpha=$alpha)",
                    )
                }
            }
        }

    @Test
    fun `toColorStrings with error highlight disabled uses accent color for first element`(): Unit =
        runBlocking {
            checkAll(Arb.int(1..255)) { alpha ->
                val accentColor = "FFCC66"
                val palette = IndentPalette(errorColor = "F27983", accentColor = accentColor)
                val colors = palette.toColorStrings(alpha, highlightErrors = false)
                assertTrue(
                    colors[0].endsWith(accentColor),
                    "First element without error highlight must end with accent color: '${colors[0]}'",
                )
            }
        }

    @Test
    fun `toColorStrings with error highlight enabled uses error color for first element`(): Unit =
        runBlocking {
            checkAll(Arb.int(1..255)) { alpha ->
                val errorColor = "F27983"
                val palette = IndentPalette(errorColor = errorColor, accentColor = "FFCC66")
                val colors = palette.toColorStrings(alpha, highlightErrors = true)
                assertTrue(
                    colors[0].endsWith(errorColor),
                    "First element with error highlight must end with error color: '${colors[0]}'",
                )
            }
        }

    @Test
    fun `forAccent strips hash prefix from accent color`() {
        for (variant in AyuVariant.entries) {
            val palette = IndentPalette.forAccent("#FFCC66", variant)
            assertFalse(
                palette.accentColor.startsWith("#"),
                "forAccent must strip # prefix for variant ${variant.name}",
            )
            assertEquals("FFCC66", palette.accentColor)
        }
    }

    @Test
    fun `forAccent handles accent without hash prefix`() {
        for (variant in AyuVariant.entries) {
            val palette = IndentPalette.forAccent("E6B450", variant)
            assertFalse(
                palette.accentColor.startsWith("#"),
                "forAccent must handle bare hex for variant ${variant.name}",
            )
            assertEquals("E6B450", palette.accentColor)
        }
    }

    @Test
    fun `forAccent sets correct error color per variant`() {
        val expectedErrors =
            mapOf(
                AyuVariant.MIRAGE to "F27983",
                AyuVariant.DARK to "F26D78",
                AyuVariant.LIGHT to "FF7383",
            )
        for (variant in AyuVariant.entries) {
            val palette = IndentPalette.forAccent("#FFCC66", variant)
            assertEquals(
                expectedErrors[variant],
                palette.errorColor,
                "Error color for ${variant.name} must match expected",
            )
        }
    }

    @Test
    fun `toColorStrings pyramid pattern has symmetric alpha progression`(): Unit =
        runBlocking {
            checkAll(Arb.int(30..255)) { alpha ->
                val palette = IndentPalette(errorColor = "F27983", accentColor = "FFCC66")
                val colors = palette.toColorStrings(alpha)
                // Elements 1-10 are the indent colors (pyramid: steps 1,2,3,4,5,6,5,4,3,2)
                // Indices: 0=step1, 1=step2, 2=step3, 3=step4, 4=step5, 5=step6, 6=step5, 7=step4, 8=step3, 9=step2
                // Symmetric pairs with equal step values:
                val indentColors = colors.drop(1)
                assertEquals(indentColors[1], indentColors[9], "Indices 1 and 9 must match (both step 2)")
                assertEquals(indentColors[2], indentColors[8], "Indices 2 and 8 must match (both step 3)")
                assertEquals(indentColors[3], indentColors[7], "Indices 3 and 7 must match (both step 4)")
                assertEquals(indentColors[4], indentColors[6], "Indices 4 and 6 must match (both step 5)")
            }
        }
}
