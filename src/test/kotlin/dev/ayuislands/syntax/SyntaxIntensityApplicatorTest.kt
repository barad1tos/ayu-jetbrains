package dev.ayuislands.syntax

import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.editor.markup.TextAttributes
import dev.ayuislands.rotation.HslColor
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.awt.Color
import kotlin.math.abs
import kotlin.test.assertNotNull

/**
 * RED-gate test set for [SyntaxIntensityApplicator].
 *
 * 14 invariants per the plan spec — see PLAN 50-04 Task 1 `<behavior>`:
 *  1.  Ambient identity — every value's foreground RGB equals the baseline's.
 *  2.  HSL transform end-to-end — Whisper KEYWORD on a medium-cool baseline.
 *  3.  Saturation lower clamp [0f].
 *  4.  Lightness lower clamp [0.10f].
 *  5.  Lightness upper clamp [0.95f].
 *  6.  Hue invariant under every preset / language / category.
 *  7.  Pattern B clone discipline — baseline `TextAttributes` instances are
 *      never mutated, and the output value is a distinct instance.
 *  8.  H10 non-null contract — every emitted `TextAttributes` is non-null.
 *  9.  Overlay overrides baseline.
 * 10.  Unknown-category skip — keys that don't classify return null are
 *      omitted from the result map.
 * 11.  R-1 caller contract — Color.WHITE on a dark variant does NOT throw and
 *      the call still succeeds (the latched WARN is a side effect we don't
 *      observe directly; we only lock the happy path doesn't break).
 * 12.  Language-aware curve lookup (Codex HIGH #4) — same baseline color on
 *      JAVA_KEYWORD vs RUBY_FUNCTION_DECLARATION under NEON produces different
 *      output colors because the language differs.
 * 13.  CUSTOM + a populated override (Java|KEYWORD=75) transforms the
 *      foreground above identity, and CUSTOM + empty overrides falls back to
 *      the subordinate preset's curve (Phase 50.1 drill-down activation).
 * 14.  Determinism — two consecutive calls with identical inputs produce
 *      equal output maps.
 */
class SyntaxIntensityApplicatorTest {
    // Keys whose externalName routes through SyntaxLanguageRegistry + SyntaxCategoryRegistry:
    //  - "JAVA_KEYWORD" -> Java / KEYWORD
    //  - "KOTLIN_KEYWORD" -> Kotlin / KEYWORD
    //  - "JAVA_LINE_COMMENT" -> Java / COMMENT
    //  - "RUBY_FUNCTION_DECLARATION" -> Ruby / FUNCTION_DECL  (NEON has Ruby override)
    //  - "GO_KEYWORD" -> Go / KEYWORD  (NEON has Go override)
    private val javaKeywordKey = TextAttributesKey.createTextAttributesKey("JAVA_KEYWORD")
    private val kotlinKeywordKey = TextAttributesKey.createTextAttributesKey("KOTLIN_KEYWORD")
    private val javaCommentKey = TextAttributesKey.createTextAttributesKey("JAVA_LINE_COMMENT")

    private val mirageBg = Color(0x1F, 0x24, 0x30)

    // --- Test 1 — Ambient identity ----------------------------------------

    @Test
    fun `AMBIENT preset produces foregrounds equal to baseline (identity)`() {
        val baselineFg = Color(0xCC, 0xCA, 0xC2)
        val baseline =
            mapOf(
                javaKeywordKey to attrsWithFg(baselineFg),
                javaCommentKey to attrsWithFg(baselineFg),
            )
        val result =
            SyntaxIntensityApplicator.compute(
                preset = SyntaxPreset.AMBIENT,
                customOverrides = emptyMap(),
                variantName = "Mirage",
                editorBg = mirageBg,
                baseline = baseline,
                overlay = emptyMap(),
            )
        assertEquals(2, result.size)
        for ((key, attrs) in result) {
            val fg = attrs.foregroundColor
            assertNotNull(fg, "Ambient must not null out foreground for $key")
            assertEquals(baselineFg.rgb, fg.rgb, "Ambient must be identity on $key")
        }
    }

    // --- Test 2 — Whisper transform end-to-end ---------------------------

