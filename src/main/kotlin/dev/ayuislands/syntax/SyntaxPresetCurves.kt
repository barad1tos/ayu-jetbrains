package dev.ayuislands.syntax

// Whisper deltas — negative across the board; COMMENT has the deepest drop
// so the HSL pipeline output approximates Phase 49 DIMMED_COMMENTS (RGB×0.6).
private const val WHISPER_DECL_SAT = -0.15f
private const val WHISPER_DECL_LIGHT = -0.05f
private const val WHISPER_KEYWORD_SAT = -0.20f
private const val WHISPER_KEYWORD_LIGHT = -0.05f
private const val WHISPER_VAR_SAT = -0.10f
private const val WHISPER_LITERAL_SAT = -0.20f

// COMMENT base: preserves saturation (so cool tint survives) and drops
// lightness by ~0.16 so the HSL pipeline output lands within ±6 RGB units
// per channel of Phase 49 RGB×0.6 dim on the medium-cool comment baselines.
// Determined empirically against #5C6773 and #787B80 baselines.
private const val WHISPER_COMMENT_SAT = 0f
private const val WHISPER_COMMENT_LIGHT = -0.16f
private const val WHISPER_OPERATOR_SAT = -0.10f
private const val WHISPER_FIELD_SAT = -0.15f
private const val WHISPER_DOC_SAT = -0.25f
private const val WHISPER_DOC_LIGHT = -0.10f

// Neon deltas — boost saturation on declarations + keywords (INTENSITY-02).
private const val NEON_DECL_SAT = 0.20f
private const val NEON_DECL_LIGHT = 0.05f
private const val NEON_KEYWORD_SAT = 0.25f
private const val NEON_KEYWORD_LIGHT = 0.05f
private const val NEON_VAR_SAT = 0.10f
private const val NEON_LITERAL_SAT = 0.10f
private const val NEON_NUMBER_SAT = 0.15f
private const val NEON_COMMENT_SAT = 0.05f
private const val NEON_OPERATOR_SAT = 0.10f
private const val NEON_TYPE_SAT = 0.20f
private const val NEON_FIELD_SAT = 0.15f
private const val NEON_DOC_SAT = 0.05f

// Cyberpunk delta — uniform peak ≥ 0.3f to satisfy peak assertion + visibly
// distinct from Neon.
private const val CYBERPUNK_SAT = 0.40f
private const val CYBERPUNK_LIGHT = 0.05f

// Per-language override values for the top-12 languages (D-10). Each
// constant maps to exactly one (preset × language × category) cell.
// Per-language Whisper COMMENT overrides — each constant differs from the
// base AND from sibling-language overrides so the override-consultation
// test (Test 8) and the top-12 coverage gate (Test 9) see a delta per
// language. Tolerance to RGB×0.6 still verified by Test 4.
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
private const val WHIS_SCALA_KW_S = -0.25f
private const val WHIS_SCALA_KW_L = -0.08f
private const val NEON_GO_KW_S = 0.30f
private const val NEON_GO_KW_L = 0.06f
private const val NEON_RUBY_FUNC_SAT = 0.25f
private const val NEON_RUBY_FUNC_LIGHT = 0.05f
private const val CP_LANG_KW_S = 0.42f
private const val CP_LANG_KW_L = 0.05f

/**
 * Per-(preset × language × category) saturation + lightness delta lookup for
 * the syntax-intensity applicator. Sparse: per-language overrides only for the
 * top-12 languages; remaining languages inherit the per-preset base table
 * (D-10).
 *
 * Delta semantics: `saturationDelta` and `lightnessDelta` are both in
 * `[-1f, +1f]`. The applicator clamps post-addition (D-03) — saturation to
 * `[0f, 1f]` and lightness to `AccentHsl.MIN_LIGHTNESS..MAX_LIGHTNESS`.
 */
data class CategoryCurve(
    val saturationDelta: Float,
    val lightnessDelta: Float,
) {
    companion object {
        val IDENTITY: CategoryCurve = CategoryCurve(0f, 0f)
    }
}

