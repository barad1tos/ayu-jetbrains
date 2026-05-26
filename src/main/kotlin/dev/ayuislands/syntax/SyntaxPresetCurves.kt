package dev.ayuislands.syntax

// Corrected intensity model: the prominent Ayu Mirage tokens (keyword,
// declarations, type-ref, literals, annotation) already sit at HSL S=1.0 with
// L>0.5, so a positive additive lightness pushes them PALER, not louder —
// chroma `(1 - |2L - 1|) * S` shrinks as L rises away from 0.5. The fix splits
// the tables into two levers:
//
//  - HIGH-S tokens (`*_INTENT` constants): `saturationDelta = 0` (a +S on an
//    S=1.0 token is inert by construction); intensity is driven by a SIGNED
//    CHROMA INTENT carried in `lightnessDelta`. The applicator's resolver turns
//    +intent into "move L toward 0.5" (louder, chroma up) and -intent into
//    "move L away from 0.5" (quieter, washed) on whichever flank the token sits.
//  - LOW-S tokens (`*_SAT` / `*_LIGHT` constants): saturation has real headroom,
//    so they keep an additive S lever plus a small signed L intent.
//
// Sign of the intent encodes loud(+) / quiet(-); the resolver owns the per-flank
// L direction so the model generalizes to the Light theme for free.

// HIGH-S signed chroma intents (saturationDelta is 0 for these tokens). Negative
// intent on Whisper washes the bright token out; positive intents on Neon /
// Cyberpunk drive L toward 0.5 (max chroma).
private const val WHISPER_HIGH_S_INTENT = -0.06f
private const val NEON_HIGH_S_INTENT = 0.10f
private const val CYBERPUNK_HIGH_S_INTENT = 0.17f

// LOW-S Whisper deltas — saturation headroom exists, so quiet via additive S
// plus a small away-from-mid intent.
private const val WHISPER_LOW_S_SAT = -0.15f
private const val WHISPER_LOW_S_LIGHT = -0.04f
private const val WHISPER_VAR_SAT = -0.15f
private const val WHISPER_DOC_SAT = -0.25f
private const val WHISPER_DOC_LIGHT = -0.04f

// COMMENT base (Whisper): legacy "dim = darker" — a literal additive lightness
// drop (absoluteLightness path) so the HSL pipeline output lands within ±10 RGB
// units per channel of Phase 49 RGB×0.6 dim on the medium-cool comment
// baselines. Determined empirically against #5C6773 and #787B80 baselines; the
// 0.48 readability floor is intentionally NOT applied on this path (see
// SyntaxIntensityApplicator.transformForeground).
private const val WHISPER_COMMENT_SAT = 0f
private const val WHISPER_COMMENT_LIGHT = -0.16f
private const val WHISPER_OPERATOR_SAT = -0.10f

// LOW-S Neon deltas — additive saturation + a small toward-mid intent.
private const val NEON_LOW_S_SAT = 0.20f
private const val NEON_LOW_S_LIGHT = 0.04f
private const val NEON_VAR_SAT = 0.20f
private const val NEON_COMMENT_SAT = 0.20f
private const val NEON_COMMENT_LIGHT = 0.04f
private const val NEON_OPERATOR_SAT = 0.10f
private const val NEON_DOC_SAT = 0.10f

// LOW-S Cyberpunk deltas — strongest additive saturation + toward-mid intent.
// LOCAL_VAR / parameter are near-gray in Ayu; at +0.45 S they gain visible
// color — intended for the loudest preset.
private const val CYBERPUNK_LOW_S_SAT = 0.45f
private const val CYBERPUNK_LOW_S_LIGHT = 0.07f

// Per-language override values for the top-12 languages (D-10). Each
// constant maps to exactly one (preset × language × category) cell.
// Per-language Whisper COMMENT overrides — each constant differs from the
// base AND from sibling-language overrides so the override-consultation
// test (Test 8) and the top-12 coverage gate (Test 9) see a delta per
// language. They ride the absoluteLightness path (literal L down, NOT the
// signed-intent flip) so comments dim downward; tolerance to RGB×0.6 still
// verified by SyntaxPresetCurvesTest Test 4.
private const val WHIS_JAVA_CMT_S = -0.02f
private const val WHIS_JAVA_CMT_L = -0.17f
private const val WHIS_KOTLIN_CMT_S = -0.01f
private const val WHIS_KOTLIN_CMT_L = -0.16f
private const val WHIS_PY_CMT_S = -0.03f
private const val WHIS_PY_CMT_L = -0.17f
private const val WHIS_JS_CMT_S = -0.02f
private const val WHIS_JS_CMT_L = -0.16f
private const val WHIS_TS_CMT_S = -0.03f
private const val WHIS_TS_CMT_L = -0.16f

