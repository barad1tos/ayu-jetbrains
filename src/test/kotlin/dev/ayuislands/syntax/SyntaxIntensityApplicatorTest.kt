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
import java.awt.Font
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
    private val javaLocalVarKey = TextAttributesKey.createTextAttributesKey("JAVA_LOCAL_VARIABLE")

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
            compute(
                preset = SyntaxPreset.AMBIENT,
                customOverrides = emptyMap(),
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
    fun `WHISPER KEYWORD on a bright Ayu baseline washes out via negative chroma intent`() {
        // KEYWORD is a HIGH-S token: saturationDelta is 0 and Whisper carries a
        // NEGATIVE chroma intent, so a bright (L > 0.5) baseline moves further
        // away from 0.5 (lighter) and loses chroma — quieter, not darker.
        val baselineFg = Color(0xFF, 0xAD, 0x66) // Ayu keyword, S=1.0 L=0.70
        val baseline = mapOf(javaKeywordKey to attrsWithFg(baselineFg))
        val curve = SyntaxPresetCurves.curveFor(SyntaxPreset.WHISPER, "Java", PrimitiveCategory.KEYWORD)

        // Compute the expected output via the documented signed-intent pipeline
        // so we lock the applicator's HSL transform, not a hard-coded magic hex.
        val baselineHsl = HslColor.fromColor(baselineFg)
        val expectedSat = (baselineHsl.saturation + curve.saturationDelta).coerceIn(0f, 1f)
        val expectedLight = expectedLightnessVia(baselineHsl.lightness, curve)
        val expected = HslColor.toColor(baselineHsl.hue, expectedSat, expectedLight)

        val result =
            compute(
                preset = SyntaxPreset.WHISPER,
                customOverrides = emptyMap(),
                baseline = baseline,
                overlay = emptyMap(),
            )
        val output =
            assertNotNull(
                result[javaKeywordKey]?.foregroundColor,
                "Whisper must emit a foreground for JAVA_KEYWORD",
            )
        assertChannelDiff(expected, output)
    }

    // --- Test 3 — Saturation lower clamp ---------------------------------

    @Test
    fun `saturation below zero clamps to zero (Whisper subtracts past zero on a low-S token)`() {
        // LOCAL_VAR is a LOW-S token with an additive saturation lever. Whisper
        // subtracts 0.15; a near-grey baseline (S ~ 0.05) clamps to 0.
        val nearGreyFg = Color(0xA5, 0xA0, 0x9C)
        val baseline = mapOf(javaLocalVarKey to attrsWithFg(nearGreyFg))
        val baselineHsl = HslColor.fromColor(nearGreyFg)
        assertTrue(baselineHsl.saturation < 0.15f, "test fixture must start below the Whisper LOCAL_VAR sat delta")

        val result =
            compute(
                preset = SyntaxPreset.WHISPER,
                customOverrides = emptyMap(),
                baseline = baseline,
                overlay = emptyMap(),
            )
        val outputFg = result[javaLocalVarKey]?.foregroundColor
        assertNotNull(outputFg)
        val outputHsl = HslColor.fromColor(outputFg)
        assertEquals(0f, outputHsl.saturation, FLOAT_TOLERANCE, "saturation must clamp to 0f")
    }

    // --- Test 4 — Lightness lower clamp (syntax readability floor) -------

    @Test
    fun `lightness clamps to the 0_48 syntax floor when the intent drives it lower`() {
        // KEYWORD is high-S with a NEGATIVE Whisper intent. A mid-dark baseline
        // (L just below 0.5) sits on the LOW flank, so a negative intent moves L
        // DOWN (away from 0.5) and the syntax-readability floor (0.48) clamps it
        // — guaranteeing transformed token text stays legible on Mirage bg.
        val midDarkFg = Color(0x3A, 0x42, 0x55) // L ~ 0.28, below 0.5
        val baseline = mapOf(javaKeywordKey to attrsWithFg(midDarkFg))
        val baselineHsl = HslColor.fromColor(midDarkFg)
        assertTrue(baselineHsl.lightness < MID_LIGHTNESS, "fixture must start on the low flank")

        val result =
            compute(
                preset = SyntaxPreset.WHISPER,
                customOverrides = emptyMap(),
                baseline = baseline,
                overlay = emptyMap(),
            )
        val outputFg = result[javaKeywordKey]?.foregroundColor
        assertNotNull(outputFg)
        val outputHsl = HslColor.fromColor(outputFg)
        assertEquals(
            SYNTAX_FLOOR,
            outputHsl.lightness,
            FLOAT_TOLERANCE,
            "lightness must clamp to the 0.48 syntax readability floor",
        )
    }

    // --- Test 5 — Lightness upper clamp ----------------------------------

    @Test
    fun `lightness clamps to MAX_LIGHTNESS when the intent drives it higher`() {
        // KEYWORD is high-S; Whisper's NEGATIVE intent on a very bright baseline
        // (L ~ 0.92, high flank) moves L UP (away from 0.5) past the ceiling, so
        // it clamps to MAX_LIGHTNESS.
        val brightFg = Color(0xF0, 0xEE, 0xE5)
        val baseline = mapOf(javaKeywordKey to attrsWithFg(brightFg))
        val baselineHsl = HslColor.fromColor(brightFg)
        assertTrue(baselineHsl.lightness > LIGHTNESS_UPPER - 0.08f, "fixture must start near the lightness ceiling")

        val result =
            compute(
                preset = SyntaxPreset.WHISPER,
                customOverrides = emptyMap(),
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
                    compute(
                        preset = preset,
                        customOverrides = emptyMap(),
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
    fun `baseline TextAttributes are never mutated - output is a distinct instance`() {
        val baselineFg = Color(0xCC, 0xCA, 0xC2)
        val baselineAttrs = attrsWithFg(baselineFg)
        val baseline = mapOf(javaKeywordKey to baselineAttrs)

        compute(
            preset = SyntaxPreset.WHISPER,
            customOverrides = emptyMap(),
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
            compute(
                preset = SyntaxPreset.WHISPER,
                customOverrides = emptyMap(),
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
            compute(
                preset = SyntaxPreset.WHISPER,
                customOverrides = emptyMap(),
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
            compute(
                preset = SyntaxPreset.WHISPER,
                customOverrides = emptyMap(),
                baseline = baseline,
                overlay = emptyMap(),
            )
        val overlayResultMap =
            compute(
                preset = SyntaxPreset.WHISPER,
                customOverrides = emptyMap(),
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
            compute(
                preset = SyntaxPreset.WHISPER,
                customOverrides = emptyMap(),
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
            compute(
                preset = SyntaxPreset.WHISPER,
                customOverrides = emptyMap(),
                baseline = baseline,
                overlay = emptyMap(),
                options = ComputeOptions(editorBg = Color.WHITE),
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
            compute(
                preset = SyntaxPreset.NEON,
                customOverrides = emptyMap(),
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
            compute(
                preset = SyntaxPreset.CUSTOM,
                customOverrides = mapOf("Java" to mapOf("KEYWORD" to 75)),
                baseline = baseline,
                overlay = emptyMap(),
                options = ComputeOptions(subordinatePreset = SyntaxPreset.AMBIENT),
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
            compute(
                preset = SyntaxPreset.CUSTOM,
                customOverrides = emptyMap(),
                baseline = baseline,
                overlay = emptyMap(),
                options = ComputeOptions(subordinatePreset = SyntaxPreset.AMBIENT),
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
            compute(
                preset = SyntaxPreset.NEON,
                customOverrides = emptyMap(),
                baseline = baseline,
                overlay = emptyMap(),
            )
        val second =
            compute(
                preset = SyntaxPreset.NEON,
                customOverrides = emptyMap(),
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

    // --- Test 14b — CUSTOM font style (Part A backend) -------------------

    @Test
    fun `CUSTOM with a style override sets clone fontType to the bitmask`() {
        val baselineFg = Color(0xE6, 0xB6, 0x73)
        val baseline = mapOf(javaKeywordKey to attrsWithFg(baselineFg))
        val result =
            compute(
                preset = SyntaxPreset.CUSTOM,
                customOverrides = emptyMap(),
                baseline = baseline,
                overlay = emptyMap(),
                options =
                    ComputeOptions(
                        subordinatePreset = SyntaxPreset.AMBIENT,
                        customStyles = mapOf("Java" to mapOf("KEYWORD" to BOLD_ITALIC_MASK)),
                    ),
            )
        val attrs = assertNotNull(result[javaKeywordKey], "CUSTOM must emit output for the styled key")
        assertEquals(
            BOLD_ITALIC_MASK,
            attrs.fontType,
            "CUSTOM + style override must set fontType to the supplied bitmask",
        )
    }

    @Test
    fun `CUSTOM with no style override leaves fontType untouched (inherits source)`() {
        // Source carries an explicit ITALIC fontType; with no customStyles cell
        // the clone must keep that inherited style (sparse — absent = inherit).
        val sourceAttrs = attrsWithFg(Color(0xE6, 0xB6, 0x73))
        sourceAttrs.fontType = Font.ITALIC
        val baseline = mapOf(javaKeywordKey to sourceAttrs)
        val result =
            compute(
                preset = SyntaxPreset.CUSTOM,
                customOverrides = emptyMap(),
                baseline = baseline,
                overlay = emptyMap(),
                options = ComputeOptions(subordinatePreset = SyntaxPreset.AMBIENT),
            )
        val attrs = assertNotNull(result[javaKeywordKey])
        assertEquals(Font.ITALIC, attrs.fontType, "absent style cell must inherit the source fontType")
    }

    @Test
    fun `named preset ignores customStyles even when a cell is present`() {
        // Under a NAMED preset (NEON), customStyles must be inert — the cell
        // exists but the source style (PLAIN) survives untouched.
        val baseline = mapOf(javaKeywordKey to attrsWithFg(Color(0xE6, 0xB6, 0x73)))
        val result =
            compute(
                preset = SyntaxPreset.NEON,
                customOverrides = emptyMap(),
                baseline = baseline,
                overlay = emptyMap(),
                options =
                    ComputeOptions(
                        customStyles = mapOf("Java" to mapOf("KEYWORD" to BOLD_ITALIC_MASK)),
                    ),
            )
        val attrs = assertNotNull(result[javaKeywordKey])
        assertEquals(Font.PLAIN, attrs.fontType, "named preset must NOT apply customStyles — style stays PLAIN")
    }

    @Test
    fun `style override is orthogonal - a styled cell with no slider still gets subordinate color and the style`() {
        // The cell has a style override but NO intensity slider. With CUSTOM +
        // subordinate AMBIENT, the foreground rides the subordinate (identity)
        // curve while the style is applied independently — proving fontType and
        // foregroundColor are orthogonal TextAttributes fields.
        val baselineFg = Color(0xE6, 0xB6, 0x73)
        val baseline = mapOf(javaKeywordKey to attrsWithFg(baselineFg))
        val result =
            compute(
                preset = SyntaxPreset.CUSTOM,
                customOverrides = emptyMap(), // slider absent for this cell
                baseline = baseline,
                overlay = emptyMap(),
                options =
                    ComputeOptions(
                        subordinatePreset = SyntaxPreset.AMBIENT,
                        customStyles = mapOf("Java" to mapOf("KEYWORD" to Font.BOLD)),
                    ),
            )
        val attrs = assertNotNull(result[javaKeywordKey])
        // Subordinate AMBIENT is identity — color equals the baseline.
        assertEquals(baselineFg.rgb, attrs.foregroundColor?.rgb, "no slider -> subordinate AMBIENT identity color")
        // The style still applies independently.
        assertEquals(Font.BOLD, attrs.fontType, "style override applies even with no slider (orthogonal)")
    }

    @Test
    fun `default compute call omits customStyles and leaves fontType untouched (positional callers unaffected)`() {
        // Locks the trailing-defaulted contract: an existing positional caller
        // that passes only the original six args (no customStyles) compiles and
        // never touches fontType, even under CUSTOM.
        val sourceAttrs = attrsWithFg(Color(0xE6, 0xB6, 0x73))
        sourceAttrs.fontType = Font.BOLD
        val baseline = mapOf(javaKeywordKey to sourceAttrs)
        val result =
            compute(
                preset = SyntaxPreset.CUSTOM,
                customOverrides = mapOf("Java" to mapOf("KEYWORD" to 75)),
                baseline = baseline,
                overlay = emptyMap(),
                options = ComputeOptions(subordinatePreset = SyntaxPreset.AMBIENT),
            )
        val attrs = assertNotNull(result[javaKeywordKey])
        assertEquals(Font.BOLD, attrs.fontType, "omitted customStyles must leave the source fontType intact")
    }

    // --- Test 15 — Signed chroma intent resolver -------------------------

    @Test
    fun `positive intent on a high-L token decreases lightness toward 0_5 (louder)`() {
        // Neon KEYWORD is a high-S token with a POSITIVE intent. A bright (L > 0.5)
        // baseline sits on the high flank, so +intent moves L DOWN toward 0.5 —
        // chroma up, louder. No clamp interferes (lands well inside [0.48, 0.95]).
        val brightHighFlank = Color(0xFF, 0xAD, 0x66) // L ~ 0.70
        val output = transformVia(SyntaxPreset.NEON, javaKeywordKey, brightHighFlank)
        val inHsl = HslColor.fromColor(brightHighFlank)
        val outHsl = HslColor.fromColor(output)
        assertTrue(
            outHsl.lightness < inHsl.lightness,
            "positive intent on high-L must lower L (${outHsl.lightness} < ${inHsl.lightness})",
        )
    }

    @Test
    fun `positive intent on a low-L token increases lightness toward 0_5 (louder)`() {
        // Same Neon KEYWORD +intent, but a dark (L < 0.5) baseline sits on the
        // low flank, so +intent moves L UP toward 0.5. The syntax floor (0.48)
        // does not interfere because the resolved L stays above it here.
        val darkLowFlank = Color(0x4D, 0x55, 0x6B) // L ~ 0.36, below 0.5
        val output = transformVia(SyntaxPreset.NEON, javaKeywordKey, darkLowFlank)
        val inHsl = HslColor.fromColor(darkLowFlank)
        val outHsl = HslColor.fromColor(output)
        assertTrue(inHsl.lightness < MID_LIGHTNESS, "fixture must start on the low flank")
        assertTrue(
            outHsl.lightness > inHsl.lightness,
            "positive intent on low-L must raise L (${outHsl.lightness} > ${inHsl.lightness})",
        )
    }

    @Test
    fun `negative intent moves lightness in the opposite direction (quieter)`() {
        // Whisper KEYWORD carries a NEGATIVE intent. On a bright (high-flank)
        // baseline that means UP (away from 0.5) — the mirror of the positive
        // case in the high-L test above.
        val brightHighFlank = Color(0xFF, 0xAD, 0x66) // L ~ 0.70
        val output = transformVia(SyntaxPreset.WHISPER, javaKeywordKey, brightHighFlank)
        val inHsl = HslColor.fromColor(brightHighFlank)
        val outHsl = HslColor.fromColor(output)
        assertTrue(
            outHsl.lightness > inHsl.lightness,
            "negative intent on high-L must raise L away from 0.5 (${outHsl.lightness} > ${inHsl.lightness})",
        )
    }

    @Test
    fun `absoluteLightness path applies the lightness delta literally (Whisper COMMENT dim)`() {
        // Whisper COMMENT rides the absoluteLightness path: literal additive L
        // (down), bypassing the intent flip. A medium-cool comment baseline must
        // get DARKER even though it sits on the low flank (where a negative
        // INTENT would have moved it up). This is the regression lock for the
        // COMMENT exemption.
        val commentFg = Color(0x5C, 0x67, 0x73) // L ~ 0.41, below 0.5
        val output = transformVia(SyntaxPreset.WHISPER, javaCommentKey, commentFg)
        val inHsl = HslColor.fromColor(commentFg)
        val outHsl = HslColor.fromColor(output)
        assertTrue(inHsl.lightness < MID_LIGHTNESS, "comment fixture must start on the low flank")
        assertTrue(
            outHsl.lightness < inHsl.lightness,
            "absoluteLightness COMMENT dim must DARKEN (${outHsl.lightness} < ${inHsl.lightness}), " +
                "not wash up as a negative intent would",
        )
        val curve = SyntaxPresetCurves.curveFor(SyntaxPreset.WHISPER, "Java", PrimitiveCategory.COMMENT)
        assertTrue(curve.absoluteLightness, "Whisper COMMENT must use the absoluteLightness path")
        assertEquals(
            expectedLightnessVia(inHsl.lightness, curve),
            outHsl.lightness,
            FLOAT_TOLERANCE,
            "absoluteLightness output must equal the literal additive resolve",
        )
    }

    // --- Test 16 — Syntax readability floor + constant value -------------

    @Test
    fun `SYNTAX_MIN_LIGHTNESS constant is the documented 0_48 readability floor`() {
        // Lock the production constant so the duplicated test literal can't drift.
        assertEquals(SYNTAX_FLOOR, SyntaxIntensityApplicator.SYNTAX_MIN_LIGHTNESS, "syntax floor must be 0.48")
    }

    @Test
    fun `intent path never resolves below the 0_48 syntax floor`() {
        // Sweep the named presets over a low-flank colored baseline; no high-S
        // intent token may resolve below the readability floor.
        val baselines =
            listOf(
                Color(0x33, 0x3A, 0x4A),
                Color(0x4D, 0x55, 0x6B),
                Color(0xFF, 0xAD, 0x66),
            )
        for (preset in listOf(SyntaxPreset.WHISPER, SyntaxPreset.NEON, SyntaxPreset.CYBERPUNK)) {
            for (fg in baselines) {
                val output = transformVia(preset, javaKeywordKey, fg)
                val outHsl = HslColor.fromColor(output)
                assertTrue(
                    outHsl.lightness >= SYNTAX_FLOOR - FLOAT_TOLERANCE,
                    "$preset KEYWORD on #${fg.hex()} resolved L ${outHsl.lightness} below the 0.48 floor",
                )
            }
        }
    }

    @Test
    fun `overlay-only keys are transformed even when absent from baseline`() {
        val hclBlockNameKey = TextAttributesKey.createTextAttributesKey("HCL.BLOCK_ONLY_NAME_KEY")
        val overlayColor = Color(0x59, 0xC2, 0xFF)
        val result =
            compute(
                preset = SyntaxPreset.AMBIENT,
                customOverrides = emptyMap(),
                baseline = emptyMap(),
                overlay = mapOf(hclBlockNameKey to attrsWithFg(overlayColor)),
            )

        assertEquals(
            overlayColor.rgb,
            assertNotNull(result[hclBlockNameKey]?.foregroundColor).rgb,
            "overlay-only semantic keys must not be dropped before transformation",
        )
    }

    @Test
    fun `Groovy-specific GString key uses the Groovy custom string cell`() {
        val gStringKey = TextAttributesKey.createTextAttributesKey("GString")
        val sourceColor = Color(0xD5, 0xFF, 0x80)
        val baseline =
            mapOf(
                gStringKey to attrsWithFg(sourceColor),
            )

        val result =
            compute(
                preset = SyntaxPreset.CUSTOM,
                customOverrides = mapOf("Groovy" to mapOf("STRING_LITERAL" to 75)),
                baseline = baseline,
                overlay = emptyMap(),
                options =
                    ComputeOptions(
                        subordinatePreset = SyntaxPreset.AMBIENT,
                        customStyles = mapOf("Groovy" to mapOf("STRING_LITERAL" to Font.BOLD)),
                    ),
            )

        val attrs = assertNotNull(result[gStringKey], "GString must not be skipped")
        assertEquals(Font.BOLD, attrs.fontType, "GString must use the Groovy STRING_LITERAL style")
        assertNotEquals(
            sourceColor.rgb,
            attrs.foregroundColor?.rgb,
            "GString must use the Groovy STRING_LITERAL slider, not the Ambient fallback",
        )
    }

    @Test
    fun `Groovy-specific declaration and variable keys use the Groovy custom cells`() {
        val constructorKey = TextAttributesKey.createTextAttributesKey("Groovy constructor declaration")
        val reassignedVariableKey = TextAttributesKey.createTextAttributesKey("Groovy reassigned var")
        val constructorColor = Color(0xFF, 0xCC, 0x66)
        val reassignedVariableColor = Color(0xDF, 0xBF, 0xFF)
        val baseline =
            mapOf(
                constructorKey to attrsWithFg(constructorColor),
                reassignedVariableKey to attrsWithFg(reassignedVariableColor),
            )

        val result =
            compute(
                preset = SyntaxPreset.CUSTOM,
                customOverrides =
                    mapOf(
                        "Groovy" to
                            mapOf(
                                "FUNCTION_DECL" to 75,
                                "LOCAL_VAR" to 25,
                            ),
                    ),
                baseline = baseline,
                overlay = emptyMap(),
                options =
                    ComputeOptions(
                        subordinatePreset = SyntaxPreset.AMBIENT,
                        customStyles =
                            mapOf(
                                "Groovy" to
                                    mapOf(
                                        "FUNCTION_DECL" to Font.BOLD,
                                        "LOCAL_VAR" to Font.ITALIC,
                                    ),
                            ),
                    ),
            )

        val constructorAttrs = assertNotNull(result[constructorKey], "Groovy constructor must not be skipped")
        assertEquals(Font.BOLD, constructorAttrs.fontType, "Groovy constructor must use the FUNCTION_DECL style")
        assertNotEquals(
            constructorColor.rgb,
            assertNotNull(constructorAttrs.foregroundColor).rgb,
            "Groovy constructor must use the Groovy FUNCTION_DECL slider",
        )

        val variableAttrs =
            assertNotNull(result[reassignedVariableKey], "Groovy reassigned var must not be skipped")
        assertEquals(Font.ITALIC, variableAttrs.fontType, "Groovy reassigned var must use the LOCAL_VAR style")
        assertNotEquals(
            reassignedVariableColor.rgb,
            assertNotNull(variableAttrs.foregroundColor).rgb,
            "Groovy reassigned var must use the Groovy LOCAL_VAR slider",
        )
    }

    @Test
    fun `bare Groovy signature keys use class and generics custom cells`() {
        val classKey = TextAttributesKey.createTextAttributesKey("Class")
        val typeParameterKey = TextAttributesKey.createTextAttributesKey("Type parameter")
        val classColor = Color(0x73, 0xD0, 0xFF)
        val typeParameterColor = Color(0x5C, 0xCF, 0xE6)

        val result =
            compute(
                preset = SyntaxPreset.CUSTOM,
                customOverrides =
                    mapOf(
                        "Other" to
                            mapOf(
                                "CLASS_DECL" to 75,
                                "GENERICS" to 25,
                            ),
                    ),
                baseline =
                    mapOf(
                        classKey to attrsWithFg(classColor),
                        typeParameterKey to attrsWithFg(typeParameterColor),
                    ),
                overlay = emptyMap(),
                options =
                    ComputeOptions(
                        subordinatePreset = SyntaxPreset.AMBIENT,
                        customStyles =
                            mapOf(
                                "Other" to
                                    mapOf(
                                        "CLASS_DECL" to Font.BOLD,
                                        "GENERICS" to Font.ITALIC,
                                    ),
                            ),
                    ),
            )

        val classAttrs = assertNotNull(result[classKey], "Class must not be skipped")
        assertEquals(Font.BOLD, classAttrs.fontType, "Class must use the CLASS_DECL custom style")
        assertNotEquals(
            classColor.rgb,
            assertNotNull(classAttrs.foregroundColor).rgb,
            "Class must use the CLASS_DECL custom slider",
        )

        val typeParameterAttrs = assertNotNull(result[typeParameterKey], "Type parameter must not be skipped")
        assertEquals(Font.ITALIC, typeParameterAttrs.fontType, "Type parameter must use the GENERICS custom style")
        assertNotEquals(
            typeParameterColor.rgb,
            assertNotNull(typeParameterAttrs.foregroundColor).rgb,
            "Type parameter must use the GENERICS custom slider",
        )
    }

    @Test
    fun `generic bare String key does not use the Groovy custom string cell`() {
        val stringKey = TextAttributesKey.createTextAttributesKey("String")
        val sourceColor = Color(0xD5, 0xFF, 0x80)
        val baseline = mapOf(stringKey to attrsWithFg(sourceColor))

        val result =
            compute(
                preset = SyntaxPreset.CUSTOM,
                customOverrides = mapOf("Groovy" to mapOf("STRING_LITERAL" to 75)),
                baseline = baseline,
                overlay = emptyMap(),
                options =
                    ComputeOptions(
                        subordinatePreset = SyntaxPreset.AMBIENT,
                        customStyles = mapOf("Groovy" to mapOf("STRING_LITERAL" to Font.BOLD)),
                    ),
            )

        val attrs = assertNotNull(result[stringKey], "String must not be skipped")
        assertEquals(0, attrs.fontType, "String must not use the Groovy STRING_LITERAL style")
        assertEquals(
            sourceColor.rgb,
            attrs.foregroundColor?.rgb,
            "String must use the Ambient fallback instead of the Groovy STRING_LITERAL slider",
        )
    }

    @Test
    fun `cascade defaults materialize inherited per-language keys without own foreground`() {
        val defaultCommentKey = TextAttributesKey.createTextAttributesKey("DEFAULT_LINE_COMMENT")
        val kotlinCommentKey = TextAttributesKey.createTextAttributesKey("KOTLIN_LINE_COMMENT")
        val commentColor = Color(0x5C, 0x67, 0x73)
        val inheritedTarget = TextAttributes()
        val result =
            compute(
                preset = SyntaxPreset.AMBIENT,
                customOverrides = emptyMap(),
                baseline =
                    mapOf(
                        defaultCommentKey to attrsWithFg(commentColor),
                        kotlinCommentKey to inheritedTarget,
                    ),
                overlay = emptyMap(),
            )

        assertEquals(
            commentColor.rgb,
            assertNotNull(result[kotlinCommentKey]?.foregroundColor).rgb,
            "KOTLIN_LINE_COMMENT must receive a materialized color from DEFAULT_LINE_COMMENT",
        )
        assertSame(
            null,
            inheritedTarget.foregroundColor,
            "cascade materialization must not mutate the inherited target attributes",
        )
    }

    @Test
    fun `CSharp ReSharper keys use the Cyberpunk language override`() {
        val csharpKeywordKey = TextAttributesKey.createTextAttributesKey("ReSharper.CSHARP_KEYWORD")
        val keywordColor = Color(0xFF, 0xAD, 0x66)
        val curve =
            SyntaxPresetCurves.curveFor(
                SyntaxPreset.CYBERPUNK,
                "C# (ReSharper)",
                PrimitiveCategory.KEYWORD,
            )
        val expected =
            HslColor.toColor(
                HslColor.fromColor(keywordColor).hue,
                (HslColor.fromColor(keywordColor).saturation + curve.saturationDelta).coerceIn(0f, 1f),
                expectedLightnessVia(HslColor.fromColor(keywordColor).lightness, curve),
            )

        val result =
            compute(
                preset = SyntaxPreset.CYBERPUNK,
                customOverrides = emptyMap(),
                baseline = mapOf(csharpKeywordKey to attrsWithFg(keywordColor)),
                overlay = emptyMap(),
            )

        assertChannelDiff(expected, assertNotNull(result[csharpKeywordKey]?.foregroundColor))
    }

    @Test
    fun `readability dim comments only quiets comment keys on top of Ambient`() {
        val baselineFg = Color(0x78, 0x7B, 0x80)
        val keywordFg = Color(0xFF, 0xAD, 0x66)
        val baseline =
            mapOf(
                javaCommentKey to attrsWithFg(baselineFg),
                javaKeywordKey to attrsWithFg(keywordFg),
            )

        val result =
            compute(
                preset = SyntaxPreset.AMBIENT,
                customOverrides = emptyMap(),
                baseline = baseline,
                overlay = emptyMap(),
                options = ComputeOptions(readabilityOptions = SyntaxReadabilityOptions(dimComments = true)),
            )

        val comment = assertNotNull(result[javaCommentKey]?.foregroundColor)
        val keyword = assertNotNull(result[javaKeywordKey]?.foregroundColor)
        assertTrue(
            HslColor.fromColor(comment).lightness < HslColor.fromColor(baselineFg).lightness,
            "Dim comments must make comment text recede",
        )
        assertEquals(keywordFg.rgb, keyword.rgb, "Dim comments must not rewrite unrelated categories")
    }

    @Test
    fun `readability dim comments moves toward the active editor background`() {
        val lightEditorBg = Color(0xFC, 0xFC, 0xFC)
        val commentFg = Color(0x78, 0x7B, 0x80)

        val result =
            compute(
                preset = SyntaxPreset.AMBIENT,
                customOverrides = emptyMap(),
                baseline = mapOf(javaCommentKey to attrsWithFg(commentFg)),
                overlay = emptyMap(),
                options =
                    ComputeOptions(
                        editorBg = lightEditorBg,
                        readabilityOptions = SyntaxReadabilityOptions(dimComments = true),
                    ),
            )

        val output = assertNotNull(result[javaCommentKey]?.foregroundColor)
        assertTrue(
            colorDistance(output, lightEditorBg) < colorDistance(commentFg, lightEditorBg),
            "Dim comments must recede toward the active background instead of always darkening",
        )
    }

    @Test
    fun `readability dim comments treats ignore comments as comments`() {
        val ignoreCommentKey = TextAttributesKey.createTextAttributesKey("IGNORE.COMMENT")
        val commentFg = Color(0x80, 0x80, 0x80)
        val editorBg = Color(0x1F, 0x24, 0x30)

        val dimResult =
            compute(
                preset = SyntaxPreset.AMBIENT,
                customOverrides = emptyMap(),
                baseline = mapOf(ignoreCommentKey to attrsWithFg(commentFg)),
                overlay = emptyMap(),
                options =
                    ComputeOptions(
                        editorBg = editorBg,
                        readabilityOptions = SyntaxReadabilityOptions(dimComments = true),
                    ),
            )
        val documentationResult =
            compute(
                preset = SyntaxPreset.AMBIENT,
                customOverrides = emptyMap(),
                baseline = mapOf(ignoreCommentKey to attrsWithFg(commentFg)),
                overlay = emptyMap(),
                options =
                    ComputeOptions(
                        editorBg = editorBg,
                        readabilityOptions = SyntaxReadabilityOptions(softenDocumentation = true),
                    ),
            )

        val dimmed = assertNotNull(dimResult[ignoreCommentKey]?.foregroundColor)
        val documentationOnly = assertNotNull(documentationResult[ignoreCommentKey]?.foregroundColor)
        assertTrue(
            colorDistance(dimmed, editorBg) < colorDistance(commentFg, editorBg),
            "Dim comments must move .ignore comments toward the active background",
        )
        assertEquals(
            commentFg.rgb,
            documentationOnly.rgb,
            "Soften documentation must not rewrite .ignore comments",
        )
    }

    @Test
    fun `readability soften documentation quiets documentation keys`() {
        val docKey = TextAttributesKey.createTextAttributesKey("JAVA_DOC_COMMENT")
        val baselineFg = Color(0x9D, 0xA0, 0xA8)
        val keywordFg = Color(0xFF, 0xAD, 0x66)

        val result =
            compute(
                preset = SyntaxPreset.AMBIENT,
                customOverrides = emptyMap(),
                baseline =
                    mapOf(
                        docKey to attrsWithFg(baselineFg),
                        javaKeywordKey to attrsWithFg(keywordFg),
                    ),
                overlay = emptyMap(),
                options = ComputeOptions(readabilityOptions = SyntaxReadabilityOptions(softenDocumentation = true)),
            )

        val output = assertNotNull(result[docKey]?.foregroundColor)
        val keyword = assertNotNull(result[javaKeywordKey]?.foregroundColor)
        assertTrue(
            colorDistance(output, ComputeOptions().editorBg) < colorDistance(baselineFg, ComputeOptions().editorBg),
            "Soften documentation must move doc text toward the editor background",
        )
        assertNotEquals(baselineFg.rgb, output.rgb, "Soften documentation must visibly affect documentation keys")
        assertEquals(keywordFg.rgb, keyword.rgb, "Soften documentation must not rewrite unrelated categories")
    }

    @Test
    fun `readability quiet operators quiets operator keys`() {
        val operatorKey = TextAttributesKey.createTextAttributesKey("JAVA_OPERATION_SIGN")
        val baselineFg = Color(0xB8, 0xC2, 0xCC)
        val commentFg = Color(0x78, 0x7B, 0x80)

        val result =
            compute(
                preset = SyntaxPreset.AMBIENT,
                customOverrides = emptyMap(),
                baseline =
                    mapOf(
                        operatorKey to attrsWithFg(baselineFg),
                        javaCommentKey to attrsWithFg(commentFg),
                    ),
                overlay = emptyMap(),
                options = ComputeOptions(readabilityOptions = SyntaxReadabilityOptions(quietOperators = true)),
            )

        val output = assertNotNull(result[operatorKey]?.foregroundColor)
        val comment = assertNotNull(result[javaCommentKey]?.foregroundColor)
        assertTrue(
            colorDistance(output, ComputeOptions().editorBg) < colorDistance(baselineFg, ComputeOptions().editorBg),
            "Quiet operators must move operator text toward the editor background",
        )
        assertEquals(commentFg.rgb, comment.rgb, "Quiet operators must not rewrite unrelated categories")
    }

    @Test
    fun `readability emphasize declarations strengthens declaration keys`() {
        val declarationKeys =
            listOf(
                TextAttributesKey.createTextAttributesKey("JAVA_FUNCTION_DECLARATION"),
                TextAttributesKey.createTextAttributesKey("JAVA_CLASS_DECLARATION"),
                TextAttributesKey.createTextAttributesKey("JAVA_INTERFACE_DECLARATION"),
                TextAttributesKey.createTextAttributesKey("KOTLIN_FUNCTION_DECLARATION"),
                TextAttributesKey.createTextAttributesKey("KOTLIN_CLASS"),
            )
        val baselineFg = Color(0xFF, 0xCC, 0x66)
        val commentFg = Color(0x78, 0x7B, 0x80)

        val result =
            compute(
                preset = SyntaxPreset.AMBIENT,
                customOverrides = emptyMap(),
                baseline =
                    declarationKeys.associateWith { attrsWithFg(baselineFg) } +
                        (javaCommentKey to attrsWithFg(commentFg)),
                overlay = emptyMap(),
                options = ComputeOptions(readabilityOptions = SyntaxReadabilityOptions(emphasizeDeclarations = true)),
            )

        val inputDistance = abs(HslColor.fromColor(baselineFg).lightness - MID_LIGHTNESS)
        for (key in declarationKeys) {
            val output = assertNotNull(result[key]?.foregroundColor)
            val outputDistance = abs(HslColor.fromColor(output).lightness - MID_LIGHTNESS)
            val distanceDelta = inputDistance - outputDistance
            assertTrue(
                distanceDelta >= EMPHASIZE_DECLARATIONS_MIN_DISTANCE_DELTA,
                "Emphasize declarations must visibly move ${key.externalName} lightness toward peak chroma",
            )
        }
        val comment = assertNotNull(result[javaCommentKey]?.foregroundColor)
        assertEquals(commentFg.rgb, comment.rgb, "Emphasize declarations must not rewrite unrelated categories")
    }

    // --- Helpers ---------------------------------------------------------

    private fun transformVia(
        preset: SyntaxPreset,
        key: TextAttributesKey,
        fg: Color,
    ): Color {
        val result =
            compute(
                preset = preset,
                customOverrides = emptyMap(),
                baseline = mapOf(key to attrsWithFg(fg)),
                overlay = emptyMap(),
            )
        return assertNotNull(result[key]?.foregroundColor, "compute must emit a foreground for ${key.externalName}")
    }

    private fun Color.hex(): String = "%02X%02X%02X".format(red, green, blue)

    private fun colorDistance(
        a: Color,
        b: Color,
    ): Int =
        abs(a.red - b.red) +
            abs(a.green - b.green) +
            abs(a.blue - b.blue)

    private data class ComputeOptions(
        val subordinatePreset: SyntaxPreset = SyntaxPreset.AMBIENT,
        val customStyles: Map<String, Map<String, Int>> = emptyMap(),
        val editorBg: Color = Color(0x1F, 0x24, 0x30),
        val readabilityOptions: SyntaxReadabilityOptions = SyntaxReadabilityOptions.DEFAULT,
    )

    private fun compute(
        preset: SyntaxPreset,
        customOverrides: Map<String, Map<String, Int>>,
        baseline: Map<TextAttributesKey, TextAttributes>,
        overlay: Map<TextAttributesKey, TextAttributes>,
        options: ComputeOptions = ComputeOptions(),
    ): Map<TextAttributesKey, TextAttributes> =
        SyntaxIntensityApplicator.compute(
            SyntaxIntensityApplicator.Request(
                preset = preset,
                variantName = "Mirage",
                editorBg = options.editorBg,
                baseline = baseline,
                overlay = overlay,
                customOverrides = customOverrides,
                subordinatePreset = options.subordinatePreset,
                customStyles = options.customStyles,
                readabilityOptions = options.readabilityOptions,
            ),
        )

    private fun attrsWithFg(color: Color): TextAttributes {
        val attrs = TextAttributes()
        attrs.foregroundColor = color
        return attrs
    }

    /**
     * Mirrors the production signed-intent / absolute lightness resolver so the
     * expected-value tests lock the applicator's transform, not a magic hex.
     */
    private fun expectedLightnessVia(
        lightness: Float,
        curve: CategoryCurve,
    ): Float {
        val resolved =
            if (curve.absoluteLightness) {
                lightness + curve.lightnessDelta
            } else {
                val towardMid = if (lightness >= MID_LIGHTNESS) -1f else 1f
                val lightnessDirection = if (curve.lightnessDelta >= 0f) towardMid else -towardMid
                lightness + lightnessDirection * abs(curve.lightnessDelta)
            }
        val lowerBound = if (curve.absoluteLightness) LIGHTNESS_LOWER else SYNTAX_FLOOR
        return resolved.coerceIn(lowerBound, LIGHTNESS_UPPER)
    }

    private fun assertChannelDiff(
        expected: Color,
        actual: Color,
    ) {
        val redDiff = abs(expected.red - actual.red)
        val greenDiff = abs(expected.green - actual.green)
        val blueDiff = abs(expected.blue - actual.blue)
        assertTrue(
            redDiff <= CHANNEL_TOLERANCE,
            "red diff $redDiff > $CHANNEL_TOLERANCE (expected=$expected actual=$actual)",
        )
        assertTrue(
            greenDiff <= CHANNEL_TOLERANCE,
            "green diff $greenDiff > $CHANNEL_TOLERANCE (expected=$expected actual=$actual)",
        )
        assertTrue(
            blueDiff <= CHANNEL_TOLERANCE,
            "blue diff $blueDiff > $CHANNEL_TOLERANCE (expected=$expected actual=$actual)",
        )
    }

    companion object {
        // AccentHsl.MIN_LIGHTNESS — the absolute floor kept on the
        // absoluteLightness (COMMENT dim) path.
        private const val LIGHTNESS_LOWER = 0.10f
        private const val LIGHTNESS_UPPER = 0.95f

        // SyntaxIntensityApplicator.SYNTAX_MIN_LIGHTNESS — the readability floor
        // on the signed-intent path.
        private const val SYNTAX_FLOOR = 0.48f
        private const val MID_LIGHTNESS = 0.5f
        private const val EMPHASIZE_DECLARATIONS_MIN_DISTANCE_DELTA = 0.10f
        private const val FLOAT_TOLERANCE = 0.005f
        private const val HUE_TOLERANCE = 0.5f
        private const val CHANNEL_TOLERANCE = 2

        // FontStyleOverride.BOLD_ITALIC.fontType — the combined java.awt.Font bitmask (3).
        private const val BOLD_ITALIC_MASK = 3

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
