package dev.ayuislands.rotation

import dev.ayuislands.accent.AyuVariant
import kotlin.random.Random

object ContrastAwareColorGenerator {
    private const val MIN_SATURATION = 0.70f
    private const val MAX_SATURATION = 1.00f

    private const val DARK_MIN_LIGHTNESS = 0.60f
    private const val DARK_MAX_LIGHTNESS = 0.85f

    private const val LIGHT_MIN_LIGHTNESS = 0.25f
    private const val LIGHT_MAX_LIGHTNESS = 0.45f

    private const val HUE_RANGE = 360f

    fun generate(variant: AyuVariant): String {
        val hue = Random.nextFloat() * HUE_RANGE
        val saturation = MIN_SATURATION + Random.nextFloat() * (MAX_SATURATION - MIN_SATURATION)

        val (minLightness, maxLightness) =
            when (variant) {
                AyuVariant.MIRAGE, AyuVariant.DARK -> DARK_MIN_LIGHTNESS to DARK_MAX_LIGHTNESS
                AyuVariant.LIGHT -> LIGHT_MIN_LIGHTNESS to LIGHT_MAX_LIGHTNESS
            }

        val lightness = minLightness + Random.nextFloat() * (maxLightness - minLightness)
        return HslColor.toHex(hue, saturation, lightness)
    }
}
