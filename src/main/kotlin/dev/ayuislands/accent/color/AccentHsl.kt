package dev.ayuislands.accent.color

import com.intellij.ui.ColorUtil
import dev.ayuislands.accent.AccentHex
import dev.ayuislands.rotation.HslColor

/**
 * HSL-lightness adjustment helpers for the quick-action Lighter / Darker
 * buttons.
 *
 * Uses [HslColor] (in-repo) instead of the platform's HSB-brightness shifters
 * because the user-expected ramp is HSL-lightness, not HSB. HSB-brightness
 * shifts the V channel and bends saturation curves on saturated hues;
 * HSL-lightness keeps hue + saturation invariant and produces the linear
 * lighten/darken ramp users expect.
 *
 * Lightness is clamped to `[0.10, 0.95]` so repeated lighten never collapses
 * to pure white and repeated darken never collapses to pure black. At a clamp
 * edge, [lighten] / [darken] return the INPUT [AccentHex] unchanged so callers
 * can detect a no-op by [AccentHex] equality and surface an "Already at
 * maximum / minimum brightness" balloon hint (helper-only today; the balloon
 * UI lives in a future release).
 *
 * Pattern K — both parameter and return are [AccentHex] so callers can't
 * accidentally feed an invalid `String` into the HSL chain.
 */
object AccentHsl {
    internal const val STEP = 0.05f
    internal const val MIN_LIGHTNESS = 0.10f
    internal const val MAX_LIGHTNESS = 0.95f

    fun lighten(hex: AccentHex): AccentHex = adjust(hex, +STEP)

    fun darken(hex: AccentHex): AccentHex = adjust(hex, -STEP)

    private fun adjust(
        hex: AccentHex,
        delta: Float,
    ): AccentHex {
        val color = ColorUtil.fromHex(hex.value)
        val hsl = HslColor.fromColor(color)
        val newLightness = (hsl.lightness + delta).coerceIn(MIN_LIGHTNESS, MAX_LIGHTNESS)
        if (newLightness == hsl.lightness) return hex
        // `HslColor.toHex` produces a validated `#RRGGBB` literal; safe to wrap
        // via `unsafeOf` (Pattern K escape hatch for known-good output).
        return AccentHex.unsafeOf(HslColor.toHex(hsl.hue, hsl.saturation, newLightness))
    }
}