object SyntaxPresetCurves {
    private val AMBIENT_BASE: Map<PrimitiveCategory, CategoryCurve> =
        PrimitiveCategory.entries.associateWith { CategoryCurve.IDENTITY }

    // Whisper: subdues every category with negative saturation. COMMENT carries
    // an extra lightness drop so the perceptual output approximates the Phase
    // 49 DIMMED_COMMENTS recipe (RGB×0.6) once the applicator runs the HSL
    // clamp (D-07). Tolerance verified by SyntaxPresetCurvesTest Test 4
    // against medium-cool comment foregrounds within ±6 RGB units per channel.
    private val WHISPER_BASE: Map<PrimitiveCategory, CategoryCurve> =
        mapOf(
            PrimitiveCategory.FUNCTION_DECL to CategoryCurve(WHISPER_DECL_SAT, WHISPER_DECL_LIGHT),
            PrimitiveCategory.CLASS_DECL to CategoryCurve(WHISPER_DECL_SAT, WHISPER_DECL_LIGHT),
            PrimitiveCategory.INTERFACE_DECL to CategoryCurve(WHISPER_DECL_SAT, WHISPER_DECL_LIGHT),
            PrimitiveCategory.KEYWORD to CategoryCurve(WHISPER_KEYWORD_SAT, WHISPER_KEYWORD_LIGHT),
            PrimitiveCategory.PARAMETER to CategoryCurve(WHISPER_VAR_SAT, 0f),
            PrimitiveCategory.LOCAL_VAR to CategoryCurve(WHISPER_VAR_SAT, 0f),
            PrimitiveCategory.STRING_LITERAL to CategoryCurve(WHISPER_LITERAL_SAT, 0f),
            PrimitiveCategory.NUMBER_LITERAL to CategoryCurve(WHISPER_LITERAL_SAT, 0f),
            PrimitiveCategory.COMMENT to CategoryCurve(WHISPER_COMMENT_SAT, WHISPER_COMMENT_LIGHT),
            PrimitiveCategory.ANNOTATION to CategoryCurve(WHISPER_KEYWORD_SAT, WHISPER_DECL_LIGHT),
            PrimitiveCategory.OPERATOR to CategoryCurve(WHISPER_OPERATOR_SAT, 0f),
            PrimitiveCategory.TYPE_REF to CategoryCurve(WHISPER_DECL_SAT, WHISPER_DECL_LIGHT),
            PrimitiveCategory.STATIC_FIELD to CategoryCurve(WHISPER_FIELD_SAT, 0f),
            PrimitiveCategory.INSTANCE_FIELD to CategoryCurve(WHISPER_FIELD_SAT, 0f),
            PrimitiveCategory.GENERICS to CategoryCurve(WHISPER_FIELD_SAT, 0f),
            PrimitiveCategory.DOCUMENTATION to CategoryCurve(WHISPER_DOC_SAT, WHISPER_DOC_LIGHT),
        )