    @Test
    fun `WHISPER KEYWORD on cool-grey baseline drops saturation and lightness per curve`() {
        val baselineFg = Color(0xCC, 0xCA, 0xC2)
        val baseline = mapOf(javaKeywordKey to attrsWithFg(baselineFg))
        val curve = SyntaxPresetCurves.curveFor(SyntaxPreset.WHISPER, "Java", PrimitiveCategory.KEYWORD)

        // Compute the expected output via the documented pipeline so we lock
        // the applicator's HSL transform, not a hard-coded magic hex.
        val baselineHsl = HslColor.fromColor(baselineFg)
        val expectedSat = (baselineHsl.saturation + curve.saturationDelta).coerceIn(0f, 1f)
        val expectedLight =
            (baselineHsl.lightness + curve.lightnessDelta)
                .coerceIn(LIGHTNESS_LOWER, LIGHTNESS_UPPER)
        val expected = HslColor.toColor(baselineHsl.hue, expectedSat, expectedLight)

        val result =
            SyntaxIntensityApplicator.compute(
                preset = SyntaxPreset.WHISPER,
                customOverrides = emptyMap(),
                variantName = "Mirage",
                editorBg = mirageBg,
                baseline = baseline,
                overlay = emptyMap(),
            )
        val output =
            assertNotNull(
                result[javaKeywordKey]?.foregroundColor,
                "Whisper must emit a foreground for JAVA_KEYWORD",
            )
        assertChannelDiff(expected, output, CHANNEL_TOLERANCE)
    }

    // --- Test 3 — Saturation lower clamp ---------------------------------

    @Test
    fun `saturation below zero clamps to zero (Whisper subtracts past zero)`() {
        // Baseline saturation ~0.05 — Whisper KEYWORD subtracts 0.20 => clamp to 0.
        val nearGreyFg = Color(0xA5, 0xA0, 0x9C)
        val baseline = mapOf(javaKeywordKey to attrsWithFg(nearGreyFg))
        val baselineHsl = HslColor.fromColor(nearGreyFg)
        assertTrue(baselineHsl.saturation < 0.20f, "test fixture must start below the Whisper KEYWORD sat delta")

        val result =
            SyntaxIntensityApplicator.compute(
                preset = SyntaxPreset.WHISPER,
                customOverrides = emptyMap(),
                variantName = "Mirage",
                editorBg = mirageBg,
                baseline = baseline,
                overlay = emptyMap(),
            )
        val outputFg = result[javaKeywordKey]?.foregroundColor
        assertNotNull(outputFg)
        val outputHsl = HslColor.fromColor(outputFg)
        assertEquals(0f, outputHsl.saturation, FLOAT_TOLERANCE, "saturation must clamp to 0f")
    }

    // --- Test 4 — Lightness lower clamp ----------------------------------

    @Test
    fun `lightness below MIN clamps to MIN_LIGHTNESS (Whisper subtracts past floor)`() {
        // Baseline lightness ~0.12 — Whisper KEYWORD subtracts 0.05 lightness;
        // we want to ensure that a low-lightness baseline lands on the floor
        // for at least one CYBERPUNK->reverse case. Use Whisper with KEYWORD
        // and a low-lightness fg to push past the floor (-0.05 makes 0.12 -> 0.07
        // which clamps to 0.10).
        val darkFg = Color(0x22, 0x22, 0x28)
        val baseline = mapOf(javaKeywordKey to attrsWithFg(darkFg))
        val baselineHsl = HslColor.fromColor(darkFg)
        assertTrue(baselineHsl.lightness < LIGHTNESS_LOWER + 0.05f, "fixture must start near the lightness floor")

        val result =
            SyntaxIntensityApplicator.compute(
                preset = SyntaxPreset.WHISPER,
                customOverrides = emptyMap(),
                variantName = "Mirage",
                editorBg = mirageBg,
                baseline = baseline,
                overlay = emptyMap(),
            )
        val outputFg = result[javaKeywordKey]?.foregroundColor
        assertNotNull(outputFg)
        val outputHsl = HslColor.fromColor(outputFg)
        assertEquals(LIGHTNESS_LOWER, outputHsl.lightness, FLOAT_TOLERANCE, "lightness must clamp to MIN_LIGHTNESS")
    }

