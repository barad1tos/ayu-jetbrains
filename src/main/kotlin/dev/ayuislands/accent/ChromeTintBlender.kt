package dev.ayuislands.accent

import java.awt.Color
import javax.swing.UIManager

/**
 * Pure color-math foundation for Phase 40 chrome tinting.
 *
 * Every chrome accent element (`StatusBar`, `MainToolbar`, `ToolWindowStripe`,
 * `NavBar`, `PanelBorder`) consumes [blend] so the blend math lives in exactly
 * one place. Lifted from
 * [dev.ayuislands.accent.elements.SearchResultsElement.blendWithBackground]
 * per phase decisions D-04, D-05, D-06.
 *
 * Foreground picking lives in [WcagForeground] — the legacy two-way
 * `contrastForeground(tinted)` helper was retired on plan 40-10 after all five
 * chrome elements migrated to [WcagForeground.pickForeground].
 */
object ChromeTintBlender {
    private const val MIN_INTENSITY = 0
    private const val MAX_INTENSITY = 100
    private const val INTENSITY_TO_RATIO = 100f
    private const val PANEL_BACKGROUND_KEY = "Panel.background"

    /**
     * Scaling factor applied to the accent's HSB saturation when synthesising
     * the per-surface tint target. `1.0` preserves the accent's chroma exactly;
     * values below `1.0` desaturate the target so tinted chrome reads closer to
     * the stock neutral.
     *
     * Calibrated to `1.0` after Task 1 Test H-7 (`saturation of output at
     * intensity 50 stays within damped accent-saturation band`) — the Ayu Cyan
     * reference accent still lands inside the permitted band without damping.
     * Tunable per VERIFICATION Gap 1 if a future accent palette pushes
     * saturation past the perceptual ceiling.
     */
    private const val SATURATION_DAMP = 1.0f

    /**
     * Ratio of full HSB-brightness travel applied per intensity step.
     *
     * Pure `B` preservation from plan 40-09 locked the luma hierarchy but made
     * the intensity slider imperceptible on dark Mirage bases
     * (`StatusBar.background` sits at ~19% brightness — saturation-only deltas
     * at that low B are below perceptual threshold). Pulling brightness halfway
     * toward the accent (`0.5`) restores slider visibility at intensity ≥ 40
     * while keeping the relative order of surface luminances at intensity ≤ 40
     * where the accent pull barely shifts the base. At intensity=100 the output
     * is `bg.B + (accent.B - bg.B) * 0.5` — halfway, not full accent brightness,
     * so the chrome still reads slightly darker than a pure accent fill.
     *
     * Lowering to 0.0 restores 40-09 behaviour (invisible slider on dark bases);
     * raising to 1.0 matches VS Code Peacock (full accent brightness at
     * intensity=100, stronger pop but breaks luma hierarchy).
     */
    private const val BRIGHTNESS_PULL_RATIO = 0.5f

    /**
     * Luma-preserving hue replacement between the stock theme color behind
     * [baseKey] and the resolved [accent].
     *
     * Algorithm (VERIFICATION Gap 1, Phase 40-09) — HSB-space blend rather than
     * per-channel RGB lerp so every chrome surface receives the SAME accent
     * hue regardless of how its stock base hue drifts:
     *  1. Resolve `background` via the fallback chain
     *     ([baseKey] → [PANEL_BACKGROUND_KEY] → [accent]).
     *  2. Clamp [intensityPercent] to `[0, 100]`.
     *  3. Extract the accent's hue + saturation (`accent.H`, `accent.S`) and
     *     the background's hue + saturation + brightness (`background.H`,
     *     `background.S`, `background.B`) via [Color.RGBtoHSB].
     *  4. Synthesise the output via [Color.getHSBColor]:
     *       - **H** replaced outright by `accent.H` (uniform hue across surfaces)
     *       - **S** lerped from `background.S` toward `accent.S × SATURATION_DAMP`
     *         by `intensity / 100` (ramps chroma with the slider)
     *       - **B** lerped from `background.B` toward `accent.B` by
     *         `(intensity / 100) × BRIGHTNESS_PULL_RATIO` (pulls halfway toward
     *         accent brightness, restoring slider visibility on dark bases
     *         while preserving most of the luma hierarchy)
     *  5. Identity short-circuit at `intensity == 0` returns `background`
     *     per-channel — ensures the existing backward-compat `blend at
     *     intensity 0 returns the base color unchanged` assertion holds and
     *     preserves base chromaticity when the tint is disabled.
     *
     * Output is always opaque (alpha=255) per D-05 — translucent chrome would
     * bleed through to native OS surfaces. The uniform-hue invariant is locked
     * by [dev.ayuislands.accent.ChromeTintBlenderHueUniformityTest].
     *
     * @param accent resolved accent color (alpha channel ignored)
     * @param baseKey UIManager key naming the stock theme color to tint
     * @param intensityPercent 0-100 mix ratio; out-of-range values clamp without throwing
     * @return opaque [Color] sharing the accent hue at the requested blend ratio
     */
    fun blend(
        accent: Color,
        baseKey: String,
        intensityPercent: Int,
    ): Color {
        val clamped = intensityPercent.coerceIn(MIN_INTENSITY, MAX_INTENSITY)
        val background =
            UIManager.getColor(baseKey)
                ?: UIManager.getColor(PANEL_BACKGROUND_KEY)
                ?: accent

        // Identity short-circuit at 0: return the background per-channel so
        // callers see the exact stock color when the slider is off. This
        // preserves the existing `intensity=0 returns base per channel` and
        // alpha=255 (D-05) invariants that ChromeTintBlenderTest pins.
        if (clamped == MIN_INTENSITY) {
            return Color(background.red, background.green, background.blue)
        }

        val accentHsb = Color.RGBtoHSB(accent.red, accent.green, accent.blue, null)
        val backgroundHsb = Color.RGBtoHSB(background.red, background.green, background.blue, null)

        val ratio = clamped.toFloat() / INTENSITY_TO_RATIO
        // HSB-space lerp: hue is replaced outright by the accent's hue so every
        // surface shares the same tint hue (uniform-hue invariant). Saturation
        // ramps from the background's chroma toward `accent.S × damp` at the
        // requested intensity. Brightness pulls halfway toward the accent's
        // brightness (weighted by BRIGHTNESS_PULL_RATIO) — restores slider
        // visibility on dark Mirage bases while mostly preserving the luma
        // hierarchy at default intensity ≤ 40.
        val outputHue = accentHsb[0]
        val outputSaturation = backgroundHsb[1] + (accentHsb[1] * SATURATION_DAMP - backgroundHsb[1]) * ratio
        val outputBrightness =
            backgroundHsb[2] + (accentHsb[2] - backgroundHsb[2]) * ratio * BRIGHTNESS_PULL_RATIO

        // getHSBColor returns an opaque Color; no 4-arg ctor needed (D-05).
        return Color.getHSBColor(outputHue, outputSaturation.coerceIn(0f, 1f), outputBrightness)
    }
}
