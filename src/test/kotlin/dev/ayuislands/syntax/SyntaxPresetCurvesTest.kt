package dev.ayuislands.syntax

import dev.ayuislands.accent.color.AccentHsl
import dev.ayuislands.rotation.HslColor
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.awt.Color
import kotlin.math.abs

/**
 * Locks the [SyntaxPresetCurves] contract:
 *
 *  - [CategoryCurve] is a `(saturationDelta, lightnessDelta)` pair, both `Float`.
 *  - AMBIENT is the identity transform for every (language × category) pair.
 *  - Whisper subdues across the board (`saturationDelta <= 0` for every category).
 *  - **Whisper COMMENT curve** approximates the Phase 49 DIMMED_COMMENTS recipe
 *    (RGB×0.6) within ±6 RGB units per channel when run through the same HSL
 *    transform pipeline Plan 50-04's applicator will use (Codex MEDIUM #7 fix —
 *    concrete tolerance-bounded equivalence test, not a vague "negative
 *    lightness" assertion).
 *  - Neon boosts declarations + keywords with positive `saturationDelta`
 *    (INTENSITY-02).
 *  - Cyberpunk maxes saturation across all categories with max delta ≥ 0.3f
 *    (INTENSITY-02).
 *  - All deltas stay in `[-1f, +1f]` (D-03 input range).
 *  - At least one per-language override exists, proving the override table is
 *    consulted before the base table.
 *  - Top-12 languages each appear in at least one preset's override map (D-10
 *    coverage gate).
 *  - All curve floats are finite (RB-1 regression lock — no NaN propagation
 *    through the HSL clamp).
 *  - CUSTOM is the sentinel — its curveFor lookup returns the AMBIENT identity
 *    because the Custom path consults customOverrides directly in the
 *    applicator.
 */
class SyntaxPresetCurvesTest {
    @Test
    fun `CategoryCurve has saturationDelta and lightnessDelta Float fields`() {
        val curve = CategoryCurve(saturationDelta = 0.1f, lightnessDelta = -0.2f)
        assertEquals(0.1f, curve.saturationDelta)
        assertEquals(-0.2f, curve.lightnessDelta)
    }

    @Test
    fun `AMBIENT is the identity transform for every category and unknown language`() {
        for (category in PrimitiveCategory.entries) {
            assertEquals(
                CategoryCurve(0f, 0f),
                SyntaxPresetCurves.curveFor(SyntaxPreset.AMBIENT, "Java", category),
                "AMBIENT must be identity for Java/$category",
            )
            assertEquals(
                CategoryCurve(0f, 0f),
                SyntaxPresetCurves.curveFor(SyntaxPreset.AMBIENT, "NotARealLanguage", category),
                "AMBIENT must be identity for the fallback path on NotARealLanguage/$category",
            )
        }
    }

    @Test
    fun `WHISPER subdues every category with non-positive saturationDelta`() {
        for (category in PrimitiveCategory.entries) {
            val curve = SyntaxPresetCurves.curveFor(SyntaxPreset.WHISPER, "Java", category)
            assertTrue(
                curve.saturationDelta <= 0f,
                "Whisper Java/$category must have non-positive saturationDelta, got ${curve.saturationDelta}",
            )
        }
    }