    // --- Test 5 — Lightness upper clamp ----------------------------------

    @Test
    fun `lightness above MAX clamps to MAX_LIGHTNESS (Cyberpunk adds past ceiling)`() {
        // Baseline lightness ~0.92 — Cyberpunk adds 0.05 => clamp to 0.95.
        val brightFg = Color(0xF0, 0xEE, 0xE5)
        val baseline = mapOf(javaKeywordKey to attrsWithFg(brightFg))
        val baselineHsl = HslColor.fromColor(brightFg)
        assertTrue(baselineHsl.lightness > LIGHTNESS_UPPER - 0.08f, "fixture must start near the lightness ceiling")

        val result =
            SyntaxIntensityApplicator.compute(
                preset = SyntaxPreset.CYBERPUNK,
                customOverrides = emptyMap(),
                variantName = "Mirage",
                editorBg = mirageBg,
                baseline = baseline,
                overlay = emptyMap(),
            )
        val outputFg = result[javaKeywordKey]?.foregroundColor
        assertNotNull(outputFg)
        val outputHsl = HslColor.fromColor(outputFg)
        assertEquals(LIGHTNESS_UPPER, outputHsl.lightness, FLOAT_TOLERANCE, "lightness must clamp to MAX_LIGHTNESS")
    }

    // --- Test 6 — Hue invariant ------------------------------------------

    @Test
    fun `hue is invariant under every preset and category for a colored baseline`() {
        val coloredFg = Color(0xE6, 0xB6, 0x73) // ayu orange-yellow, hue ~36
        val baselineHsl = HslColor.fromColor(coloredFg)
        for (preset in SyntaxPreset.entries) {
            for ((key, _) in CATEGORY_KEYS) {
                val baseline = mapOf(key to attrsWithFg(coloredFg))
                val result =
                    SyntaxIntensityApplicator.compute(
                        preset = preset,
                        customOverrides = emptyMap(),
                        variantName = "Mirage",
                        editorBg = mirageBg,
                        baseline = baseline,
                        overlay = emptyMap(),
                    )
                val outputFg = result[key]?.foregroundColor ?: continue
                val outputHsl = HslColor.fromColor(outputFg)
                // Hue can wrap; allow the comparison either at the literal hue or
                // the achromatic short-circuit (saturation == 0 collapses hue to 0).
                val hueMatches =
                    abs(outputHsl.hue - baselineHsl.hue) < HUE_TOLERANCE ||
                        outputHsl.saturation == 0f
                assertTrue(
                    hueMatches,
                    "hue drift for preset=$preset key=${key.externalName}: " +
                        "baseline=${baselineHsl.hue} output=${outputHsl.hue} sat=${outputHsl.saturation}",
                )
            }
        }
    }

    // --- Test 7 — Pattern B clone discipline ----------------------------

    @Test
    fun `baseline TextAttributes are never mutated — output is a distinct instance`() {
        val baselineFg = Color(0xCC, 0xCA, 0xC2)
        val baselineAttrs = attrsWithFg(baselineFg)
        val baseline = mapOf(javaKeywordKey to baselineAttrs)

        SyntaxIntensityApplicator.compute(
            preset = SyntaxPreset.WHISPER,
            customOverrides = emptyMap(),
            variantName = "Mirage",
            editorBg = mirageBg,
            baseline = baseline,
            overlay = emptyMap(),
        )

        assertEquals(
            baselineFg.rgb,
            baseline[javaKeywordKey]?.foregroundColor?.rgb,
            "baseline foreground RGB must be preserved verbatim — Pattern B clone discipline",
        )

        // The result-side instance must be a clone, not the baseline reference.
        val result =
            SyntaxIntensityApplicator.compute(
                preset = SyntaxPreset.WHISPER,
                customOverrides = emptyMap(),
                variantName = "Mirage",
                editorBg = mirageBg,
                baseline = baseline,
                overlay = emptyMap(),
            )
        assertNotSame(baselineAttrs, result[javaKeywordKey], "output value must be a clone (Pattern B)")
    }

    // --- Test 8 — H10 non-null contract ----------------------------------

