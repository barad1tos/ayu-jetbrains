package dev.ayuislands.accent.color

import com.intellij.ui.ColorUtil
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
 * edge, [lighten] / [darken] return the INPUT hex unchanged so callers can
 * detect a no-op by hex equality and surface an "Already at maximum /
 * minimum brightness" balloon hint (helper-only today; the balloon UI lives
 * in a future release).
 */
object AccentHsl {
    internal const val STEP = 0.05f
    internal const val MIN_LIGHTNESS = 0.10f
    internal const val MAX_LIGHTNESS = 0.95f

    fun lighten(hex: String): String = adjust(hex, +STEP)

    fun darken(hex: String): String = adjust(hex, -STEP)

    private fun adjust(
        hex: String,
        delta: Float,
    ): String {
        val color = ColorUtil.fromHex(hex)
        val hsl = HslColor.fromColor(color)
        val newLightness = (hsl.lightness + delta).coerceIn(MIN_LIGHTNESS, MAX_LIGHTNESS)
        if (newLightness == hsl.lightness) return hex
        return HslColor.toHex(hsl.hue, hsl.saturation, newLightness)
    }
}