// Scala keyword is a HIGH-S token — drive Whisper quiet via negative intent
// (saturationDelta 0). A distinct intent magnitude keeps the per-language
// override test honest.
private const val WHIS_SCALA_KW_INTENT = -0.08f

// Go / Rust keyword and Ruby function decl are HIGH-S tokens under Neon — a
// stronger toward-mid intent than the base so the override differs.
private const val NEON_GO_KW_INTENT = 0.12f
private const val NEON_RUBY_FUNC_INTENT = 0.12f

// PHP / Swift / C# keyword under Cyberpunk — HIGH-S, strongest toward-mid
// intent, distinct from the Cyberpunk base.
private const val CP_LANG_KW_INTENT = 0.18f

// Custom drill-down slider math. 50 is the identity midpoint; the swing
// magnitudes are the deltas at the slider extremes (0 and 100). MAX_SAT_SWING
// matches the low-S Cyberpunk saturation peak so the strongest Custom setting
// is as vivid as the most intense named preset. MAX_LIGHT_SWING matches the
// HIGH-S Cyberpunk chroma intent (0.17) so the slider's signed-intent L lever
// reaches the same authority — the slider's CategoryCurve(t*sat, t*light) flows
// through the SAME signed-intent resolver (default absoluteLightness=false), so
// >50 moves L toward 0.5 (louder) and <50 away (quieter). The applicator's
// transformForeground owns the clamp; this function deliberately does NOT
// pre-clamp.
private const val SLIDER_MID = 50
private const val MAX_SAT_SWING = 0.40f
private const val MAX_LIGHT_SWING = 0.17f

/**
 * Per-(preset × language × category) saturation + lightness delta lookup for
 * the syntax-intensity applicator. Sparse: per-language overrides only for the
 * top-12 languages; remaining languages inherit the per-preset base table
 * (D-10).
 *
 * Delta semantics: `saturationDelta` and `lightnessDelta` are both in
 * `[-1f, +1f]`. Saturation is always additive (it self-clamps to `[0f, 1f]`, so
 * a `+0.40` on an S=1.0 token is inert). `lightnessDelta` is interpreted by
 * [SyntaxIntensityApplicator.transformForeground]:
 *
 *  - When [absoluteLightness] is `false` (default) it is a SIGNED CHROMA INTENT:
 *    `+intent` moves L toward `0.5` (louder, chroma up); `-intent` moves L away
 *    from `0.5` (quieter, washed). The applicator resolves the per-flank L
 *    direction, so the model generalizes to the Light theme without hardcoding
 *    a direction in the constants.
 *  - When [absoluteLightness] is `true` it is the legacy literal additive L
 *    delta (used by the Whisper COMMENT "dim = darker" cell), bypassing the
 *    intent flip.
 *
 * The applicator clamps the resolved lightness to a syntax-readability floor
 * (`SYNTAX_MIN_LIGHTNESS`, 0.48) on the intent path and to
 * `AccentHsl.MIN_LIGHTNESS` (0.10) on the absoluteLightness path; the upper
 * bound is `AccentHsl.MAX_LIGHTNESS` (0.95) on both.
 */
data class CategoryCurve(
    val saturationDelta: Float,
    val lightnessDelta: Float,
    val absoluteLightness: Boolean = false,
) {
    companion object {
        val IDENTITY: CategoryCurve = CategoryCurve(0f, 0f)
    }
}

object SyntaxPresetCurves {
    private val AMBIENT_BASE: Map<PrimitiveCategory, CategoryCurve> =
        PrimitiveCategory.entries.associateWith { CategoryCurve.IDENTITY }

