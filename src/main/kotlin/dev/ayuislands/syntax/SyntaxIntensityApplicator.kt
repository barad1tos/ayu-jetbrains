package dev.ayuislands.syntax

import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.editor.markup.TextAttributes
import dev.ayuislands.accent.color.AccentHsl
import dev.ayuislands.rotation.HslColor
import java.awt.Color
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

/**
 * Pure-compute per-key HSL transform from
 * `(preset, customOverrides, variantName, editorBg, baseline, overlay)` to
 * `Map<TextAttributesKey, TextAttributes>`. No IDE singleton, no platform
 * I/O — fully testable in unit tests.
 *
 * Language-aware curve lookup (Codex HIGH #4): each baseline key's language
 * is derived from `key.externalName` via [SyntaxLanguageRegistry.classify] →
 * [SyntaxLanguageRegistry.LangTag.displayName]; the resulting language name
 * is the input to [SyntaxPresetCurves.curveFor]. The `variantName` parameter
 * is used ONLY for the R-1 caller-contract WARN — it never reaches the curve
 * lookup as a "language" mistake.
 *
 * Math: parse baseline foreground via [HslColor.fromColor], then:
 *  - saturation is additive (`hsl.saturation + saturationDelta`), self-clamped
 *    to `[0f, 1f]` (a `+0.40` on an S=1.0 token is inert by construction);
 *  - lightness is resolved by [CategoryCurve.lightnessDelta]'s mode. On the
 *    SIGNED CHROMA INTENT path (`absoluteLightness == false`) a `+intent` moves
 *    L toward `0.5` (louder, chroma up) and a `-intent` moves L away from `0.5`
 *    (quieter, washed), with the per-flank direction resolved at apply time so
 *    the Light theme works without a hardcoded sign. On the additive path
 *    (`absoluteLightness == true`, the Whisper COMMENT "dim = darker" cell) the
 *    delta is applied literally.
 *
 * Lightness is clamped to `[SYNTAX_MIN_LIGHTNESS, AccentHsl.MAX_LIGHTNESS]` on
 * the intent path — `SYNTAX_MIN_LIGHTNESS` (0.48) is a syntax-readability floor
 * because `AccentHsl.MIN_LIGHTNESS` (0.10) is too dark for token text on the
 * Mirage background. The absoluteLightness path keeps `AccentHsl.MIN_LIGHTNESS`
 * as its floor so the COMMENT dim can reach the legacy RGB×0.6 tolerance.
 * Hue invariant by construction.
 *
 * Pattern B clone discipline: baseline / overlay `TextAttributes` instances
 * are NEVER mutated. Every output value is a fresh clone obtained via
 * `source.clone()` and the cloned instance is the only thing the applicator
 * writes to.
 *
 * H10 fix carried forward: no `null` is ever emitted as a value — the
 * platform's `EditorColorsSchemeImpl.setAttributes(key, attrs)` declares
 * the second parameter `@NotNull`. The applicator's emit path always uses
 * the cloned (non-null) TextAttributes; keys that don't classify into a
 * [PrimitiveCategory] or whose source has no foreground simply don't get
 * an entry in the result map (skip-vs-null write).
 *
 * R-1 caller contract (D-09, RB-4): the applicator takes `editorBg: Color`
 * as a parameter — it does NOT call [dev.ayuislands.syntax.RgbBlend.fallbackEditorBgFor]
 * itself. The mitigation for `EditorColorsScheme.defaultBackground == Color.WHITE`
 * on a dark variant is the SERVICE caller's responsibility (Plan 50-05).
 * If a dark variant arrives here with `Color.WHITE`, the applicator latches
 * a one-time WARN per `(variant, session)` so the regression is visible in
 * `idea.log` without spamming.
 *
 * Pattern B note: there is NO `runCatching` and NO broad `catch` in this
 * file. The pure-compute path has no exception sources beyond
 * [HslColor.fromColor] / [HslColor.toColor], which `require()`-validate
 * inputs already clamped to `[0, 1]` / `[0, 360]` by the caller. Any
 * `IllegalArgumentException` from a malformed delta is a programming bug
 * the test suite will surface, not a runtime condition to recover from.
 */
object SyntaxIntensityApplicator {
    private val log = logger<SyntaxIntensityApplicator>()
    private val warnedBgFallback = ConcurrentHashMap.newKeySet<String>()

    private val darkVariants = setOf("Mirage", "Dark")

    @Suppress("LongParameterList")
    fun compute(
        preset: SyntaxPreset,
        customOverrides: Map<String, Map<String, Int>>,
        variantName: String,
        editorBg: Color,
        baseline: Map<TextAttributesKey, TextAttributes>,
        overlay: Map<TextAttributesKey, TextAttributes>,
        subordinatePreset: SyntaxPreset = SyntaxPreset.AMBIENT,
    ): Map<TextAttributesKey, TextAttributes> {
        warnOnceIfWhiteBgOnDarkVariant(variantName, editorBg)

        val result = mutableMapOf<TextAttributesKey, TextAttributes>()
        for ((key, baseAttrs) in baseline) {
            val source = overlay[key] ?: baseAttrs
            val category = SyntaxCategoryRegistry.classify(key.externalName) ?: continue
            val langTag = SyntaxLanguageRegistry.classify(key.externalName)
            val language = langTag.displayName
            val curve = resolveCurve(preset, language, category, customOverrides, subordinatePreset)
            val sourceFg = source.foregroundColor ?: continue
            val transformedFg = transformForeground(sourceFg, curve)
            val clone = source.clone()
            clone.foregroundColor = transformedFg
            result[key] = clone
        }
        // `editorBg` participates in the R-1 contract guard above but does
        // not feed the per-key transform; the parameter exists so the
        // applicator stays language-aware AND signature-stable for Plan
        // 50-05's service caller, which will pass the resolved editor
        // background for diagnostic logging.
        check(editorBg.alpha in 0..MAX_RGB_CHANNEL) { "editorBg alpha out of range: ${editorBg.alpha}" }
        return result
    }

