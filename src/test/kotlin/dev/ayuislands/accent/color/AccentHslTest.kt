package dev.ayuislands.accent.color

import com.intellij.ui.ColorUtil
import dev.ayuislands.accent.AccentHex
import dev.ayuislands.rotation.HslColor
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Locks the [AccentHsl] helper's clamp range, no-op-at-edge semantics, and the
 * HSL-not-HSB invariant.
 */
class AccentHslTest {
    private val epsilon = 0.01f
    private val hueRoundingTolerance = 0.25f

    @Test
    fun `lighten on pure black clamps to MIN_LIGHTNESS`() {
        // L=0 + STEP=0.05 -> coerceIn -> 0.10 (MIN_LIGHTNESS). Returns NEW hex
        // because the post-clamp value differs from input lightness.
        // lighten/darken take and return AccentHex.
        val result = AccentHsl.lighten(AccentHex.unsafeOf("#000000")).value
        val resultLightness = HslColor.fromColor(ColorUtil.fromHex(result)).lightness
        assertTrue(
            abs(resultLightness - AccentHsl.MIN_LIGHTNESS) < epsilon,
            "Expected L=${AccentHsl.MIN_LIGHTNESS}, got L=$resultLightness for hex=$result",
        )
    }

    @Test
    fun `darken on pure white clamps to MAX_LIGHTNESS`() {
        // L=1.0 - STEP=0.95 -> coerceIn -> 0.95 (MAX_LIGHTNESS). Returns NEW hex.
        val result = AccentHsl.darken(AccentHex.unsafeOf("#FFFFFF")).value
        val resultLightness = HslColor.fromColor(ColorUtil.fromHex(result)).lightness
        assertTrue(
            abs(resultLightness - AccentHsl.MAX_LIGHTNESS) < epsilon,
            "Expected L=${AccentHsl.MAX_LIGHTNESS}, got L=$resultLightness for hex=$result",
        )
    }

    @Test
    fun `lighten on pure white clamps step result to MAX_LIGHTNESS`() {
        // L=1.0 + STEP=1.05 -> coerceIn -> 0.95. Returns NEW hex (post-clamp != input L).
        val result = AccentHsl.lighten(AccentHex.unsafeOf("#FFFFFF")).value
        val resultLightness = HslColor.fromColor(ColorUtil.fromHex(result)).lightness
        assertTrue(
            abs(resultLightness - AccentHsl.MAX_LIGHTNESS) < epsilon,
            "Expected L=${AccentHsl.MAX_LIGHTNESS}, got L=$resultLightness for hex=$result",
        )
    }

    @Test
    fun `lighten at MAX_LIGHTNESS edge returns INPUT unchanged (no-op detection)`() {
        // Construct a hex at exactly L=MAX_LIGHTNESS so the post-clamp lightness equals
        // the input lightness — `lighten` returns the original AccentHex so callers can
        // detect a no-op by value equality and surface the balloon hint.
        val ceilingHex = AccentHex.unsafeOf(HslColor.toHex(0f, 0f, AccentHsl.MAX_LIGHTNESS))
        val result = AccentHsl.lighten(ceilingHex)
        assertEquals(ceilingHex, result, "Expected `lighten` at MAX_LIGHTNESS to return input unchanged")
    }

    @Test
    fun `darken at MIN_LIGHTNESS edge returns INPUT unchanged (no-op detection)`() {
        val floorHex = AccentHex.unsafeOf(HslColor.toHex(0f, 0f, AccentHsl.MIN_LIGHTNESS))
        val result = AccentHsl.darken(floorHex)
        assertEquals(floorHex, result, "Expected `darken` at MIN_LIGHTNESS to return input unchanged")
    }

    @Test
    fun `lighten on MIRAGE default accent shifts lightness by STEP`() {
        // `#FFCC66` (MIRAGE default) has L ~ 0.70; result L ~ 0.75.
        val input = AccentHex.unsafeOf("#FFCC66")
        val inputLightness = HslColor.fromColor(ColorUtil.fromHex(input.value)).lightness
        val result = AccentHsl.lighten(input).value
        val resultLightness = HslColor.fromColor(ColorUtil.fromHex(result)).lightness
        assertTrue(
            abs(resultLightness - (inputLightness + AccentHsl.STEP)) < epsilon,
            "Expected delta=+${AccentHsl.STEP}, got from $inputLightness to $resultLightness",
        )
    }

    @Test
    fun `darken-then-lighten roundtrip on MIRAGE default returns within rounding tolerance`() {
        // Per-channel rounding tolerance — one hex byte per channel is ±1/255.
        val input = AccentHex.unsafeOf("#FFCC66")
        val roundtrip = AccentHsl.darken(AccentHsl.lighten(input)).value
        val inputColor = ColorUtil.fromHex(input.value)
        val roundtripColor = ColorUtil.fromHex(roundtrip)
        val tolerance = 2
        assertTrue(
            abs(inputColor.red - roundtripColor.red) <= tolerance,
            "Red roundtrip drift: ${inputColor.red} -> ${roundtripColor.red}",
        )
        assertTrue(
            abs(inputColor.green - roundtripColor.green) <= tolerance,
            "Green roundtrip drift: ${inputColor.green} -> ${roundtripColor.green}",
        )
        assertTrue(
            abs(inputColor.blue - roundtripColor.blue) <= tolerance,
            "Blue roundtrip drift: ${inputColor.blue} -> ${roundtripColor.blue}",
        )
    }

    @Test
    fun `lighten preserves hue and saturation on saturated accent`() {
        val input = HslColor.fromColor(ColorUtil.fromHex("#FFCC66"))
        val result = HslColor.fromColor(ColorUtil.fromHex(AccentHsl.lighten(AccentHex.unsafeOf("#FFCC66")).value))

        assertTrue(abs(result.hue - input.hue) < hueRoundingTolerance, "Hue drifted from ${input.hue} to ${result.hue}")
        assertTrue(
            abs(result.saturation - input.saturation) < epsilon,
            "Saturation drifted from ${input.saturation} to ${result.saturation}",
        )
    }

    @Test
    fun `clamp constants are frozen at STEP=0_05 MIN=0_10 MAX=0_95 (Pattern L)`() {
        // Pattern L source-level regression lock — a casual edit to STEP / MIN /
        // MAX shifts user-visible behaviour silently. Direct assertion against
        // the published constants so the failure message points at the constant
        // that drifted.
        assertEquals(0.05f, AccentHsl.STEP, "AccentHsl.STEP must stay 0.05f per spec")
        assertEquals(0.10f, AccentHsl.MIN_LIGHTNESS, "AccentHsl.MIN_LIGHTNESS must stay 0.10f per spec")
        assertEquals(0.95f, AccentHsl.MAX_LIGHTNESS, "AccentHsl.MAX_LIGHTNESS must stay 0.95f per spec")
    }
}