    // Whisper: quietens every category. HIGH-S tokens (keyword, decls, type-ref,
    // literals, annotation, generics) carry a NEGATIVE chroma intent (saturation
    // 0) so they wash out instead of being pushed paler by an additive L.
    // LOW-S tokens keep an additive S drop. COMMENT rides the absoluteLightness
    // path so it dims downward, approximating Phase 49 DIMMED_COMMENTS (RGB×0.6)
    // — verified by SyntaxPresetCurvesTest Test 4 within ±10 RGB units/channel.
    private val WHISPER_BASE: Map<PrimitiveCategory, CategoryCurve> =
        mapOf(
            PrimitiveCategory.FUNCTION_DECL to CategoryCurve(0f, WHISPER_HIGH_S_INTENT),
            PrimitiveCategory.CLASS_DECL to CategoryCurve(0f, WHISPER_HIGH_S_INTENT),
            PrimitiveCategory.INTERFACE_DECL to CategoryCurve(0f, WHISPER_HIGH_S_INTENT),
            PrimitiveCategory.KEYWORD to CategoryCurve(0f, WHISPER_HIGH_S_INTENT),
            PrimitiveCategory.PARAMETER to CategoryCurve(WHISPER_VAR_SAT, WHISPER_LOW_S_LIGHT),
            PrimitiveCategory.LOCAL_VAR to CategoryCurve(WHISPER_VAR_SAT, WHISPER_LOW_S_LIGHT),
            PrimitiveCategory.STRING_LITERAL to CategoryCurve(0f, WHISPER_HIGH_S_INTENT),
            PrimitiveCategory.NUMBER_LITERAL to CategoryCurve(0f, WHISPER_HIGH_S_INTENT),
            PrimitiveCategory.COMMENT to
                CategoryCurve(WHISPER_COMMENT_SAT, WHISPER_COMMENT_LIGHT, absoluteLightness = true),
            PrimitiveCategory.ANNOTATION to CategoryCurve(0f, WHISPER_HIGH_S_INTENT),
            PrimitiveCategory.OPERATOR to CategoryCurve(WHISPER_OPERATOR_SAT, WHISPER_LOW_S_LIGHT),
            PrimitiveCategory.TYPE_REF to CategoryCurve(0f, WHISPER_HIGH_S_INTENT),
            PrimitiveCategory.STATIC_FIELD to CategoryCurve(WHISPER_LOW_S_SAT, WHISPER_LOW_S_LIGHT),
            PrimitiveCategory.INSTANCE_FIELD to CategoryCurve(WHISPER_LOW_S_SAT, WHISPER_LOW_S_LIGHT),
            PrimitiveCategory.GENERICS to CategoryCurve(0f, WHISPER_HIGH_S_INTENT),
            PrimitiveCategory.DOCUMENTATION to CategoryCurve(WHISPER_DOC_SAT, WHISPER_DOC_LIGHT),
        )

    // Neon: louder. HIGH-S tokens move L toward 0.5 via a positive chroma intent
    // (saturation 0); LOW-S tokens get an additive S boost plus a small
    // toward-mid intent.
    private val NEON_BASE: Map<PrimitiveCategory, CategoryCurve> =
        mapOf(
            PrimitiveCategory.FUNCTION_DECL to CategoryCurve(0f, NEON_HIGH_S_INTENT),
            PrimitiveCategory.CLASS_DECL to CategoryCurve(0f, NEON_HIGH_S_INTENT),
            PrimitiveCategory.INTERFACE_DECL to CategoryCurve(0f, NEON_HIGH_S_INTENT),
            PrimitiveCategory.KEYWORD to CategoryCurve(0f, NEON_HIGH_S_INTENT),
            PrimitiveCategory.PARAMETER to CategoryCurve(NEON_VAR_SAT, NEON_LOW_S_LIGHT),
            PrimitiveCategory.LOCAL_VAR to CategoryCurve(NEON_VAR_SAT, NEON_LOW_S_LIGHT),
            PrimitiveCategory.STRING_LITERAL to CategoryCurve(0f, NEON_HIGH_S_INTENT),
            PrimitiveCategory.NUMBER_LITERAL to CategoryCurve(0f, NEON_HIGH_S_INTENT),
            PrimitiveCategory.COMMENT to CategoryCurve(NEON_COMMENT_SAT, NEON_COMMENT_LIGHT),
            PrimitiveCategory.ANNOTATION to CategoryCurve(0f, NEON_HIGH_S_INTENT),
            PrimitiveCategory.OPERATOR to CategoryCurve(NEON_OPERATOR_SAT, NEON_LOW_S_LIGHT),
            PrimitiveCategory.TYPE_REF to CategoryCurve(0f, NEON_HIGH_S_INTENT),
            PrimitiveCategory.STATIC_FIELD to CategoryCurve(NEON_LOW_S_SAT, NEON_LOW_S_LIGHT),
            PrimitiveCategory.INSTANCE_FIELD to CategoryCurve(NEON_LOW_S_SAT, NEON_LOW_S_LIGHT),
            PrimitiveCategory.GENERICS to CategoryCurve(0f, NEON_HIGH_S_INTENT),
            PrimitiveCategory.DOCUMENTATION to CategoryCurve(NEON_DOC_SAT, NEON_LOW_S_LIGHT),
        )

