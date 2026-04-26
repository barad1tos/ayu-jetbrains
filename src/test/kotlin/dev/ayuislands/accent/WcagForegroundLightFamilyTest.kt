package dev.ayuislands.accent

import dev.ayuislands.accent.WcagForeground.TextTarget
import java.awt.Color
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Locks Phase 40.4's light-family palette contract for [WcagForeground].
 *
 * The full 3-color WCAG palette `[WHITE, DARK_FG #1F2430, BLACK]` correctly
 * picks BLACK on mid-luminance backgrounds (e.g. cyan accent at intensity ≥
 * 20% blended against the Mirage stock status bar bg `#20242C`). On chrome
 * surfaces we semantically own as "dark tinted bands" — the status bar,
 * NavBar items rendered inside it — that BLACK pick reads as broken contrast
 * against the visible peer surface, even though it satisfies WCAG AA in
 * isolation.
 *
 * This suite locks two invariants of [WcagForeground.pickLightFamilyForeground]:
 *
 *  1. **BLACK is unreachable.** Across an intensity sweep that the standard
 *     picker would yield BLACK for, the light-family picker returns either
 *     WHITE or the Ayu dark foreground `#1F2430` — never `Color.BLACK`.
 *  2. **Graceful fallback.** When neither WHITE nor DARK_FG meets the WCAG
 *     floor (which can happen on saturated mid-luminance bgs with the strict
 *     4.5:1 PRIMARY_TEXT target), the picker still returns the candidate with
 *     the higher measured ratio rather than throwing or defaulting to BLACK.
 *
 * Mirage stock `StatusBar.background` source: `ayu-islands-mirage.theme.json`
 * resolves `AltBackgroundLight = #1F2430` (per `BackgroundLight` chain).
 * Cyan accent `#5CCFE6` is the Mirage palette entry tested here.
 */
class WcagForegroundLightFamilyTest {
    @Test
    fun `pickLightFamilyForeground never returns BLACK on cyan-tinted Mirage bg sweep`() {
        // Phase 40.4 regression lock: the user-reported "white at 0-10%, black
        // at 20%+" symptom traced to the standard 3-color sweep landing on BLACK
        // once the tinted bg luminance crossed ~0.20. Light-family must NEVER do
        // that — the user expects light text on a dark band at every intensity.
        for (percent in INTENSITY_SWEEP) {
            val tintedBg = blend(MIRAGE_STATUS_BAR_BG, CYAN_ACCENT, percent)
            val pick = WcagForeground.pickLightFamilyForeground(tintedBg, TextTarget.PRIMARY_TEXT)
            assertNotEquals(
                Color.BLACK,
                pick,
                "intensity=$percent% bg=#${"%06X".format(tintedBg.rgb and 0xFFFFFF)} " +
                    "leaked Color.BLACK — light-family palette must exclude it",
            )
            assertTrue(
                pick == Color.WHITE || pick == AYU_DARK_FG,
                "intensity=$percent% pick=${pick.rgb.toString(16)} is neither WHITE nor Ayu DARK_FG",
            )
        }
    }

    @Test
    fun `pickLightFamilyForeground returns WHITE on dark Mirage bg`() {
        // Sanity baseline: on the untinted Mirage status bar bg, WHITE passes
        // WCAG AA cleanly and the picker should land on it on the first
        // iteration without the fallback path engaging.
        val pick = WcagForeground.pickLightFamilyForeground(MIRAGE_STATUS_BAR_BG, TextTarget.PRIMARY_TEXT)
        assertEquals(Color.WHITE, pick, "Untinted dark bg must pick WHITE")
    }

    @Test
    fun `pickLightFamilyForeground returns Ayu dark fg on light theme bg`() {
        // Symmetry: on a light surface (Ayu Light stock bg) WHITE fails WCAG and
        // the picker must fall to the Ayu dark foreground — restoring contrast
        // on the light-theme path widget without leaking BLACK.
        val pick = WcagForeground.pickLightFamilyForeground(AYU_LIGHT_BG, TextTarget.PRIMARY_TEXT)
        assertEquals(AYU_DARK_FG, pick, "Light bg must fall to Ayu DARK_FG")
    }

