package dev.ayuislands.syntax

import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.editor.markup.TextAttributes
import dev.ayuislands.accent.color.AccentHsl
import dev.ayuislands.rotation.HslColor
import java.awt.Color
import java.util.concurrent.ConcurrentHashMap

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
 * Math (D-03): parse baseline foreground via [HslColor.fromColor] → add
 * `(saturationDelta, lightnessDelta)` from the resolved [CategoryCurve] →
 * clamp saturation to `[0f, 1f]`, lightness to
 * `[AccentHsl.MIN_LIGHTNESS, AccentHsl.MAX_LIGHTNESS]` → repack via
 * [HslColor.toColor]. Hue invariant by construction.
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
    ): Map<TextAttributesKey, TextAttributes> {
        warnOnceIfWhiteBgOnDarkVariant(variantName, editorBg)

        val result = mutableMapOf<TextAttributesKey, TextAttributes>()
        for ((key, baseAttrs) in baseline) {
            val source = overlay[key] ?: baseAttrs
            val category = SyntaxCategoryRegistry.classify(key.externalName) ?: continue
            val langTag = SyntaxLanguageRegistry.classify(key.externalName)
            val language = langTag.displayName
            val curve = resolveCurve(preset, language, category, customOverrides)
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
     * Phase 50A only consults the preset's static curve table. The Phase 50B
     * Custom drill-down will activate the `customOverrides` lookup path — for
     * now, accepting a populated map without throwing is enough (the free tier
     * UI never writes to it). The signature accepts the param so the contract
     * is stable across Wave 2 / Wave 3.
     */
    private fun resolveCurve(
        preset: SyntaxPreset,
        language: String,
        category: PrimitiveCategory,
        @Suppress("UNUSED_PARAMETER") customOverrides: Map<String, Map<String, Int>>,
    ): CategoryCurve = SyntaxPresetCurves.curveFor(preset, language, category)

    private fun transformForeground(
        fg: Color,
        curve: CategoryCurve,
    ): Color {
        if (curve.saturationDelta == 0f && curve.lightnessDelta == 0f) return fg
        val hsl = HslColor.fromColor(fg)
        val newSat = (hsl.saturation + curve.saturationDelta).coerceIn(0f, 1f)
        val newLight =
            (hsl.lightness + curve.lightnessDelta)
                .coerceIn(AccentHsl.MIN_LIGHTNESS, AccentHsl.MAX_LIGHTNESS)
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
}
