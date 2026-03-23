package dev.ayuislands.rotation

import java.awt.Color
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

object HslColor {
    private const val DEGREES_PER_SEGMENT = 60f
    private const val FULL_CIRCLE = 360f
    private const val MAX_RGB = 255f
    private const val MAX_RGB_INT = 255
    private const val SEGMENT_2 = 2f
    private const val SEGMENT_3 = 3f
    private const val SEGMENT_4 = 4f
    private const val SEGMENT_5 = 5f
    private const val HALF = 0.5f
    private const val HUE_SECTOR_COUNT = 6f

    fun toColor(
        hue: Float,
        saturation: Float,
        lightness: Float,
    ): Color {
        val chroma = (1f - abs(SEGMENT_2 * lightness - 1f)) * saturation
        val hueSegment = hue / DEGREES_PER_SEGMENT
        val x = chroma * (1f - abs(hueSegment % SEGMENT_2 - 1f))

        val (r1, g1, b1) =
            when {
                hueSegment < 1f -> Triple(chroma, x, 0f)
                hueSegment < SEGMENT_2 -> Triple(x, chroma, 0f)
                hueSegment < SEGMENT_3 -> Triple(0f, chroma, x)
                hueSegment < SEGMENT_4 -> Triple(0f, x, chroma)
                hueSegment < SEGMENT_5 -> Triple(x, 0f, chroma)
                else -> Triple(chroma, 0f, x)
            }

        val m = lightness - chroma / SEGMENT_2
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

    fun fromColor(color: Color): Triple<Float, Float, Float> {
        val r = color.red / MAX_RGB
        val g = color.green / MAX_RGB
        val b = color.blue / MAX_RGB

        val maxComponent = max(r, max(g, b))
        val minComponent = min(r, min(g, b))
        val delta = maxComponent - minComponent

        val lightness = (maxComponent + minComponent) / SEGMENT_2

        if (delta == 0f) {
            return Triple(0f, 0f, lightness)
        }

        val saturation =
            if (lightness <= HALF) {
                delta / (maxComponent + minComponent)
            } else {
                delta / (SEGMENT_2 - maxComponent - minComponent)
            }

        val hue =
            when (maxComponent) {
                r -> DEGREES_PER_SEGMENT * (((g - b) / delta) % HUE_SECTOR_COUNT)
                g -> DEGREES_PER_SEGMENT * (((b - r) / delta) + SEGMENT_2)
                else -> DEGREES_PER_SEGMENT * (((r - g) / delta) + SEGMENT_4)
            }

        val normalizedHue = if (hue < 0f) hue + FULL_CIRCLE else hue

        return Triple(normalizedHue, saturation, lightness)
    }
}