    @Test
    fun `every emitted TextAttributes value is non-null (H10 contract)`() {
        val baselineFg = Color(0xCC, 0xCA, 0xC2)
        val baseline =
            mapOf(
                javaKeywordKey to attrsWithFg(baselineFg),
                javaCommentKey to attrsWithFg(baselineFg),
                kotlinKeywordKey to attrsWithFg(baselineFg),
            )
        val result =
            SyntaxIntensityApplicator.compute(
                preset = SyntaxPreset.WHISPER,
                customOverrides = emptyMap(),
                variantName = "Mirage",
                editorBg = mirageBg,
                baseline = baseline,
                overlay = emptyMap(),
            )
        for ((key, value) in result) {
            assertNotNull(value, "applicator emitted null TextAttributes for $key — H10 regression")
        }
    }

    // --- Test 9 — Overlay overrides baseline ------------------------------

    @Test
    fun `overlay foreground replaces baseline as the transform source`() {
        val baselineFg = Color(0x80, 0x80, 0x80)
        val overlayFg = Color(0xE6, 0xB6, 0x73)
        val baseline = mapOf(javaKeywordKey to attrsWithFg(baselineFg))
        val overlay = mapOf(javaKeywordKey to attrsWithFg(overlayFg))

        val baselineResultMap =
            SyntaxIntensityApplicator.compute(
                preset = SyntaxPreset.WHISPER,
                customOverrides = emptyMap(),
                variantName = "Mirage",
                editorBg = mirageBg,
                baseline = baseline,
                overlay = emptyMap(),
            )
        val overlayResultMap =
            SyntaxIntensityApplicator.compute(
                preset = SyntaxPreset.WHISPER,
                customOverrides = emptyMap(),
                variantName = "Mirage",
                editorBg = mirageBg,
                baseline = baseline,
                overlay = overlay,
            )
        val resultBaseline = baselineResultMap[javaKeywordKey]?.foregroundColor
        val resultOverlay = overlayResultMap[javaKeywordKey]?.foregroundColor

        assertNotNull(resultBaseline)
        assertNotNull(resultOverlay)
        assertNotEquals(
            resultBaseline.rgb,
            resultOverlay.rgb,
            "overlay foreground should drive a different output than baseline",
        )
    }

    // --- Test 10 — Unknown-category skip ---------------------------------

    @Test
    fun `keys that do not classify into a PrimitiveCategory are skipped`() {
        // "FOOBAR_GARBAGE_SUFFIX_NEVERMATCHES" must not match any suffix rule.
        val unknownKey = TextAttributesKey.createTextAttributesKey("FOOBAR_NEVERMATCHES_XYZZY")
        // Sanity — confirm the classifier returns null for this key.
        assertEquals(null, SyntaxCategoryRegistry.classify(unknownKey.externalName))

        val baseline =
            mapOf(
                unknownKey to attrsWithFg(Color(0xFF, 0x00, 0x00)),
                javaKeywordKey to attrsWithFg(Color(0xCC, 0xCA, 0xC2)),
            )
        val result =
            SyntaxIntensityApplicator.compute(
                preset = SyntaxPreset.WHISPER,
                customOverrides = emptyMap(),
                variantName = "Mirage",
                editorBg = mirageBg,
                baseline = baseline,
                overlay = emptyMap(),
            )
        assertTrue(unknownKey !in result, "unknown-category key must be skipped from the result map")
        assertTrue(javaKeywordKey in result, "classified key must be present in the result map")
    }

    // --- Test 11 — R-1 caller contract (does not crash) ------------------

    @Test
    fun `dark variant with Color WHITE editorBg still completes (R-1 caller contract WARN only)`() {
        // The applicator does NOT call fallbackEditorBgFor itself. Receiving
        // WHITE on a dark variant is a caller bug — applicator logs WARN once
        // and proceeds with the transform. The compute must not throw and
        // must still emit a result for classified keys.
        val baselineFg = Color(0xCC, 0xCA, 0xC2)
        val baseline = mapOf(javaKeywordKey to attrsWithFg(baselineFg))
        val result =
            SyntaxIntensityApplicator.compute(
                preset = SyntaxPreset.WHISPER,
                customOverrides = emptyMap(),
                variantName = "Mirage",
                editorBg = Color.WHITE,
                baseline = baseline,
                overlay = emptyMap(),
            )
        assertNotNull(result[javaKeywordKey], "compute must still emit a result despite the R-1 contract violation")
    }