    // Cyberpunk: loudest. HIGH-S tokens get the strongest toward-mid intent;
    // LOW-S tokens get the strongest additive S (near-gray local vars / params
    // gain visible color — intended for the loudest preset).
    private val CYBERPUNK_BASE: Map<PrimitiveCategory, CategoryCurve> =
        mapOf(
            PrimitiveCategory.FUNCTION_DECL to CategoryCurve(0f, CYBERPUNK_HIGH_S_INTENT),
            PrimitiveCategory.CLASS_DECL to CategoryCurve(0f, CYBERPUNK_HIGH_S_INTENT),
            PrimitiveCategory.INTERFACE_DECL to CategoryCurve(0f, CYBERPUNK_HIGH_S_INTENT),
            PrimitiveCategory.KEYWORD to CategoryCurve(0f, CYBERPUNK_HIGH_S_INTENT),
            PrimitiveCategory.PARAMETER to CategoryCurve(CYBERPUNK_LOW_S_SAT, CYBERPUNK_LOW_S_LIGHT),
            PrimitiveCategory.LOCAL_VAR to CategoryCurve(CYBERPUNK_LOW_S_SAT, CYBERPUNK_LOW_S_LIGHT),
            PrimitiveCategory.STRING_LITERAL to CategoryCurve(0f, CYBERPUNK_HIGH_S_INTENT),
            PrimitiveCategory.NUMBER_LITERAL to CategoryCurve(0f, CYBERPUNK_HIGH_S_INTENT),
            PrimitiveCategory.COMMENT to CategoryCurve(CYBERPUNK_LOW_S_SAT, CYBERPUNK_LOW_S_LIGHT),
            PrimitiveCategory.ANNOTATION to CategoryCurve(0f, CYBERPUNK_HIGH_S_INTENT),
            PrimitiveCategory.OPERATOR to CategoryCurve(CYBERPUNK_LOW_S_SAT, CYBERPUNK_LOW_S_LIGHT),
            PrimitiveCategory.TYPE_REF to CategoryCurve(0f, CYBERPUNK_HIGH_S_INTENT),
            PrimitiveCategory.STATIC_FIELD to CategoryCurve(CYBERPUNK_LOW_S_SAT, CYBERPUNK_LOW_S_LIGHT),
            PrimitiveCategory.INSTANCE_FIELD to CategoryCurve(CYBERPUNK_LOW_S_SAT, CYBERPUNK_LOW_S_LIGHT),
            PrimitiveCategory.GENERICS to CategoryCurve(0f, CYBERPUNK_HIGH_S_INTENT),
            PrimitiveCategory.DOCUMENTATION to CategoryCurve(CYBERPUNK_LOW_S_SAT, CYBERPUNK_LOW_S_LIGHT),
        )

