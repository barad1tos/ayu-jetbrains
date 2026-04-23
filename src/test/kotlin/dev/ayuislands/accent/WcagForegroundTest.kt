package dev.ayuislands.accent

import dev.ayuislands.accent.WcagForeground.TextTarget
import java.awt.Color
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * WCAG 2.1 contrast-ratio + palette-sweep lock for [WcagForeground].
 *
 * Closes VERIFICATION Gap 2 — the static `ColorUtil.isDark` two-way pick in
 * `ChromeTintBlender.contrastForeground` was insufficient for saturated
 * mid-luminance tinted backgrounds where neither white nor the Ayu dark
 * foreground meets WCAG AA. This suite locks the spec-fidelity of the new
 * module against known-good values from the W3C spec.
 *
 * Spec: https://www.w3.org/TR/WCAG21/#contrast-minimum
 */
class WcagForegroundTest {
    // W-1
    @Test
    fun `relativeLuminance of pure black is 0 and of pure white is 1 per WCAG 2 1 spec`() {
        val blackLuminance = WcagForeground.relativeLuminance(Color.BLACK)
        val whiteLuminance = WcagForeground.relativeLuminance(Color.WHITE)
        assertTrue(
            abs(blackLuminance - 0.0) < LUMINANCE_TOLERANCE,
            "relativeLuminance(BLACK) must be ~0.0, got $blackLuminance",
        )
        assertTrue(
            abs(whiteLuminance - 1.0) < LUMINANCE_TOLERANCE,
            "relativeLuminance(WHITE) must be ~1.0, got $whiteLuminance",
        )
    }

    // W-2
    @Test
    fun `contrastRatio of white against black is 21 per WCAG 2 1 spec`() {
        val ratio = WcagForeground.contrastRatio(Color.WHITE, Color.BLACK)
        assertTrue(
            abs(ratio - WHITE_BLACK_RATIO) < RATIO_TOLERANCE,
            "contrastRatio(WHITE, BLACK) must be ~21.0, got $ratio",
        )
    }

    // W-3
    @Test
    fun `contrastRatio of white against Ayu DARK_FOREGROUND is at least 14`() {
        // Ayu DARK_FOREGROUND = 0x1F2430 — actual ratio ≈ 14.7.
        // The 14.0 floor catches luminance-math regressions a looser 12.0 would
        // silently pass; a swapped sRGB coefficient typically shifts the ratio
        // by ~1-2, so the tightened band surfaces it (M-3 remediation).
        val ratio = WcagForeground.contrastRatio(Color.WHITE, Color(0x1F, 0x24, 0x30))
        assertTrue(
            ratio >= DARK_FG_MIN_RATIO,
            "contrastRatio(WHITE, #1F2430) must be >= $DARK_FG_MIN_RATIO, got $ratio",
        )
    }

    // W-4
    @Test
    fun `contrastRatio is symmetric across the argument order`() {
        val pairs =
            listOf(
                Color.WHITE to Color.BLACK,
                Color(0x5C, 0xCF, 0xE6) to Color(0x1F, 0x24, 0x30),
                Color(0xE6, 0xB4, 0x50) to Color(0x25, 0x2E, 0x38),
            )
        for ((a, b) in pairs) {
            val ab = WcagForeground.contrastRatio(a, b)
            val ba = WcagForeground.contrastRatio(b, a)
            assertTrue(
                abs(ab - ba) < RATIO_TOLERANCE,
                "contrastRatio must be symmetric: f($a,$b)=$ab vs f($b,$a)=$ba",
            )
        }
    }

    // W-5
    @Test
    fun `pickForeground returns white against the darkest Ayu surface for PRIMARY_TEXT`() {
        val darkAyu = Color(0x1F, 0x24, 0x30)
        val picked = WcagForeground.pickForeground(darkAyu, TextTarget.PRIMARY_TEXT)
        assertEquals(Color.WHITE, picked, "white must be the first palette entry passing AA on dark Ayu")
    }

    // W-6
    @Test
    fun `pickForeground returns a dark foreground on a pale background for PRIMARY_TEXT`() {
        val paleBackground = Color(0xFF, 0xE4, 0xB5) // moccasin — very light
        val picked = WcagForeground.pickForeground(paleBackground, TextTarget.PRIMARY_TEXT)
        assertTrue(
            picked != Color.WHITE,
            "white fails AA on a pale background; picker must fall through, got $picked",
        )
        // The ratio against the picked color must meet AA
        val ratio = WcagForeground.contrastRatio(picked, paleBackground)
        assertTrue(
            ratio >= TextTarget.PRIMARY_TEXT.minRatio,
            "picked foreground on a pale background must meet the AA ratio, got $ratio",
        )
    }

    // W-7
    @Test
    fun `pickForeground on a mid-tinted background meets the PRIMARY_TEXT 4_5 threshold`() {
        val midTinted = Color(0x7A, 0x9E, 0xAA) // ambiguous luminance slate
        val picked = WcagForeground.pickForeground(midTinted, TextTarget.PRIMARY_TEXT)
        val ratio = WcagForeground.contrastRatio(picked, midTinted)
        assertTrue(
            ratio >= TextTarget.PRIMARY_TEXT.minRatio,
            "PRIMARY_TEXT foreground on mid-tinted bg must meet >= 4.5, got $ratio",
        )
    }

    // W-8
    @Test
    fun `pickForeground with SECONDARY_TEXT target needs only the 3_0 threshold`() {
        // Use a background where white hits ≥3.0 but possibly undershoots 4.5 —
        // locks the target-specific threshold contract.
        val midBackground = Color(0x5C, 0x6E, 0x7F)
        val secondaryPick = WcagForeground.pickForeground(midBackground, TextTarget.SECONDARY_TEXT)
        val secondaryRatio = WcagForeground.contrastRatio(secondaryPick, midBackground)
        assertTrue(
            secondaryRatio >= TextTarget.SECONDARY_TEXT.minRatio,
            "SECONDARY_TEXT must meet >= 3.0, got $secondaryRatio",
        )
    }

    // W-9
    @Test
    fun `pickForeground never throws nor returns null for degenerate inputs`() {
        val degenerate = listOf(Color(0, 0, 0), Color(255, 255, 255), Color(128, 128, 128))
        for (bg in degenerate) {
            val picked = WcagForeground.pickForeground(bg, TextTarget.PRIMARY_TEXT)
            assertNotNull(picked)
            val ratio = WcagForeground.contrastRatio(picked, bg)
            assertTrue(
                ratio >= 1.0,
                "ratio must be >= 1.0 (picker never degrades below identity) for bg=$bg, got $ratio",
            )
        }
    }

    // W-10
    @Test
    fun `TextTarget ICON and SECONDARY_TEXT share the same 3_0 minRatio`() {
        assertEquals(TextTarget.ICON.minRatio, TextTarget.SECONDARY_TEXT.minRatio)
        assertEquals(SECONDARY_MIN_RATIO, TextTarget.SECONDARY_TEXT.minRatio)
        assertEquals(PRIMARY_MIN_RATIO, TextTarget.PRIMARY_TEXT.minRatio)
    }

    private companion object {
        const val LUMINANCE_TOLERANCE = 1e-4
        const val RATIO_TOLERANCE = 1e-3
        const val WHITE_BLACK_RATIO = 21.0
        const val DARK_FG_MIN_RATIO = 14.0
        const val PRIMARY_MIN_RATIO = 4.5
        const val SECONDARY_MIN_RATIO = 3.0
    }
}