    /**
     * Resolve the [CategoryCurve] for one `(preset, language, category)` cell.
     *
     * For the Custom drill-down (`preset == CUSTOM`) the per-cell slider in
     * `customOverrides` drives the curve: a present cell maps through
     * [SyntaxPresetCurves.sliderToCurve]; an untouched (sparse) cell inherits
     * the [subordinatePreset]'s named curve via [SyntaxPresetCurves.curveFor]
     * rather than collapsing to identity. The override lookup uses the same
     * composite key the persisted state writes — language is the
     * [SyntaxLanguageRegistry] displayName and the category half is
     * [PrimitiveCategory.name] (e.g. `KEYWORD`).
     *
     * For every named preset the static per-(preset, language, category) curve
     * table is consulted directly; `customOverrides` and `subordinatePreset`
     * are inert on that path.
     */
    private fun resolveCurve(
        preset: SyntaxPreset,
        language: String,
        category: PrimitiveCategory,
        customOverrides: Map<String, Map<String, Int>>,
        subordinatePreset: SyntaxPreset = SyntaxPreset.AMBIENT,
    ): CategoryCurve {
        if (preset == SyntaxPreset.CUSTOM) {
            val slider = customOverrides[language]?.get(category.name)
            if (slider != null) return SyntaxPresetCurves.sliderToCurve(slider)
            return SyntaxPresetCurves.curveFor(subordinatePreset, language, category)
        }
        return SyntaxPresetCurves.curveFor(preset, language, category)
    }

    private fun transformForeground(
        fg: Color,
        curve: CategoryCurve,
    ): Color {
        if (curve.saturationDelta == 0f && curve.lightnessDelta == 0f) return fg
        val hsl = HslColor.fromColor(fg)
        // Saturation stays additive; +0.40 on an S=1.0 token is inert by
        // construction (the coerce clamps it back to 1.0).
        val newSat = (hsl.saturation + curve.saturationDelta).coerceIn(0f, 1f)
        val resolvedLight =
            if (curve.absoluteLightness) {
                // Legacy/explicit additive L path — Whisper COMMENT "dim = darker".
                hsl.lightness + curve.lightnessDelta
            } else {
                // Signed chroma intent: +intent moves L toward 0.5 (louder,
                // chroma up); -intent moves L away from 0.5 (quieter, washed).
                val towardMid = if (hsl.lightness >= MID_LIGHTNESS) -1f else 1f
                val lightnessDirection = if (curve.lightnessDelta >= 0f) towardMid else -towardMid
                hsl.lightness + lightnessDirection * abs(curve.lightnessDelta)
            }
        // The intent path keeps the syntax-readability floor (0.48); the
        // absoluteLightness COMMENT dim is exempt so it can reach the legacy
        // RGB×0.6 tolerance, falling back to AccentHsl's absolute floor (0.10).
        val lowerBound = if (curve.absoluteLightness) AccentHsl.MIN_LIGHTNESS else SYNTAX_MIN_LIGHTNESS
        val newLight = resolvedLight.coerceIn(lowerBound, AccentHsl.MAX_LIGHTNESS)
        return HslColor.toColor(hsl.hue, newSat, newLight)
    }

    private fun warnOnceIfWhiteBgOnDarkVariant(
        variantName: String,
        editorBg: Color,
    ) {
        if (editorBg.rgb == Color.WHITE.rgb &&
            variantName in darkVariants &&
            warnedBgFallback.add(variantName)
        ) {
            log.warn(
                "editorBg arrived as Color.WHITE for dark variant '$variantName' — " +
                    "caller must resolve via RgbBlend.fallbackEditorBgFor (R-1)",
            )
        }
    }

    private const val MAX_RGB_CHANNEL = 255

    // The flank pivot for the signed chroma intent: tokens at or above this L
    // are pushed DOWN toward it by a positive intent, tokens below are pushed UP.
    private const val MID_LIGHTNESS = 0.5f

    /**
     * Syntax-readability lightness floor. `AccentHsl.MIN_LIGHTNESS` (0.10) is an
     * absolute floor for the accent quick-actions but is too dark for token text
     * on the Mirage editor background (`#242936`); 0.48 keeps transformed syntax
     * legible. Applied as the lower clamp on the signed-intent lightness path
     * only — the Whisper COMMENT dim (`absoluteLightness`) keeps the 0.10 floor.
     */
    const val SYNTAX_MIN_LIGHTNESS = 0.48f
}