    @Test
    fun `WHISPER COMMENT curve approximates Phase 49 DIMMED_COMMENTS (RGB times 0_6) within 6 units per channel`() {
        // Sample comment baseline foregrounds from the Ayu variants — typical
        // medium-cool grey comments that the Phase 49 DIMMED_COMMENTS recipe
        // multiplies by 0.6 per channel. The curve, when run through the same
        // HSL transform pipeline Plan 50-04's applicator will use, must land
        // within ±6 RGB units per channel of that reference.
        val baselines =
            listOf(
                Color(0x5C, 0x67, 0x73), // Mirage / Dark typical comment
                Color(0x78, 0x7B, 0x80), // Light typical comment
            )

        val whisperCommentCurve =
            SyntaxPresetCurves.curveFor(SyntaxPreset.WHISPER, "Java", PrimitiveCategory.COMMENT)

        for (baseline in baselines) {
            val whisperOutput = applyCurveViaHsl(baseline, whisperCommentCurve)
            val dimmedReference = dimByPhase49Factor(baseline)

            val redDiff = abs(whisperOutput.red - dimmedReference.red)
            val greenDiff = abs(whisperOutput.green - dimmedReference.green)
            val blueDiff = abs(whisperOutput.blue - dimmedReference.blue)

            val baseHex = baseline.hex()
            val refHex = dimmedReference.hex()
            val outHex = whisperOutput.hex()
            assertTrue(
                redDiff <= MAX_CHANNEL_TOLERANCE,
                "Whisper COMMENT red for #$baseHex differs by $redDiff " +
                    "(> $MAX_CHANNEL_TOLERANCE) from RGB×$DIM_FACTOR #$refHex (got #$outHex)",
            )
            assertTrue(
                greenDiff <= MAX_CHANNEL_TOLERANCE,
                "Whisper COMMENT green for #$baseHex differs by $greenDiff " +
                    "(> $MAX_CHANNEL_TOLERANCE) from RGB×$DIM_FACTOR #$refHex (got #$outHex)",
            )
            assertTrue(
                blueDiff <= MAX_CHANNEL_TOLERANCE,
                "Whisper COMMENT blue for #$baseHex differs by $blueDiff " +
                    "(> $MAX_CHANNEL_TOLERANCE) from RGB×$DIM_FACTOR #$refHex (got #$outHex)",
            )
        }
    }

    @Test
    fun `NEON boosts declarations and keywords with positive saturationDelta (INTENSITY-02)`() {
        val neonFunc = SyntaxPresetCurves.curveFor(SyntaxPreset.NEON, "Kotlin", PrimitiveCategory.FUNCTION_DECL)
        val neonKeyword = SyntaxPresetCurves.curveFor(SyntaxPreset.NEON, "Kotlin", PrimitiveCategory.KEYWORD)
        assertTrue(
            neonFunc.saturationDelta > 0f,
            "Neon Kotlin/FUNCTION_DECL must have positive saturationDelta",
        )
        assertTrue(
            neonKeyword.saturationDelta > 0f,
            "Neon Kotlin/KEYWORD must have positive saturationDelta",
        )
    }

    @Test
    fun `CYBERPUNK maxes saturation across every category with peak delta at or above 0_3f`() {
        var maxDelta = -Float.MAX_VALUE
        for (category in PrimitiveCategory.entries) {
            val curve = SyntaxPresetCurves.curveFor(SyntaxPreset.CYBERPUNK, "Java", category)
            assertTrue(
                curve.saturationDelta > 0f,
                "Cyberpunk Java/$category positive saturationDelta required, got ${curve.saturationDelta}",
            )
            if (curve.saturationDelta > maxDelta) maxDelta = curve.saturationDelta
        }
        assertTrue(
            maxDelta >= 0.3f,
            "Cyberpunk peak saturationDelta across categories must be >= 0.3f (got $maxDelta)",
        )
    }

    @Test
    fun `every delta stays inside the D-03 input range and is finite`() {
        for (preset in SyntaxPreset.entries) {
            for (language in samplingLanguages) {
                for (category in PrimitiveCategory.entries) {
                    val curve = SyntaxPresetCurves.curveFor(preset, language, category)
                    assertTrue(
                        curve.saturationDelta.isFinite(),
                        "$preset $language/$category saturationDelta must be finite",
                    )
                    assertTrue(
                        curve.lightnessDelta.isFinite(),
                        "$preset $language/$category lightnessDelta must be finite",
                    )
                    assertTrue(
                        curve.saturationDelta in -1f..1f,
                        "$preset $language/$category saturationDelta out of [-1,+1]: ${curve.saturationDelta}",
                    )
                    assertTrue(
                        curve.lightnessDelta in -1f..1f,
                        "$preset $language/$category lightnessDelta out of [-1,+1]: ${curve.lightnessDelta}",
                    )
                }
            }
        }
    }