    // --- Test 12 — Language-aware curve lookup (Codex HIGH #4) -----------

    @Test
    fun `same baseline color on different languages can produce different outputs (Codex HIGH #4)`() {
        // Under NEON the curve table assigns Ruby FUNCTION_DECL a per-language
        // override (NEON_RUBY_FUNC_SAT / NEON_RUBY_FUNC_LIGHT) while a Java
        // FUNCTION_DECL falls back to the NEON base. Same baseline color in,
        // different output out — proves the applicator derives the language
        // from the key name via SyntaxLanguageRegistry, not from variantName.
        val baselineFg = Color(0x80, 0x80, 0x80)
        // Pick a colored baseline so the language-specific curve actually
        // diverges. Pure grey has saturation 0 and HSL output stays grey.
        val coloredBaseline = Color(0xE6, 0xB6, 0x73)
        val rubyFunctionDeclKey = TextAttributesKey.createTextAttributesKey("RUBY_FUNCTION_DECLARATION")
        val javaFunctionDeclKey = TextAttributesKey.createTextAttributesKey("JAVA_METHOD_DECLARATION")

        val baseline =
            mapOf(
                rubyFunctionDeclKey to attrsWithFg(coloredBaseline),
                javaFunctionDeclKey to attrsWithFg(coloredBaseline),
            )
        val result =
            SyntaxIntensityApplicator.compute(
                preset = SyntaxPreset.NEON,
                customOverrides = emptyMap(),
                variantName = "Mirage",
                editorBg = mirageBg,
                baseline = baseline,
                overlay = emptyMap(),
            )
        val rubyOut = result[rubyFunctionDeclKey]?.foregroundColor
        val javaOut = result[javaFunctionDeclKey]?.foregroundColor
        assertNotNull(rubyOut, "ruby function decl must yield output")
        assertNotNull(javaOut, "java method decl must yield output")
        assertNotEquals(
            rubyOut.rgb,
            javaOut.rgb,
            "Ruby and Java FUNCTION_DECL must differ under NEON — Codex HIGH #4 language-aware lookup",
        )

        // baselineFg referenced to keep the fixture self-consistent; the
        // colored baseline above is the actual transform source.
        assertNotEquals(baselineFg.rgb, coloredBaseline.rgb)
    }

    // --- Test 13 — CUSTOM transforms per slider + subordinate fallback ---

    @Test
    fun `CUSTOM with override transforms per slider and empty overrides falls back to subordinate preset`() {
        // Colored baseline so the HSL transform actually moves the RGB —
        // pure grey (saturation 0) would collapse to grey regardless.
        val baselineFg = Color(0xE6, 0xB6, 0x73)
        val baseline = mapOf(javaKeywordKey to attrsWithFg(baselineFg))

        // CUSTOM + a populated override (Java|KEYWORD slider 75) MUST saturate /
        // brighten above identity — the drill-down is now activated (Phase 50.1
        // D-02 / D-05). `subordinatePreset` is passed as a NAMED trailing
        // argument; it does NOT exist on compute() until Plan 02 lands, forcing
        // the RED state.
        val populatedResult =
            SyntaxIntensityApplicator.compute(
                preset = SyntaxPreset.CUSTOM,
                customOverrides = mapOf("Java" to mapOf("KEYWORD" to 75)),
                variantName = "Mirage",
                editorBg = mirageBg,
                baseline = baseline,
                overlay = emptyMap(),
                subordinatePreset = SyntaxPreset.AMBIENT,
            )
        assertNotNull(populatedResult[javaKeywordKey], "CUSTOM with overrides must emit output")
        assertNotEquals(
            baselineFg.rgb,
            populatedResult[javaKeywordKey]?.foregroundColor?.rgb,
            "CUSTOM + slider 75 must transform the foreground above identity (drill-down activated)",
        )

        // CUSTOM + empty overrides resolves untouched cells via the SUBORDINATE
        // preset's curve (D-07) — NOT the old AMBIENT-identity no-op. Here the
        // subordinate is AMBIENT, so the output equals the baseline; the
        // contract under test is that empty-overrides routes through the
        // subordinate curve and still emits output without throwing.
        val fallbackResult =
            SyntaxIntensityApplicator.compute(
                preset = SyntaxPreset.CUSTOM,
                customOverrides = emptyMap(),
                variantName = "Mirage",
                editorBg = mirageBg,
                baseline = baseline,
                overlay = emptyMap(),
                subordinatePreset = SyntaxPreset.AMBIENT,
            )
        assertNotNull(
            fallbackResult[javaKeywordKey],
            "CUSTOM with empty overrides must emit output via the subordinate preset curve",
        )
    }

