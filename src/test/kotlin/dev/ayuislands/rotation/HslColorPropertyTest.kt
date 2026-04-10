package dev.ayuislands.rotation

import io.kotest.property.Arb
import io.kotest.property.arbitrary.float
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import java.awt.Color
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

class HslColorPropertyTest {
    @Test
    fun `toColor then fromColor round-trips within tolerance`(): Unit =
        runBlocking {
            checkAll(
                Arb.float(min = 0f, max = 359.9f),
                Arb.float(min = 0.25f, max = 1f),
                Arb.float(min = 0.2f, max = 0.85f),
            ) { hue, saturation, lightness ->
                val color = HslColor.toColor(hue, saturation, lightness)
                val (roundTripHue, roundTripSat, roundTripLight) = HslColor.fromColor(color)

                val hueDiff = minOf(abs(roundTripHue - hue), 360f - abs(roundTripHue - hue))
                assertTrue(
                    hueDiff <= 2.5f,
                    "Hue round-trip: input=$hue, output=$roundTripHue, diff=$hueDiff",
                )
                assertTrue(
                    abs(roundTripSat - saturation) <= 0.02f,
                    "Saturation round-trip: input=$saturation, output=$roundTripSat",
                )
                assertTrue(
                    abs(roundTripLight - lightness) <= 0.02f,
                    "Lightness round-trip: input=$lightness, output=$roundTripLight",
                )
            }
        }

    @Test
    fun `toColor always produces RGB values in 0 to 255 range`(): Unit =
        runBlocking {
            checkAll(
                Arb.float(min = 0f, max = 360f),
                Arb.float(min = 0f, max = 1f),
                Arb.float(min = 0f, max = 1f),
            ) { hue, saturation, lightness ->
                val color = HslColor.toColor(hue, saturation, lightness)
                assertTrue(color.red in 0..255, "Red out of range: ${color.red}")
                assertTrue(color.green in 0..255, "Green out of range: ${color.green}")
                assertTrue(color.blue in 0..255, "Blue out of range: ${color.blue}")
            }
        }

    @Test
    fun `fromColor always returns hue in 0 to 360, saturation and lightness in 0 to 1`(): Unit =
        runBlocking {
            checkAll(
                Arb.float(min = 0f, max = 360f),
                Arb.float(min = 0f, max = 1f),
                Arb.float(min = 0f, max = 1f),
            ) { hue, saturation, lightness ->
                val color = HslColor.toColor(hue, saturation, lightness)
                val (resultHue, resultSat, resultLight) = HslColor.fromColor(color)

                assertTrue(resultHue >= 0f, "Hue must be >= 0: $resultHue")
                assertTrue(resultHue < 360f, "Hue must be < 360: $resultHue")
                assertTrue(resultSat in 0f..1f, "Saturation must be in [0,1]: $resultSat")
                assertTrue(resultLight in 0f..1f, "Lightness must be in [0,1]: $resultLight")
            }
        }

    @Test
    fun `toHex always produces valid 7-char hex string`(): Unit =
        runBlocking {
            checkAll(
                Arb.float(min = 0f, max = 360f),
                Arb.float(min = 0f, max = 1f),
                Arb.float(min = 0f, max = 1f),
            ) { hue, saturation, lightness ->
                val hex = HslColor.toHex(hue, saturation, lightness)
                assertTrue(
                    hex.matches(Regex("#[0-9A-Fa-f]{6}")),
                    "Hex must match #RRGGBB pattern: $hex",
                )
            }
        }

    @Test
    fun `fromColor of constructed Color matches direct constructor RGB`(): Unit =
        runBlocking {
            checkAll(
                Arb.float(min = 0f, max = 360f),
                Arb.float(min = 0f, max = 1f),
                Arb.float(min = 0f, max = 1f),
            ) { hue, saturation, lightness ->
                val color = HslColor.toColor(hue, saturation, lightness)
                val reconstructed =
                    HslColor.toColor(
                        HslColor.fromColor(color).hue,
                        HslColor.fromColor(color).saturation,
                        HslColor.fromColor(color).lightness,
                    )

                assertTrue(
                    abs(color.red - reconstructed.red) <= 1,
                    "Red diff: ${color.red} vs ${reconstructed.red}",
                )
                assertTrue(
                    abs(color.green - reconstructed.green) <= 1,
                    "Green diff: ${color.green} vs ${reconstructed.green}",
                )
                assertTrue(
                    abs(color.blue - reconstructed.blue) <= 1,
                    "Blue diff: ${color.blue} vs ${reconstructed.blue}",
                )
            }
        }

    @Test
    fun `zero saturation produces grey regardless of hue`(): Unit =
        runBlocking {
            checkAll(Arb.float(min = 0f, max = 360f)) { hue ->
                val color = HslColor.toColor(hue, 0f, 0.5f)
                val expected = Color(128, 128, 128)
                assertTrue(
                    abs(color.red - expected.red) <= 1 &&
                        abs(color.green - expected.green) <= 1 &&
                        abs(color.blue - expected.blue) <= 1,
                    "Zero saturation with hue=$hue should produce ~grey, got $color",
                )
            }
        }
}
