package dev.ayuislands.accent

import java.awt.Color
import kotlin.math.pow

/**
 * WCAG 2.1 contrast-ratio-aware foreground picker for Phase 40 chrome surfaces.
 *
 * Spec: https://www.w3.org/TR/WCAG21/#contrast-minimum
 *
 *   ratio = (L_lighter + 0.05) / (L_darker + 0.05)
 *   L = 0.2126*R_lin + 0.7152*G_lin + 0.0722*B_lin
 *   channel_lin = if (c <= 0.03928) c / 12.92 else ((c + 0.055) / 1.055)^2.4
 *
 * Closes VERIFICATION Gap 2 — the legacy static `ColorUtil.isDark` two-way
 * pick (the retired `ChromeTintBlender.contrastForeground`) was insufficient
 * for saturated mid-luminance tinted backgrounds where both white and the
 * Ayu dark foreground could undershoot WCAG AA. The picker sweeps a
 * deterministic palette (white → Ayu dark foreground → black) and returns
 * the first candidate meeting the [TextTarget]'s minimum ratio; if none
 * pass it falls through to the candidate that measured the highest ratio
 * (graceful degradation — the picker NEVER throws).
 */
object WcagForeground {
    /**
     * Minimum contrast ratio per WCAG 2.1 AA.
     *
     * [PRIMARY_TEXT] uses 4.5:1 (normal text). [SECONDARY_TEXT] and [ICON]
     * use 3.0:1 (large text / non-text UI components).
     */
    enum class TextTarget(
        val minRatio: Double,
    ) {
        PRIMARY_TEXT(PRIMARY_MIN_RATIO),
        SECONDARY_TEXT(LARGE_MIN_RATIO),
        ICON(LARGE_MIN_RATIO),
    }

    // Palette sweep order — 0x1F2430 is the Ayu dark foreground literal also
    // used by AccentApplicator's own contrast-foreground pick, so both modules
    // agree on the canonical dark foreground without a cross-import. Black is
    // the last-resort high-contrast candidate for unusually light tinted
    // surfaces.
    private val palette = listOf(Color.WHITE, Color(DARK_FOREGROUND_HEX), Color.BLACK)

    // Phase 40.4 — light-family palette (no BLACK) for chrome surfaces the plugin
    // semantically owns as "tinted dark bands" (status bar foregrounds). Without
    // restricting the palette here, mid-luminance tints (status bar at >= 20%
    // intensity on cyan/lime accents) push the WCAG sweep to BLACK because BLACK
    // passes 4.5:1 there while WHITE drops to ~4:1 — the picker is doing its job,
    // but on a chrome surface meant to read as dark the user expects light text.
    // Pre-Phase 40 status bar fg was pinned to a light tone for exactly this
    // reason; Phase 40 introduced WCAG-aware contrast picking but accidentally
    // regressed the "always-light" contract. Restricting the palette restores it.
    private val lightFamilyPalette = listOf(Color.WHITE, Color(DARK_FOREGROUND_HEX))

    /**
     * Returns the first palette color whose WCAG 2.1 contrast ratio against
     * [bg] meets [target].minRatio. If no candidate passes, returns the
     * candidate with the highest measured ratio — the picker never throws.
     */
    fun pickForeground(
        bg: Color,
        target: TextTarget,
    ): Color = pickFromPalette(bg, target, palette)

    /**
     * Same as [pickForeground] but restricted to the light-family palette
     * (WHITE + Ayu dark foreground, NO black). Use for chrome surfaces the
     * plugin owns as dark tinted bands. See [lightFamilyPalette] for rationale.
     */
    fun pickLightFamilyForeground(
        bg: Color,
        target: TextTarget,
    ): Color = pickFromPalette(bg, target, lightFamilyPalette)

    private fun pickFromPalette(
        bg: Color,
        target: TextTarget,
        candidates: List<Color>,
    ): Color {
        var best = candidates.first()
        var bestRatio = -1.0
        for (candidate in candidates) {
            val ratio = contrastRatio(candidate, bg)
            if (ratio >= target.minRatio) return candidate
            if (ratio > bestRatio) {
                bestRatio = ratio
                best = candidate
            }
        }
        return best
    }

    /** WCAG 2.1 contrast ratio between two colors; order-independent. */
    internal fun contrastRatio(
        a: Color,
        b: Color,
    ): Double {
        val la = relativeLuminance(a)
        val lb = relativeLuminance(b)
        val lighter = if (la >= lb) la else lb
        val darker = if (la >= lb) lb else la
        return (lighter + LUMINANCE_OFFSET) / (darker + LUMINANCE_OFFSET)
    }

    /** WCAG 2.1 relative luminance per the sRGB linearisation + coefficient formula. */
    internal fun relativeLuminance(c: Color): Double {
        val r = channelLin(c.red)
        val g = channelLin(c.green)
        val b = channelLin(c.blue)
        return RED_COEFFICIENT * r + GREEN_COEFFICIENT * g + BLUE_COEFFICIENT * b
    }

    private fun channelLin(component: Int): Double {
        val s = component / MAX_CHANNEL_VALUE
        return if (s <= LOW_GAMMA_THRESHOLD) {
            s / LOW_GAMMA_DIVISOR
        } else {
            ((s + HIGH_GAMMA_OFFSET) / HIGH_GAMMA_DIVISOR).pow(GAMMA_EXPONENT)
        }
    }

    // --- WCAG 2.1 spec constants ---
    // https://www.w3.org/TR/WCAG21/#dfn-relative-luminance

    /** Luminance contribution of the red channel per the spec. */
    private const val RED_COEFFICIENT = 0.2126

    /** Luminance contribution of the green channel per the spec. */
    private const val GREEN_COEFFICIENT = 0.7152

    /** Luminance contribution of the blue channel per the spec. */
    private const val BLUE_COEFFICIENT = 0.0722

    /** sRGB linearisation cutoff below which a linear ramp is used. */
    private const val LOW_GAMMA_THRESHOLD = 0.03928

    /** Linear-ramp divisor applied to channels below [LOW_GAMMA_THRESHOLD]. */
    private const val LOW_GAMMA_DIVISOR = 12.92

    /** sRGB gamma offset applied above [LOW_GAMMA_THRESHOLD] before the exponent. */
    private const val HIGH_GAMMA_OFFSET = 0.055

    /** sRGB gamma divisor applied above [LOW_GAMMA_THRESHOLD]. */
    private const val HIGH_GAMMA_DIVISOR = 1.055

    /** sRGB gamma exponent applied above [LOW_GAMMA_THRESHOLD]. */
    private const val GAMMA_EXPONENT = 2.4

    /** Constant added to both luminances before the contrast-ratio division. */
    private const val LUMINANCE_OFFSET = 0.05

    /** Maximum 8-bit channel value used to normalise sRGB components. */
    private const val MAX_CHANNEL_VALUE = 255.0

    /** WCAG AA minimum ratio for normal-size text. */
    private const val PRIMARY_MIN_RATIO = 4.5

    /** WCAG AA minimum ratio for large text / non-text UI components. */
    private const val LARGE_MIN_RATIO = 3.0

    /** Ayu dark-foreground literal (shared with AccentApplicator's contrast-foreground pick). */
    private const val DARK_FOREGROUND_HEX = 0x1F2430
}
