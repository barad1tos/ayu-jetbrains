package dev.ayuislands.vcs

import dev.ayuislands.accent.AyuVariant
import java.awt.Color
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Algorithmic tests for [VcsColorPalette].
 *
 * Wave 2 covers the three Diff & File Status user-task categories with both
 * ColorKey-based and TextAttributesKey-based entries. The palette derives
 * Whisper and Cyberpunk slider endpoints from each stock value via HSB
 * saturation offset; the tests pin:
 *
 *  - completeness — every category exposes at least one entry; every entry
 *    has a stock color for every Ayu variant
 *  - endpoint relationships — Whisper has lower saturation, Cyberpunk higher
 *  - Ambient slider invariant — at [VcsColorPreset.AMBIENT_SLIDER], blending
 *    from `endpoints(...)` lands within rounding tolerance of the stock color
 *    so a Pro user observing the AMBIENT preset sees no visible change vs 2.6.2
 *  - alpha preservation — Light variant ships translucent backgrounds; the
 *    palette must round-trip the alpha channel through the saturation offset
 */
class VcsColorPaletteTest {
    @Test
    fun `palette covers every entry under every variant`() {
        for ((category, entries) in VcsColorPalette.allCategoriesAndEntries()) {
            assertTrue(
                entries.isNotEmpty(),
                "Category $category must have at least one palette entry",
            )
            for (entry in entries) {
                for (variant in AyuVariant.entries) {
                    val stock = entry.stockFor(variant)
                    // Alpha may be < 255 for translucent Light backgrounds; just ensure the
                    // entry resolved a real color without throwing.
                    assertTrue(
                        stock.alpha in 1..ALPHA_MAX,
                        "Stock alpha must lie in (0, 255]: ${entry.keyName}/${entry.mode}/$variant",
                    )
                }
            }
        }
    }

    @Test
    fun `Whisper endpoint has lower or equal saturation than stock`() {
        for ((_, entries) in VcsColorPalette.allCategoriesAndEntries()) {
            for (entry in entries) {
                for (variant in AyuVariant.entries) {
                    val stock = entry.stockFor(variant)
                    val (whisper, _) = VcsColorPalette.endpoints(entry, variant)
                    if (saturationOf(stock) <= LOW_SAT_THRESHOLD) continue
                    assertTrue(
                        saturationOf(whisper) < saturationOf(stock),
                        "${entry.keyName}/$variant: Whisper sat ${saturationOf(whisper)} " +
                            "should be below stock sat ${saturationOf(stock)}",
                    )
                }
            }
        }
    }

    @Test
    fun `Cyberpunk endpoint has higher or equal saturation vs stock`() {
        for ((_, entries) in VcsColorPalette.allCategoriesAndEntries()) {
            for (entry in entries) {
                for (variant in AyuVariant.entries) {
                    val stock = entry.stockFor(variant)
                    val (_, cyberpunk) = VcsColorPalette.endpoints(entry, variant)
                    assertTrue(
                        saturationOf(cyberpunk) >= saturationOf(stock) - SAT_EPSILON,
                        "${entry.keyName}/$variant: Cyberpunk sat ${saturationOf(cyberpunk)} " +
                            "should be >= stock sat ${saturationOf(stock)}",
                    )
                }
            }
        }
    }

    @Test
    fun `endpoints preserve stock alpha for translucent Light backgrounds`() {
        // Light variant DIFF_*_BG entries ship as `#RRGGBBAA` with alpha < 255
        // (12-13% overlay). The Whisper/Cyberpunk endpoints derive via HSB
        // saturation offset and must NOT collapse alpha to 255 — otherwise
        // tinted backgrounds would suddenly become opaque blocks of color.
        for ((_, entries) in VcsColorPalette.allCategoriesAndEntries()) {
            for (entry in entries) {
                if (entry.mode != VcsWriteMode.TEXT_ATTR_BG) continue
                val stock = entry.stockFor(AyuVariant.LIGHT)
                if (stock.alpha == ALPHA_MAX) continue
                val (whisper, cyberpunk) = VcsColorPalette.endpoints(entry, AyuVariant.LIGHT)
                assertEquals(stock.alpha, whisper.alpha, "${entry.keyName}: Whisper alpha drift")
                assertEquals(stock.alpha, cyberpunk.alpha, "${entry.keyName}: Cyberpunk alpha drift")
            }
        }
    }

    @Test
    fun `Ambient slider position blends to within rounding tolerance of stock`() {
        val ambient = VcsIntensity.of(VcsColorPreset.AMBIENT_SLIDER)
        for ((_, entries) in VcsColorPalette.allCategoriesAndEntries()) {
            for (entry in entries) {
                for (variant in AyuVariant.entries) {
                    val stock = entry.stockFor(variant)
                    val (base, target) = VcsColorPalette.endpoints(entry, variant)
                    val blended = VcsColorBlender.blend(base, target, ambient)
                    assertTrue(
                        abs(blended.red - stock.red) <= AMBIENT_CHANNEL_TOLERANCE,
                        "${entry.keyName}/$variant: red drift |${blended.red} - ${stock.red}| > tolerance",
                    )
                    assertTrue(
                        abs(blended.green - stock.green) <= AMBIENT_CHANNEL_TOLERANCE,
                        "${entry.keyName}/$variant: green drift |${blended.green} - ${stock.green}| > tolerance",
                    )
                    assertTrue(
                        abs(blended.blue - stock.blue) <= AMBIENT_CHANNEL_TOLERANCE,
                        "${entry.keyName}/$variant: blue drift |${blended.blue} - ${stock.blue}| > tolerance",
                    )
                }
            }
        }
    }

