package dev.ayuislands.rotation

import dev.ayuislands.accent.AyuVariant
import io.kotest.property.Exhaustive
import io.kotest.property.checkAll
import io.kotest.property.exhaustive.collection
import kotlinx.coroutines.runBlocking
import java.awt.Color
import kotlin.test.Test
import kotlin.test.assertTrue

class ContrastAwareColorGeneratorPropertyTest {
    private fun parseHexToColor(hex: String): Color {
        val stripped = hex.removePrefix("#")
        val red = stripped.substring(0, 2).toInt(16)
        val green = stripped.substring(2, 4).toInt(16)
        val blue = stripped.substring(4, 6).toInt(16)
        return Color(red, green, blue)
    }

    @Test
    fun `generate always returns valid hex color for all variants`() =
        runBlocking {
            checkAll(iterations = 200, Exhaustive.collection(AyuVariant.entries)) { variant ->
                val hex = ContrastAwareColorGenerator.generate(variant)
                assertTrue(
                    hex.matches(Regex("#[0-9A-Fa-f]{6}")),
                    "Generated hex must match #RRGGBB pattern: '$hex' for ${variant.name}",
                )
            }
        }

    @Test
    fun `generate always produces colors with saturation above 0 point 65`() =
        runBlocking {
            checkAll(iterations = 200, Exhaustive.collection(AyuVariant.entries)) { variant ->
                val hex = ContrastAwareColorGenerator.generate(variant)
                val color = parseHexToColor(hex)
                val hsl = HslColor.fromColor(color)
                assertTrue(
                    hsl.saturation >= 0.65f,
                    "Saturation must be >= 0.65 for ${variant.name}: " +
                        "got ${hsl.saturation} from hex=$hex",
                )
            }
        }

    @Test
    fun `generate produces colors with appropriate lightness for dark variants`() =
        runBlocking {
            val darkVariants = listOf(AyuVariant.MIRAGE, AyuVariant.DARK)
            checkAll(iterations = 200, Exhaustive.collection(darkVariants)) { variant ->
                val hex = ContrastAwareColorGenerator.generate(variant)
                val color = parseHexToColor(hex)
                val hsl = HslColor.fromColor(color)
                assertTrue(
                    hsl.lightness >= 0.55f,
                    "Dark variant lightness must be >= 0.55 for ${variant.name}: " +
                        "got ${hsl.lightness} from hex=$hex",
                )
                assertTrue(
                    hsl.lightness <= 0.90f,
                    "Dark variant lightness must be <= 0.90 for ${variant.name}: " +
                        "got ${hsl.lightness} from hex=$hex",
                )
            }
        }

    @Test
    fun `generate produces colors with appropriate lightness for light variant`() =
        runBlocking {
            checkAll(iterations = 200, Exhaustive.collection(listOf(AyuVariant.LIGHT))) { variant ->
                val hex = ContrastAwareColorGenerator.generate(variant)
                val color = parseHexToColor(hex)
                val hsl = HslColor.fromColor(color)
                assertTrue(
                    hsl.lightness >= 0.20f,
                    "Light variant lightness must be >= 0.20: " +
                        "got ${hsl.lightness} from hex=$hex",
                )
                assertTrue(
                    hsl.lightness <= 0.50f,
                    "Light variant lightness must be <= 0.50: " +
                        "got ${hsl.lightness} from hex=$hex",
                )
            }
        }

    @Test
    fun `generate produces varied hues across many calls`() =
        runBlocking {
            val hues = mutableSetOf<Int>()
            repeat(100) {
                val hex = ContrastAwareColorGenerator.generate(AyuVariant.MIRAGE)
                val color = parseHexToColor(hex)
                val hsl = HslColor.fromColor(color)
                hues.add(hsl.hue.toInt() / 30)
            }
            assertTrue(
                hues.size >= 4,
                "100 generated colors should span at least 4 hue sectors (30-degree buckets), " +
                    "got ${hues.size} sectors: $hues",
            )
        }
}