    // Sparse per-language overrides — top-12 languages by D-10. Each entry
    // overrides a single (preset × language × category) cell. The
    // SyntaxPresetCurvesTest coverage gate (Test 9) enforces that each of
    // the 12 languages appears in at least one preset's override map.
    private val LANGUAGE_OVERRIDES: Map<SyntaxPreset, Map<String, Map<PrimitiveCategory, CategoryCurve>>> =
        mapOf(
            SyntaxPreset.WHISPER to
                mapOf(
                    "Java" to
                        mapOf(
                            PrimitiveCategory.COMMENT to
                                CategoryCurve(WHIS_JAVA_CMT_S, WHIS_JAVA_CMT_L, absoluteLightness = true),
                        ),
                    "Kotlin" to
                        mapOf(
                            PrimitiveCategory.COMMENT to
                                CategoryCurve(WHIS_KOTLIN_CMT_S, WHIS_KOTLIN_CMT_L, absoluteLightness = true),
                        ),
                    "Python" to
                        mapOf(
                            PrimitiveCategory.COMMENT to
                                CategoryCurve(WHIS_PY_CMT_S, WHIS_PY_CMT_L, absoluteLightness = true),
                        ),
                    "JavaScript" to
                        mapOf(
                            PrimitiveCategory.COMMENT to
                                CategoryCurve(WHIS_JS_CMT_S, WHIS_JS_CMT_L, absoluteLightness = true),
                        ),
                    "TypeScript" to
                        mapOf(
                            PrimitiveCategory.COMMENT to
                                CategoryCurve(WHIS_TS_CMT_S, WHIS_TS_CMT_L, absoluteLightness = true),
                        ),
                    "Scala" to
                        mapOf(
                            PrimitiveCategory.KEYWORD to CategoryCurve(0f, WHIS_SCALA_KW_INTENT),
                        ),
                ),
            SyntaxPreset.NEON to
                mapOf(
                    "Go" to
                        mapOf(
                            PrimitiveCategory.KEYWORD to CategoryCurve(0f, NEON_GO_KW_INTENT),
                        ),
                    "Rust" to
                        mapOf(
                            PrimitiveCategory.KEYWORD to CategoryCurve(0f, NEON_GO_KW_INTENT),
                        ),
                    "Ruby" to
                        mapOf(
                            PrimitiveCategory.FUNCTION_DECL to CategoryCurve(0f, NEON_RUBY_FUNC_INTENT),
                        ),
                ),
            SyntaxPreset.CYBERPUNK to
                mapOf(
                    "PHP" to
                        mapOf(
                            PrimitiveCategory.KEYWORD to CategoryCurve(0f, CP_LANG_KW_INTENT),
                        ),
                    "Swift" to
                        mapOf(
                            PrimitiveCategory.KEYWORD to CategoryCurve(0f, CP_LANG_KW_INTENT),
                        ),
                    "C#" to
                        mapOf(
                            PrimitiveCategory.KEYWORD to CategoryCurve(0f, CP_LANG_KW_INTENT),
                        ),
                ),
            // AMBIENT: no overrides — identity throughout (saves churn).
        )

    /**
     * Resolve the curve for `(preset, language, category)`. Consults the
     * sparse per-language overrides first; falls back to the preset's base
     * table; falls back finally to the AMBIENT identity (defensive).
     */
    fun curveFor(
        preset: SyntaxPreset,
        language: String,
        category: PrimitiveCategory,
    ): CategoryCurve {
        LANGUAGE_OVERRIDES[preset]?.get(language)?.get(category)?.let { return it }
        return baseFor(preset)[category] ?: CategoryCurve.IDENTITY
    }

    /**
     * Map a Custom drill-down slider position `0..100` to a [CategoryCurve].
     *
     * Linear from the 50 midpoint: `t = (value - 50) / 50f` ranges `-1f..+1f`,
     * with `50` the identity zero-point. The saturation half is a raw additive
     * delta; the lightness half is a SIGNED CHROMA INTENT (the curve defaults to
     * `absoluteLightness = false`), so above 50 the resolver moves L toward
     * `0.5` (louder) and below 50 away from it (quieter) — the same lever the
     * named presets use. Hue is invariant by construction (no hue term). Deltas
     * are RAW swing magnitudes — the applicator's `transformForeground` clamps
     * saturation to `[0f, 1f]` and lightness to the syntax readability floor /
     * `AccentHsl.MAX_LIGHTNESS`, so this function deliberately does NOT
     * pre-clamp.
     */
    fun sliderToCurve(value: Int): CategoryCurve {
        val t = (value - SLIDER_MID) / SLIDER_MID.toFloat()
        return CategoryCurve(t * MAX_SAT_SWING, t * MAX_LIGHT_SWING)
    }

    private fun baseFor(preset: SyntaxPreset): Map<PrimitiveCategory, CategoryCurve> =
        when (preset) {
            SyntaxPreset.WHISPER -> WHISPER_BASE
            SyntaxPreset.AMBIENT -> AMBIENT_BASE
            SyntaxPreset.NEON -> NEON_BASE
            SyntaxPreset.CYBERPUNK -> CYBERPUNK_BASE
            // CUSTOM is a sentinel — the applicator owns customOverrides lookup.
            SyntaxPreset.CUSTOM -> AMBIENT_BASE
        }
}