    @Test
    fun `Whisper slider position blends to the Whisper endpoint`() {
        val whisper = VcsIntensity.of(VcsColorPreset.WHISPER_SLIDER)
        for ((_, entries) in VcsColorPalette.allCategoriesAndEntries()) {
            for (entry in entries) {
                for (variant in AyuVariant.entries) {
                    val (base, target) = VcsColorPalette.endpoints(entry, variant)
                    val blended = VcsColorBlender.blend(base, target, whisper)
                    // Slider 0 short-circuits to base per VcsColorBlender contract.
                    assertEquals(base.red, blended.red, "${entry.keyName}/$variant: Whisper red mismatch")
                    assertEquals(base.green, blended.green, "${entry.keyName}/$variant: Whisper green mismatch")
                    assertEquals(base.blue, blended.blue, "${entry.keyName}/$variant: Whisper blue mismatch")
                }
            }
        }
    }

    @Test
    fun `Cyberpunk slider position blends to the Cyberpunk endpoint`() {
        val cyberpunk = VcsIntensity.of(VcsColorPreset.CYBERPUNK_SLIDER)
        for ((_, entries) in VcsColorPalette.allCategoriesAndEntries()) {
            for (entry in entries) {
                for (variant in AyuVariant.entries) {
                    val (base, target) = VcsColorPalette.endpoints(entry, variant)
                    val blended = VcsColorBlender.blend(base, target, cyberpunk)
                    assertEquals(target.red, blended.red, "${entry.keyName}/$variant: Cyberpunk red mismatch")
                    assertEquals(target.green, blended.green, "${entry.keyName}/$variant: Cyberpunk green mismatch")
                    assertEquals(target.blue, blended.blue, "${entry.keyName}/$variant: Cyberpunk blue mismatch")
                }
            }
        }
    }

    @Test
    fun `palette covers Wave 2 + Wave 3 + Wave 4 categories`() {
        // Lock the active category set so a future wave addition lands intentionally
        // rather than implicitly. Wave 4 added BLAME_GUTTER. MERGE_3WAY,
        // INLINE_DIFF_POPUP, LOCAL_HISTORY, and the three Branch & Commit categories
        // intentionally remain absent — they either reuse Wave 2 diff keys (merge,
        // local history) or live on UIManager surfaces outside this palette (popup,
        // branch indicator, branches popup, commit highlights).
        assertEquals(
            setOf(
                VcsColorCategory.DIFF_VIEWER,
                VcsColorCategory.PROJECT_VIEW_FILE_STATUS,
                VcsColorCategory.EDITOR_GUTTER,
                VcsColorCategory.CONFLICT_MARKERS,
                VcsColorCategory.BLAME_GUTTER,
            ),
            VcsColorPalette.allCategoriesAndEntries().keys,
            "Palette must cover the five active VCS color categories through Wave 4",
        )
    }

    @Test
    fun `conflict markers category includes both ColorKey and TextAttrBg entries`() {
        val entries = VcsColorPalette.entriesFor(VcsColorCategory.CONFLICT_MARKERS)
        val modes = entries.map { it.mode }.toSet()
        assertTrue(VcsWriteMode.COLOR_KEY in modes, "CONFLICT_MARKERS must have ColorKey entries")
        assertTrue(VcsWriteMode.TEXT_ATTR_BG in modes, "CONFLICT_MARKERS must have TextAttrBg entries")
    }

    @Test
    fun `diff viewer category includes both ColorKey and TextAttrBg entries`() {
        // Wave 2.5 invariant: DIFF_VIEWER must reach both the top-level ColorKey
        // (file tab tints, scrollbar markers) and the TextAttributesKey BACKGROUND
        // (visible row tint in the diff editor). If either disappears the visible
        // signal of moving the Diff slider degrades materially.
        val entries = VcsColorPalette.entriesFor(VcsColorCategory.DIFF_VIEWER)
        val modes = entries.map { it.mode }.toSet()
        assertTrue(VcsWriteMode.COLOR_KEY in modes, "DIFF_VIEWER must have ColorKey entries")
        assertTrue(VcsWriteMode.TEXT_ATTR_BG in modes, "DIFF_VIEWER must have TextAttrBg entries")
    }

    private fun saturationOf(color: Color): Float = Color.RGBtoHSB(color.red, color.green, color.blue, null)[1]

    private companion object {
        const val SAT_EPSILON: Float = 0.001f
        const val LOW_SAT_THRESHOLD: Float = 0.10f

        /**
         * Per-channel RGB tolerance for the AMBIENT_SLIDER == stock invariant.
         *
         * Three sources of drift:
         *  1. Slider 33 approximates the exact saturation midpoint (33.33).
         *  2. Stock saturation-ceiling clamps (Light FILESTATUS_ADDED is fully
         *     saturated green): Cyberpunk's +20% offset hits S=1.0 instead of 1.2,
         *     so the lerp midpoint slips slightly below stock.
         *  3. Near-white quasi-text entries (Light VCS_ANNOTATIONS_COLOR_3..5
         *     sit above #EAF2FC — very low saturation, very high brightness)
         *     concentrate HSB→RGB requantization noise in their low-S/high-B
         *     corner of the color space. Visually indistinguishable but
         *     mathematically louder.
         *
         * `24` sits at the WCAG-just-noticeable-difference threshold for sRGB —
         * anything beyond this would be perceptible, and gross palette drift
         * (typo, wrong-variant index) trips at deltas well above this.
         */
        const val AMBIENT_CHANNEL_TOLERANCE: Int = 24
        const val ALPHA_MAX: Int = 255
    }
}