    @Test
    fun `at least one per-language override differs from the unknown-language fallback`() {
        // Prove the LANGUAGE_OVERRIDES table is consulted before the base table.
        // Pick a language/category combination the planner pre-populated; the
        // override value must NOT equal the base-table fallback used by a
        // language that has no override.
        val python = SyntaxPresetCurves.curveFor(SyntaxPreset.WHISPER, "Python", PrimitiveCategory.COMMENT)
        val unknown = SyntaxPresetCurves.curveFor(SyntaxPreset.WHISPER, "NotARealLanguage", PrimitiveCategory.COMMENT)
        assertNotEquals(unknown, python, "Whisper Python/COMMENT must override the base-table fallback")
    }

    @Test
    fun `top-12 languages each appear in at least one preset's LANGUAGE_OVERRIDES (D-10 coverage)`() {
        // For each of the top-12 languages, find at least one (preset, category)
        // pair whose curveFor lookup differs from the unknown-language fallback —
        // proving the language has at least one override entry somewhere.
        for (language in TOP_12_LANGUAGES) {
            val hasOverride =
                SyntaxPreset.entries.any { preset ->
                    PrimitiveCategory.entries.any { category ->
                        SyntaxPresetCurves.curveFor(preset, language, category) !=
                            SyntaxPresetCurves.curveFor(preset, "NotARealLanguage", category)
                    }
                }
            assertTrue(
                hasOverride,
                "Top-12 language '$language' must appear in at least one preset's LANGUAGE_OVERRIDES (D-10)",
            )
        }
    }

    @Test
    fun `CUSTOM curveFor returns the AMBIENT identity sentinel`() {
        // CUSTOM is a sentinel — the applicator consults customOverrides directly
        // for this preset, never the static base table. Returning identity here
        // is the safest fall-through.
        assertEquals(
            CategoryCurve(0f, 0f),
            SyntaxPresetCurves.curveFor(SyntaxPreset.CUSTOM, "Java", PrimitiveCategory.KEYWORD),
            "Custom must return identity sentinel — applicator owns the override lookup",
        )
    }

    /**
     * Applies a curve via the same HSL transform pipeline Plan 50-04's
     * `SyntaxIntensityApplicator` will use: parse → HSL → add deltas → clamp
     * saturation `[0, 1]` and lightness `[AccentHsl.MIN_LIGHTNESS, MAX_LIGHTNESS]`
     * → repack to RGB. Hue invariant.
     */
    private fun applyCurveViaHsl(
        baseline: Color,
        curve: CategoryCurve,
    ): Color {
        val hsl = HslColor.fromColor(baseline)
        val newSaturation = (hsl.saturation + curve.saturationDelta).coerceIn(0f, 1f)
        val newLightness =
            (hsl.lightness + curve.lightnessDelta)
                .coerceIn(AccentHsl.MIN_LIGHTNESS, AccentHsl.MAX_LIGHTNESS)
        return HslColor.toColor(hsl.hue, newSaturation, newLightness)
    }

    /**
     * Reproduces the Phase 49 `SyntaxModeApplicator.DIM_FACTOR = 0.6` per-channel
     * RGB scale used by DIMMED_COMMENTS. Uses `.toInt()` truncation to match
     * the historic Phase 49 implementation byte-for-byte.
     */
    private fun dimByPhase49Factor(baseline: Color): Color =
        Color(
            (baseline.red * DIM_FACTOR).toInt(),
            (baseline.green * DIM_FACTOR).toInt(),
            (baseline.blue * DIM_FACTOR).toInt(),
            baseline.alpha,
        )

    private fun Color.hex(): String = "%02X%02X%02X".format(red, green, blue)

    companion object {
        private const val DIM_FACTOR = 0.6
        private const val MAX_CHANNEL_TOLERANCE = 6

        private val TOP_12_LANGUAGES =
            listOf(
                "Java",
                "Kotlin",
                "Python",
                "JavaScript",
                "TypeScript",
                "Go",
                "Rust",
                "Ruby",
                "PHP",
                "Scala",
                "Swift",
                "C#",
            )

        // Sampling set for the range-invariant sweep — keep it bounded so the
        // test stays fast while still touching the override table.
        private val samplingLanguages: List<String> = TOP_12_LANGUAGES + listOf("NotARealLanguage")
    }
}
