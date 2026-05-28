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
 * Custom font style (Part A backend): under `preset == CUSTOM` the per-cell
 * `customStyles` map (language -> category -> `java.awt.Font` bitmask) sets
 * the clone's `fontType` after the foreground transform. The two fields are
 * independent `TextAttributes` slots, so the style set is orthogonal to the
 * hue/color math — a cell may carry a style with no slider, or a slider with
 * no style. Sparse: an absent cell leaves `fontType` untouched (inherits the
 * source style). Named / AMBIENT presets never read `customStyles`.
 *
 * Readability modifiers are a second semantic layer after the selected preset
 * has resolved. They never write Custom slider cells: comments, docs, and
 * operators recede by blending toward `editorBg`, while declarations get a
 * small chroma-intent boost. The user's Custom sparse map stays manual
 * per-language tuning only.
 *
 * Pattern B clone discipline: baseline / overlay `TextAttributes` instances
 * are NEVER mutated. Every output value is a fresh clone obtained via
 * `source.clone()` and the cloned instance is the only thing the applicator
 * writes to (both `foregroundColor` and `fontType`).
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

    data class Request(
        val preset: SyntaxPreset,
        val variantName: String,
        val editorBg: Color,
        val baseline: Map<TextAttributesKey, TextAttributes>,
        val overlay: Map<TextAttributesKey, TextAttributes>,
        val customOverrides: Map<String, Map<String, Int>> = emptyMap(),
        val subordinatePreset: SyntaxPreset = SyntaxPreset.AMBIENT,
        val customStyles: Map<String, Map<String, Int>> = emptyMap(),
        val readabilityOptions: SyntaxReadabilityOptions = SyntaxReadabilityOptions.DEFAULT,
    )

    fun compute(request: Request): Map<TextAttributesKey, TextAttributes> {
        val preset = request.preset
        val customOverrides = request.customOverrides
        val subordinatePreset = request.subordinatePreset
        val baseline = request.baseline
        val overlay = request.overlay
        warnOnceIfWhiteBgOnDarkVariant(request.variantName, request.editorBg)

        val result = linkedMapOf<TextAttributesKey, TextAttributes>()
        val context =
            TransformContext(
                preset = preset,
                customOverrides = customOverrides,
                subordinatePreset = subordinatePreset,
                customStyles = request.customStyles,
                readabilityOptions = request.readabilityOptions,
                editorBg = request.editorBg,
            )
        val sources = AttributeSources(baseline, overlay)
        val keys = LinkedHashSet<TextAttributesKey>()
        keys.addAll(baseline.keys)
        keys.addAll(overlay.keys)
        for (key in keys) {
            val source = overlay[key] ?: baseline[key] ?: continue
            val category = SyntaxCategoryRegistry.classify(key.externalName) ?: continue
            val langTag = SyntaxLanguageRegistry.classify(key.externalName)
            val language = langTag.displayName
            val curve = resolveCurve(preset, language, category, customOverrides, subordinatePreset)
            val transformed =
                transformedAttributes(source, curve, context, language, category)
                    ?: continue
            result[key] = transformed
            if (langTag.bucket == SyntaxLanguageRegistry.Bucket.CASCADE &&
                key.externalName in SyntaxLanguageRegistry.cascadeKeysInScope()
            ) {
                materializeCascadeTargets(
                    CascadeSource(key.externalName, category, source),
                    context,
                    sources,
                    result,
                )
            }
        }
        // `request.editorBg` participates in the R-1 contract guard above but does
        // not feed the per-key transform; the parameter exists so the
        // applicator stays language-aware AND signature-stable for Plan
        // 50-05's service caller, which will pass the resolved editor
        // background for diagnostic logging.
        check(request.editorBg.alpha in 0..MAX_RGB_CHANNEL) {
            "editorBg alpha out of range: ${request.editorBg.alpha}"
        }
        return result
    }

    private data class TransformContext(
        val preset: SyntaxPreset,
        val customOverrides: Map<String, Map<String, Int>>,
        val subordinatePreset: SyntaxPreset,
        val customStyles: Map<String, Map<String, Int>>,
        val readabilityOptions: SyntaxReadabilityOptions,
        val editorBg: Color,
    )

    private data class AttributeSources(
        val baseline: Map<TextAttributesKey, TextAttributes>,
        val overlay: Map<TextAttributesKey, TextAttributes>,
    )

    private data class CascadeSource(
        val defaultKeyName: String,
        val category: PrimitiveCategory,
        val attributes: TextAttributes,
    )

    private fun transformedAttributes(
        source: TextAttributes,
        curve: CategoryCurve,
        context: TransformContext,
        language: String,
        category: PrimitiveCategory,
    ): TextAttributes? {
        val sourceForeground = source.foregroundColor ?: return null
        val clone = source.clone()
        clone.foregroundColor =
            transformForeground(
                fg = sourceForeground,
                curve = curve,
                readabilityOptions = context.readabilityOptions,
                category = category,
                editorBg = context.editorBg,
            )
        // Sparse per-category font style — gated to the Custom drill-down.
        // fontType and foregroundColor are independent TextAttributes fields,
        // so the style set is orthogonal to the hue/color transform above.
        // An absent cell leaves clone.fontType untouched.
        if (context.preset == SyntaxPreset.CUSTOM) {
            context.customStyles[language]?.get(category.name)?.let { clone.fontType = it }
        }
        return clone
    }

    private fun materializeCascadeTargets(
        cascadeSource: CascadeSource,
        context: TransformContext,
        sources: AttributeSources,
        result: MutableMap<TextAttributesKey, TextAttributes>,
    ) {
        for (target in SyntaxLanguageRegistry.cascadeTargetsFor(cascadeSource.defaultKeyName)) {
            val targetKey = findKey(target.keyName, sources)
            val targetSource = sources.overlay[targetKey] ?: sources.baseline[targetKey]
            if (targetSource?.foregroundColor != null) continue
            val language = target.language.displayName
            val curve =
                resolveCurve(
                    context.preset,
                    language,
                    cascadeSource.category,
                    context.customOverrides,
                    context.subordinatePreset,
                )
            val transformed =
                transformedAttributes(
                    cascadeSource.attributes,
                    curve,
                    context,
                    language,
                    cascadeSource.category,
                )
                    ?: continue
            result[targetKey] = transformed
        }
    }

    private fun findKey(
        keyName: String,
        sources: AttributeSources,
    ): TextAttributesKey =
        sources.baseline.keys.firstOrNull { it.externalName == keyName }
            ?: sources.overlay.keys.firstOrNull { it.externalName == keyName }
            ?: TextAttributesKey.createTextAttributesKey(keyName)

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
        readabilityOptions: SyntaxReadabilityOptions,
        category: PrimitiveCategory,
        editorBg: Color,
    ): Color {
        val color = transformForeground(fg, curve)
        return applyReadabilityModifiers(color, category, readabilityOptions, editorBg)
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

    private fun applyReadabilityModifiers(
        color: Color,
        category: PrimitiveCategory,
        options: SyntaxReadabilityOptions,
        editorBg: Color,
    ): Color =
        when (category) {
            PrimitiveCategory.COMMENT ->
                if (options.dimComments) {
                    RgbBlend.blend(color, editorBg, DIM_COMMENTS_INTENSITY)
                } else {
                    color
                }
            PrimitiveCategory.DOCUMENTATION ->
                if (options.softenDocumentation) {
                    RgbBlend.blend(color, editorBg, SOFTEN_DOCUMENTATION_INTENSITY)
                } else {
                    color
                }
            PrimitiveCategory.OPERATOR ->
                if (options.quietOperators) {
                    RgbBlend.blend(color, editorBg, QUIET_OPERATORS_INTENSITY)
                } else {
                    color
                }
            PrimitiveCategory.FUNCTION_DECL,
            PrimitiveCategory.CLASS_DECL,
            PrimitiveCategory.INTERFACE_DECL,
            ->
                if (options.emphasizeDeclarations) transformForeground(color, EMPHASIZE_DECLARATIONS_CURVE) else color
            else -> color
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

    private const val DIM_COMMENTS_INTENSITY = 70
    private const val SOFTEN_DOCUMENTATION_INTENSITY = 82
    private const val QUIET_OPERATORS_INTENSITY = 78
    private const val EMPHASIZE_DECLARATIONS_SATURATION_DELTA = 0.08f
    private const val EMPHASIZE_DECLARATIONS_LIGHTNESS_DELTA = 0.06f

    private val EMPHASIZE_DECLARATIONS_CURVE =
        CategoryCurve(EMPHASIZE_DECLARATIONS_SATURATION_DELTA, EMPHASIZE_DECLARATIONS_LIGHTNESS_DELTA)

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