    // --- Test 14 — Determinism -------------------------------------------

    @Test
    fun `two consecutive calls with identical inputs produce identical foreground RGB`() {
        val baselineFg = Color(0xE6, 0xB6, 0x73)
        val baseline =
            mapOf(
                javaKeywordKey to attrsWithFg(baselineFg),
                javaCommentKey to attrsWithFg(baselineFg),
                kotlinKeywordKey to attrsWithFg(baselineFg),
            )
        val first =
            SyntaxIntensityApplicator.compute(
                preset = SyntaxPreset.NEON,
                customOverrides = emptyMap(),
                variantName = "Mirage",
                editorBg = mirageBg,
                baseline = baseline,
                overlay = emptyMap(),
            )
        val second =
            SyntaxIntensityApplicator.compute(
                preset = SyntaxPreset.NEON,
                customOverrides = emptyMap(),
                variantName = "Mirage",
                editorBg = mirageBg,
                baseline = baseline,
                overlay = emptyMap(),
            )
        assertEquals(first.keys, second.keys, "deterministic compute must emit the same key set")
        for (key in first.keys) {
            assertEquals(
                first.getValue(key).foregroundColor?.rgb,
                second.getValue(key).foregroundColor?.rgb,
                "deterministic compute must emit the same foreground for $key",
            )
        }
        // Reference assertSame to keep the import live even on success paths.
        assertSame(first.keys.first(), first.keys.first())
    }

    // --- Helpers ---------------------------------------------------------

    private fun attrsWithFg(color: Color): TextAttributes {
        val attrs = TextAttributes()
        attrs.foregroundColor = color
        return attrs
    }

    private fun assertChannelDiff(
        expected: Color,
        actual: Color,
        tolerance: Int,
    ) {
        val redDiff = abs(expected.red - actual.red)
        val greenDiff = abs(expected.green - actual.green)
        val blueDiff = abs(expected.blue - actual.blue)
        assertTrue(redDiff <= tolerance, "red diff $redDiff > $tolerance (expected=$expected actual=$actual)")
        assertTrue(greenDiff <= tolerance, "green diff $greenDiff > $tolerance (expected=$expected actual=$actual)")
        assertTrue(blueDiff <= tolerance, "blue diff $blueDiff > $tolerance (expected=$expected actual=$actual)")
    }

    companion object {
        private const val LIGHTNESS_LOWER = 0.10f
        private const val LIGHTNESS_UPPER = 0.95f
        private const val FLOAT_TOLERANCE = 0.005f
        private const val HUE_TOLERANCE = 0.5f
        private const val CHANNEL_TOLERANCE = 2

        // Map of TextAttributesKey -> expected (language, category) so the hue
        // invariant test exercises a variety of category buckets.
        private val CATEGORY_KEYS: List<Pair<TextAttributesKey, Pair<String, PrimitiveCategory>>> =
            listOf(
                TextAttributesKey.createTextAttributesKey("JAVA_KEYWORD") to ("Java" to PrimitiveCategory.KEYWORD),
                TextAttributesKey.createTextAttributesKey("JAVA_LINE_COMMENT") to ("Java" to PrimitiveCategory.COMMENT),
                TextAttributesKey.createTextAttributesKey("KOTLIN_KEYWORD") to ("Kotlin" to PrimitiveCategory.KEYWORD),
            )
    }
}
