package dev.ayuislands.syntax

import java.awt.Color

/**
 * Pure RGB-blend utility for Phase 50 continuous-intensity syntax styling.
 *
 * The blend math is `bg + factor * (fg - bg)` where `factor = intensity / 100`,
 * clamped per channel to `[0, 255]` after the float→int conversion. Identity
 * at `intensity == 100`, near-baseline-bg at `intensity == 10`, extrapolated
 * past the baseline_fg at `intensity > 100`. Alpha is always sourced from the
 * baseline_fg — the blender never modulates transparency.
 *
 * Per-variant editor-background fallback constants ([EDITOR_BG_MIRAGE_HEX],
 * [EDITOR_BG_DARK_HEX], [EDITOR_BG_LIGHT_HEX]) live here as a single source
 * of truth (D-12) so callers that hit the R-1 `defaultBackground=Color.WHITE`
 * landmine can resolve a safe per-variant fallback without reading
 * `EditorColorsManager`.
 *
 * Platform-dependency free — every entry point is a pure function over
 * [java.awt.Color]. No coroutine context, no IDE singleton.
 *
 * Analog: [dev.ayuislands.accent.ChromeTintBlender] (HSB-space blender for
 * chrome surfaces). The two blenders share the identity short-circuit + clamp
 * idiom but operate in different color spaces — chrome tinting preserves luma
 * hierarchy via HSB lerp; syntax intensity preserves channel relationships
 * via per-channel RGB lerp.
 *
 * See Phase 50 RESEARCH OQ-02 (editor-bg landmine), CONTEXT D-03 (intensity
 * range 10–150 default 100), D-04 (RGB-blend transform with extrapolation).
 */
object RgbBlend {
    const val MIN_INTENSITY = 10
    const val DEFAULT_INTENSITY = 100
    const val MAX_INTENSITY = 150

    const val EDITOR_BG_MIRAGE_HEX = "#1F2430"
    const val EDITOR_BG_DARK_HEX = "#0D1017"
    const val EDITOR_BG_LIGHT_HEX = "#FCFCFC"

    private const val INTENSITY_DIVISOR = 100.0
    private const val CHANNEL_MIN = 0
    private const val CHANNEL_MAX = 255

    /**
     * Resolves the per-variant editor background fallback used when
     * `EditorColorsScheme.defaultBackground` returns the platform sentinel
     * (R-1 — most commonly `Color.WHITE` on dark variants during early init).
     *
     * Mirage / Dark / Light variants map to their canonical hex constants;
     * any unknown variant tag falls back to the Mirage hex — the same safe
     * default Phase 49 `SyntaxModeService.resolveOverlayVariant` uses, so the
     * downstream applicator does not see a brand-new color for a brand-new
     * variant name.
     */
    fun fallbackEditorBgFor(variantTag: String): Color =
        when (variantTag) {
            "Dark" -> Color.decode(EDITOR_BG_DARK_HEX)
            "Light" -> Color.decode(EDITOR_BG_LIGHT_HEX)
            else -> Color.decode(EDITOR_BG_MIRAGE_HEX)
        }

    /**
     * Blends `baselineFg` toward `editorBg` by `intensity / 100`. The intensity
     * is clamped to `[MIN_INTENSITY, MAX_INTENSITY]` before blending; values
     * below 10 collapse to 10 (near-bg), values above 150 collapse to 150
     * (50 % extrapolation past `baselineFg`). At `intensity == 100` the result
     * is `baselineFg` verbatim (identity short-circuit). Alpha is copied from
     * `baselineFg` — the editor-bg alpha is discarded so a translucent
     * baseline keeps its opacity through the blend.
     */
    fun blend(
        baselineFg: Color,
        editorBg: Color,
        intensity: Int,
    ): Color {
        val clamped = intensity.coerceIn(MIN_INTENSITY, MAX_INTENSITY)
        if (clamped == DEFAULT_INTENSITY) {
            return Color(baselineFg.red, baselineFg.green, baselineFg.blue, baselineFg.alpha)
        }
        val factor = clamped / INTENSITY_DIVISOR
        val red = blendChannel(baselineFg.red, editorBg.red, factor)
        val green = blendChannel(baselineFg.green, editorBg.green, factor)
        val blue = blendChannel(baselineFg.blue, editorBg.blue, factor)
        return Color(red, green, blue, baselineFg.alpha)
    }

    private fun blendChannel(
        fg: Int,
        bg: Int,
        factor: Double,
    ): Int = (bg + factor * (fg - bg)).toInt().coerceIn(CHANNEL_MIN, CHANNEL_MAX)
}
