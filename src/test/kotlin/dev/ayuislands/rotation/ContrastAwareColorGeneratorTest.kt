package dev.ayuislands.rotation

import dev.ayuislands.accent.AyuVariant
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.awt.Color

class ContrastAwareColorGeneratorTest {
    @Test
    fun `generate produces bright accents for DARK variant`() {
        repeat(100) {
            val hex = ContrastAwareColorGenerator.generate(AyuVariant.DARK)
            val (_, _, lightness) = HslColor.fromColor(Color.decode(hex))
            assertTrue(lightness in 0.55f..0.90f) {
                "DARK lightness $lightness out of range for hex $hex"
            }
        }
    }

    @Test
    fun `generate produces bright accents for MIRAGE variant`() {
        repeat(100) {
            val hex = ContrastAwareColorGenerator.generate(AyuVariant.MIRAGE)
            val (_, _, lightness) = HslColor.fromColor(Color.decode(hex))
            assertTrue(lightness in 0.55f..0.90f) {
                "MIRAGE lightness $lightness out of range for hex $hex"
            }
        }
    }

    @Test
    fun `generate produces dark accents for LIGHT variant`() {
        repeat(100) {
            val hex = ContrastAwareColorGenerator.generate(AyuVariant.LIGHT)
            val (_, _, lightness) = HslColor.fromColor(Color.decode(hex))
            assertTrue(lightness in 0.20f..0.50f) {
                "LIGHT lightness $lightness out of range for hex $hex"
            }
        }
    }

    @Test
    fun `all generated colors have high saturation`() {
        for (variant in AyuVariant.entries) {
            repeat(50) {
                val hex = ContrastAwareColorGenerator.generate(variant)
                val (_, saturation, _) = HslColor.fromColor(Color.decode(hex))
                assertTrue(saturation >= 0.65f) {
                    "Saturation $saturation too low for $variant hex $hex"
                }
            }
        }
    }

    @Test
    fun `all generated colors match hex pattern`() {
        for (variant in AyuVariant.entries) {
            repeat(50) {
                val hex = ContrastAwareColorGenerator.generate(variant)
                assertTrue(hex.matches(Regex("#[0-9A-F]{6}"))) {
                    "Invalid hex format: $hex"
                }
            }
        }
    }
}
