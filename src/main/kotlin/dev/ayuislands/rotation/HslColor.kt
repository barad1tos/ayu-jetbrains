package dev.ayuislands.rotation

import java.awt.Color
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

data class HslComponents(
    val hue: Float,
    val saturation: Float,
    val lightness: Float,
)

object HslColor {
    private const val DEGREES_PER_SEGMENT = 60f
    private const val FULL_CIRCLE = 360f
    private const val MAX_RGB = 255f
    private const val MAX_RGB_INT = 255
    private const val HALF = 0.5f
    private const val HUE_SECTOR_COUNT = 6f

    fun toColor(
        hue: Float,
        saturation: Float,
        lightness: Float,
    ): Color {
        require(hue in 0f..FULL_CIRCLE) { "hue must be in [0, 360]: $hue" }
        require(saturation in 0f..1f) { "saturation must be in [0, 1]: $saturation" }
        require(lightness in 0f..1f) { "lightness must be in [0, 1]: $lightness" }

        val chroma = (1f - abs(2f * lightness - 1f)) * saturation
        val hueSegment = hue / DEGREES_PER_SEGMENT
        val x = chroma * (1f - abs(hueSegment % 2f - 1f))

        val (r1, g1, b1) =
            when {
                hueSegment < 1f -> Triple(chroma, x, 0f)
                hueSegment < 2f -> Triple(x, chroma, 0f)
                hueSegment < 3f -> Triple(0f, chroma, x)
                hueSegment < 4f -> Triple(0f, x, chroma)
                hueSegment < 5f -> Triple(x, 0f, chroma)
                else -> Triple(chroma, 0f, x)
            }

        val m = lightness - chroma / 2f
        return Color(
            ((r1 + m) * MAX_RGB).roundToInt().coerceIn(0, MAX_RGB_INT),
            ((g1 + m) * MAX_RGB).roundToInt().coerceIn(0, MAX_RGB_INT),
            ((b1 + m) * MAX_RGB).roundToInt().coerceIn(0, MAX_RGB_INT),
        )
    }

    fun toHex(
        hue: Float,
        saturation: Float,
        lightness: Float,
    ): String {
        val color = toColor(hue, saturation, lightness)
        return "#%02X%02X%02X".format(color.red, color.green, color.blue)
    }

    fun fromColor(color: Color): HslComponents {
        val r = color.red / MAX_RGB
        val g = color.green / MAX_RGB
        val b = color.blue / MAX_RGB

        val maxComponent = max(r, max(g, b))
        val minComponent = min(r, min(g, b))
        val delta = maxComponent - minComponent

        val lightness = (maxComponent + minComponent) / 2f

        if (delta == 0f) {
            return HslComponents(0f, 0f, lightness)
        }

        val saturation =
            if (lightness <= HALF) {
                delta / (maxComponent + minComponent)
            } else {
                delta / (2f - maxComponent - minComponent)
            }

        val hue =
            when (maxComponent) {
                r -> DEGREES_PER_SEGMENT * (((g - b) / delta) % HUE_SECTOR_COUNT)
                g -> DEGREES_PER_SEGMENT * (((b - r) / delta) + 2f)
                else -> DEGREES_PER_SEGMENT * (((r - g) / delta) + 4f)
            }

        val normalizedHue = if (hue < 0f) hue + FULL_CIRCLE else hue

        return HslComponents(normalizedHue, saturation, lightness)
    }
}