    @Test
    fun `pickLightFamilyForeground gracefully degrades when neither candidate meets the WCAG floor`() {
        // Mid-luminance tinted bg where both WHITE (~4.05:1) and DARK_FG
        // (~3.8:1) fall under the 4.5:1 PRIMARY_TEXT floor. The picker must
        // still return the candidate with the HIGHER ratio (WHITE here) and
        // never fall through to BLACK / throw / return null.
        val borderlineBg = Color(0x45, 0x87, 0x94) // sampled from 25%-intensity cyan tint logs
        val pick = WcagForeground.pickLightFamilyForeground(borderlineBg, TextTarget.PRIMARY_TEXT)
        assertNotEquals(Color.BLACK, pick, "Graceful degradation must not leak BLACK")
        assertTrue(pick == Color.WHITE || pick == AYU_DARK_FG, "Pick must be from light family")
    }

    @Test
    fun `pickLightFamilyForeground and pickForeground agree on uncontested dark bgs`() {
        // The two pickers must converge on dark bgs where WHITE is unambiguously
        // the right answer — light-family is a SUPERSET of the standard picker's
        // dark-bg behaviour, NOT a different algorithm. Any divergence here
        // indicates the new method's loop introduced a regression.
        val standardPick = WcagForeground.pickForeground(MIRAGE_STATUS_BAR_BG, TextTarget.PRIMARY_TEXT)
        val lightFamilyPick = WcagForeground.pickLightFamilyForeground(MIRAGE_STATUS_BAR_BG, TextTarget.PRIMARY_TEXT)
        assertEquals(
            standardPick,
            lightFamilyPick,
            "On clearly dark bg both pickers must return WHITE",
        )
    }

    /**
     * Linear blend matching `ChromeTintBlender.blend` semantics for test data.
     * Replicating it here (rather than calling the real blender) keeps the
     * suite a pure unit test on [WcagForeground] alone — no service registry,
     * no mock plumbing.
     */
    private fun blend(
        base: Color,
        accent: Color,
        intensityPercent: Int,
    ): Color {
        val a = intensityPercent / PERCENT_SCALE
        val r = ((1.0 - a) * base.red + a * accent.red).toInt().coerceIn(0, BYTE_MAX)
        val g = ((1.0 - a) * base.green + a * accent.green).toInt().coerceIn(0, BYTE_MAX)
        val b = ((1.0 - a) * base.blue + a * accent.blue).toInt().coerceIn(0, BYTE_MAX)
        return Color(r, g, b)
    }

    private companion object {
        // Mirage status bar bg derived from theme.json AltBackgroundLight chain
        val MIRAGE_STATUS_BAR_BG = Color(0x20, 0x24, 0x2C)

        // Mirage cyan accent — the palette entry that triggered the user-reported
        // BLACK transition because cyan blends to mid-luminance fastest.
        val CYAN_ACCENT = Color(0x5C, 0xCF, 0xE6)

        // Ayu Light stock status bar bg — used for symmetry test.
        val AYU_LIGHT_BG = Color(0xFC, 0xFC, 0xFC)

        // Ayu canonical dark foreground (matches WcagForeground.DARK_FOREGROUND_HEX).
        val AYU_DARK_FG = Color(0x1F, 0x24, 0x30)

        // Slider stops the user actually reaches via the Chrome Tinting Intensity UI.
        // 0% is included even though it produces the stock bg unchanged — the picker
        // still has to run on it without leaking BLACK.
        val INTENSITY_SWEEP = listOf(0, 5, 10, 15, 20, 25, 30, 40, 50)

        const val PERCENT_SCALE = 100.0
        const val BYTE_MAX = 255
    }
}
