package dev.ayuislands.vcs

import java.awt.Color

/**
 * Pure HSB-lerp helper that mixes a stock 2.6.2 XML baseline color toward a
 * per-category vibrant target by the user-configured [VcsIntensity] percent.
 *
 * Unlike [dev.ayuislands.accent.ChromeTintBlender], which replaces the base
 * hue with a single accent hue across all chrome surfaces (uniform-hue
 * invariant), this blender lerps between two specific colors and preserves
 * the natural hue ramp of VCS surfaces: diff-modified blue stays blue-family,
 * file-status-added green stays green-family, deleted-red stays red-family.
 * That keeps the user's intensity slider purely a saturation/brightness dial,
 * not a hue-shift dial.
 *
 * Invariants enforced by [VcsColorBlenderTest]:
 *  - intensity=0 → base per-channel, alpha from base
 *  - intensity=100 → target per-channel, alpha from base
 *  - intensity=50 → midpoint via HSB lerp, alpha from base
 *  - alpha ALWAYS sourced from base (translucent SELECTION_BACKGROUND keys
 *    must not lose their transparency when "vibrified")
 *  - hue uses shortest-arc lerp (red↔magenta crossing 0/360 stays on the
 *    short side, doesn't pass through the spectrum's full range)
 */
object VcsColorBlender {
    private const val MIN_INTENSITY: Int = VcsIntensity.MIN
    private const val MAX_INTENSITY: Int = VcsIntensity.MAX
    private const val INTENSITY_TO_RATIO: Float = 100f

    /**
     * Half-rotation threshold on the normalised hue circle (`[0, 1]`).
     * When `|targetHue - baseHue| > 0.5` the shortest arc goes the OTHER
     * way around the wraparound (e.g. red→magenta via 0/1, not via the
     * full spectrum), so the lerp adjusts one operand by ±1.0 before mixing.
     */
    private const val HUE_HALF_ROTATION: Float = 0.5f
    private const val HUE_FULL_ROTATION: Float = 1.0f

    /**
     * Lerps [base] toward [target] in HSB space by [intensity] percent.
     *
     * @param base stock XML baseline color (Muted preset value)
     * @param target vibrant per-category endpoint (hand-tuned per category by
     *   the applier; the blender stays agnostic to what target means)
     * @param intensity typed slider position — clamped at construction
     * @return blended [Color] sharing [base]'s alpha at the requested mix
     */
    fun blend(
        base: Color,
        target: Color,
        intensity: VcsIntensity,
    ): Color {
        val clamped = intensity.percent.coerceIn(MIN_INTENSITY, MAX_INTENSITY)

        if (clamped == MIN_INTENSITY) {
            // Identity short-circuit: no target read at all so a future caller
            // can safely pass `target = null` semantics via a sentinel color
            // without affecting the result at intensity=0.
            return Color(base.red, base.green, base.blue, base.alpha)
        }

        if (clamped == MAX_INTENSITY) {
            // Endpoint short-circuit: return target per-channel with base alpha
            // so translucent baselines stay translucent at peak intensity.
            return Color(target.red, target.green, target.blue, base.alpha)
        }

        val ratio = clamped.toFloat() / INTENSITY_TO_RATIO
        val baseHsb = Color.RGBtoHSB(base.red, base.green, base.blue, null)
        val targetHsb = Color.RGBtoHSB(target.red, target.green, target.blue, null)

        val hue = lerpShortestArcHue(baseHsb[0], targetHsb[0], ratio)
        val saturation = (baseHsb[1] + (targetHsb[1] - baseHsb[1]) * ratio).coerceIn(0f, 1f)
        val brightness = (baseHsb[2] + (targetHsb[2] - baseHsb[2]) * ratio).coerceIn(0f, 1f)

        val rgb = Color.HSBtoRGB(hue, saturation, brightness)
        val red = rgb shr RED_SHIFT and CHANNEL_MASK
        val green = rgb shr GREEN_SHIFT and CHANNEL_MASK
        val blue = rgb and CHANNEL_MASK
        return Color(red, green, blue, base.alpha)
    }

    /**
     * Lerps two hues on the normalised `[0, 1]` HSB hue circle along the
     * shortest arc.
     *
     * Java's [Color.RGBtoHSB] returns hue in `[0, 1]` (not degrees), with
     * `0.0` and `1.0` both representing red. A naive `base + (target-base)*r`
     * lerp from red (0.0) toward magenta (0.83) would sweep through orange,
     * yellow, green, cyan, blue — the long way around. Detecting that the
     * delta exceeds half a rotation and adjusting one endpoint by ±1.0
     * keeps the lerp on the short arc.
     */
    private fun lerpShortestArcHue(
        baseHue: Float,
        targetHue: Float,
        ratio: Float,
    ): Float {
        val delta = targetHue - baseHue
        val adjustedDelta =
            when {
                delta > HUE_HALF_ROTATION -> delta - HUE_FULL_ROTATION
                delta < -HUE_HALF_ROTATION -> delta + HUE_FULL_ROTATION
                else -> delta
            }
        val raw = baseHue + adjustedDelta * ratio
        // Wrap the result back into [0, 1). Adding HUE_FULL_ROTATION before mod
        // handles the case where the lerp crossed through 0.0 going backwards.
        return ((raw % HUE_FULL_ROTATION) + HUE_FULL_ROTATION) % HUE_FULL_ROTATION
    }

    private const val CHANNEL_MASK: Int = 0xFF
    private const val RED_SHIFT: Int = 16
    private const val GREEN_SHIFT: Int = 8
}