    // Neon: boosts saturation on declarations + keywords (INTENSITY-02).
    private val NEON_BASE: Map<PrimitiveCategory, CategoryCurve> =
        mapOf(
            PrimitiveCategory.FUNCTION_DECL to CategoryCurve(NEON_DECL_SAT, NEON_DECL_LIGHT),
            PrimitiveCategory.CLASS_DECL to CategoryCurve(NEON_DECL_SAT, NEON_DECL_LIGHT),
            PrimitiveCategory.INTERFACE_DECL to CategoryCurve(NEON_DECL_SAT, NEON_DECL_LIGHT),
            PrimitiveCategory.KEYWORD to CategoryCurve(NEON_KEYWORD_SAT, NEON_KEYWORD_LIGHT),
            PrimitiveCategory.PARAMETER to CategoryCurve(NEON_VAR_SAT, 0f),
            PrimitiveCategory.LOCAL_VAR to CategoryCurve(NEON_VAR_SAT, 0f),
            PrimitiveCategory.STRING_LITERAL to CategoryCurve(NEON_LITERAL_SAT, 0f),
            PrimitiveCategory.NUMBER_LITERAL to CategoryCurve(NEON_NUMBER_SAT, 0f),
            PrimitiveCategory.COMMENT to CategoryCurve(NEON_COMMENT_SAT, 0f),
            PrimitiveCategory.ANNOTATION to CategoryCurve(NEON_DECL_SAT, 0f),
            PrimitiveCategory.OPERATOR to CategoryCurve(NEON_OPERATOR_SAT, 0f),
            PrimitiveCategory.TYPE_REF to CategoryCurve(NEON_TYPE_SAT, 0f),
            PrimitiveCategory.STATIC_FIELD to CategoryCurve(NEON_FIELD_SAT, 0f),
            PrimitiveCategory.INSTANCE_FIELD to CategoryCurve(NEON_FIELD_SAT, 0f),
            PrimitiveCategory.GENERICS to CategoryCurve(NEON_TYPE_SAT, 0f),
            PrimitiveCategory.DOCUMENTATION to CategoryCurve(NEON_DOC_SAT, 0f),
        )

    // Cyberpunk: uniform peak saturation across every category (INTENSITY-02).
    private val CYBERPUNK_BASE: Map<PrimitiveCategory, CategoryCurve> =
        PrimitiveCategory.entries.associateWith { CategoryCurve(CYBERPUNK_SAT, CYBERPUNK_LIGHT) }

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
                            PrimitiveCategory.COMMENT to CategoryCurve(WHIS_JAVA_CMT_S, WHIS_JAVA_CMT_L),
                        ),
                    "Kotlin" to
                        mapOf(
                            PrimitiveCategory.COMMENT to CategoryCurve(WHIS_KOTLIN_CMT_S, WHIS_KOTLIN_CMT_L),
                        ),
                    "Python" to
                        mapOf(
                            PrimitiveCategory.COMMENT to CategoryCurve(WHIS_PY_CMT_S, WHIS_PY_CMT_L),
                        ),
                    "JavaScript" to
                        mapOf(
                            PrimitiveCategory.COMMENT to CategoryCurve(WHIS_JS_CMT_S, WHIS_JS_CMT_L),
                        ),
                    "TypeScript" to
                        mapOf(
                            PrimitiveCategory.COMMENT to CategoryCurve(WHIS_TS_CMT_S, WHIS_TS_CMT_L),
                        ),
                    "Scala" to
                        mapOf(
                            PrimitiveCategory.KEYWORD to CategoryCurve(WHIS_SCALA_KW_S, WHIS_SCALA_KW_L),
                        ),
                ),
            SyntaxPreset.NEON to
                mapOf(
                    "Go" to
                        mapOf(
                            PrimitiveCategory.KEYWORD to CategoryCurve(NEON_GO_KW_S, NEON_GO_KW_L),
                        ),
                    "Rust" to
                        mapOf(
                            PrimitiveCategory.KEYWORD to CategoryCurve(NEON_GO_KW_S, NEON_GO_KW_L),
                        ),
                    "Ruby" to
                        mapOf(
                            PrimitiveCategory.FUNCTION_DECL to CategoryCurve(NEON_RUBY_FUNC_SAT, NEON_RUBY_FUNC_LIGHT),
                        ),
                ),
            SyntaxPreset.CYBERPUNK to
                mapOf(
                    "PHP" to
                        mapOf(
                            PrimitiveCategory.KEYWORD to CategoryCurve(CP_LANG_KW_S, CP_LANG_KW_L),
                        ),
                    "Swift" to
                        mapOf(
                            PrimitiveCategory.KEYWORD to CategoryCurve(CP_LANG_KW_S, CP_LANG_KW_L),
                        ),
                    "C#" to
                        mapOf(
                            PrimitiveCategory.KEYWORD to CategoryCurve(CP_LANG_KW_S, CP_LANG_KW_L),
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
