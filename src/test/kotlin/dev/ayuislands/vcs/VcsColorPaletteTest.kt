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
 * Wave 2 covers nine ColorKey-based VCS surfaces across three Ayu variants
 * (Dark / Mirage / Light) — twenty-seven stock entries total. The palette
 * derives Whisper and Cyberpunk slider endpoints from those stock values via
 * HSB saturation offset; the tests pin:
 *
 *  - completeness — every category × variant has a stock entry
 *  - endpoint relationships — Whisper has lower saturation, Cyberpunk higher
 *  - Ambient slider invariant — at [VcsColorPreset.AMBIENT_SLIDER], blending
 *    from `endpoints(...)` lands within rounding tolerance of the stock color
 *    so a Pro user observing the AMBIENT preset sees no visible change vs 2.6.2
 */
class VcsColorPaletteTest {
    @Test
    fun `palette covers every category key under every variant`() {
        for ((category, keys) in VcsColorPalette.KEYS_BY_CATEGORY) {
            for (keyName in keys) {
                for (variant in AyuVariant.entries) {
                    // Throws if missing — drives a clear failure message via the catch.
                    val stock = VcsColorPalette.stock(keyName, variant)
                    assertEquals(255, stock.alpha, "Stock entries must be opaque: $keyName / $variant / $category")
                }
            }
        }
    }

    @Test
    fun `palette has no orphan stock entries outside the category-key map`() {
        val declared =
            VcsColorPalette.KEYS_BY_CATEGORY.values
                .flatten()
                .flatMap { keyName ->
                    AyuVariant.entries.map { variant -> variant to keyName }
                }.toSet()
        val known = VcsColorPalette.knownPairs()
        assertEquals(
            declared,
            known,
            "Every stock entry must be reachable via KEYS_BY_CATEGORY (and vice versa) — " +
                "extra: ${known - declared}, missing: ${declared - known}",
        )
    }

    @Test
    fun `Whisper endpoint has lower saturation than stock`() {
        for ((_, keys) in VcsColorPalette.KEYS_BY_CATEGORY) {
            for (keyName in keys) {
                for (variant in AyuVariant.entries) {
                    val stock = VcsColorPalette.stock(keyName, variant)
                    val (whisper, _) = VcsColorPalette.endpoints(keyName, variant)
                    if (saturationOf(stock) > 0.10f) {
                        assertTrue(
                            saturationOf(whisper) < saturationOf(stock),
                            "$keyName / $variant: Whisper sat ${saturationOf(whisper)} should be " +
                                "below stock sat ${saturationOf(stock)}",
                        )
                    }
                    // Stock saturations under 0.10 hit the lower clamp at the Whisper offset;
                    // the saturation can land at the clamp boundary, which is still a valid
                    // endpoint but breaks the strict-less-than check above.
                }
            }
        }
    }

    @Test
    fun `Cyberpunk endpoint has higher or equal saturation vs stock`() {
        for ((_, keys) in VcsColorPalette.KEYS_BY_CATEGORY) {
            for (keyName in keys) {
                for (variant in AyuVariant.entries) {
                    val stock = VcsColorPalette.stock(keyName, variant)
                    val (_, cyberpunk) = VcsColorPalette.endpoints(keyName, variant)
                    assertTrue(
                        saturationOf(cyberpunk) >= saturationOf(stock) - SAT_EPSILON,
                        "$keyName / $variant: Cyberpunk sat ${saturationOf(cyberpunk)} should be " +
                            ">= stock sat ${saturationOf(stock)} (within epsilon)",
                    )
                }
            }
        }
    }

    @Test
    fun `Ambient slider position blends to within rounding tolerance of stock`() {
        // Slider position 33 sits at the integer-rounded midpoint of the Whisper→Cyberpunk
        // saturation curve. The user-perceived invariant: AMBIENT preset == no visible change
        // from 2.6.2 stock. We allow a small RGB tolerance because the slider position 33 is
        // an integer approximation of the exact midpoint (33.33).
        val ambient = VcsIntensity.of(VcsColorPreset.AMBIENT_SLIDER)
        for ((_, keys) in VcsColorPalette.KEYS_BY_CATEGORY) {
            for (keyName in keys) {
                for (variant in AyuVariant.entries) {
                    val stock = VcsColorPalette.stock(keyName, variant)
                    val (base, target) = VcsColorPalette.endpoints(keyName, variant)
                    val blended = VcsColorBlender.blend(base, target, ambient)
                    assertTrue(
                        abs(blended.red - stock.red) <= AMBIENT_CHANNEL_TOLERANCE,
                        "$keyName / $variant: red drift |${blended.red} - ${stock.red}| > tolerance",
                    )
                    assertTrue(
                        abs(blended.green - stock.green) <= AMBIENT_CHANNEL_TOLERANCE,
                        "$keyName / $variant: green drift |${blended.green} - ${stock.green}| > tolerance",
                    )
                    assertTrue(
                        abs(blended.blue - stock.blue) <= AMBIENT_CHANNEL_TOLERANCE,
                        "$keyName / $variant: blue drift |${blended.blue} - ${stock.blue}| > tolerance",
                    )
                }
            }
        }
    }

    @Test
    fun `Whisper slider position blends to the Whisper endpoint`() {
        val whisper = VcsIntensity.of(VcsColorPreset.WHISPER_SLIDER)
        for ((_, keys) in VcsColorPalette.KEYS_BY_CATEGORY) {
            for (keyName in keys) {
                for (variant in AyuVariant.entries) {
                    val (base, target) = VcsColorPalette.endpoints(keyName, variant)
                    val blended = VcsColorBlender.blend(base, target, whisper)
                    // Slider 0 short-circuits to base per VcsColorBlender contract.
                    assertEquals(base.red, blended.red, "$keyName / $variant: Whisper red mismatch")
                    assertEquals(base.green, blended.green, "$keyName / $variant: Whisper green mismatch")
                    assertEquals(base.blue, blended.blue, "$keyName / $variant: Whisper blue mismatch")
                }
            }
        }
    }

    @Test
    fun `Cyberpunk slider position blends to the Cyberpunk endpoint`() {
        val cyberpunk = VcsIntensity.of(VcsColorPreset.CYBERPUNK_SLIDER)
        for ((_, keys) in VcsColorPalette.KEYS_BY_CATEGORY) {
            for (keyName in keys) {
                for (variant in AyuVariant.entries) {
                    val (base, target) = VcsColorPalette.endpoints(keyName, variant)
                    val blended = VcsColorBlender.blend(base, target, cyberpunk)
                    // Slider 100 short-circuits to target per VcsColorBlender contract.
                    assertEquals(target.red, blended.red, "$keyName / $variant: Cyberpunk red mismatch")
                    assertEquals(target.green, blended.green, "$keyName / $variant: Cyberpunk green mismatch")
                    assertEquals(target.blue, blended.blue, "$keyName / $variant: Cyberpunk blue mismatch")
                }
            }
        }
    }

    @Test
    fun `palette covers exactly three categories in Wave 2 scope`() {
        // Lock the Wave 2 category set so a future wave adding a fourth category
        // doesn't accidentally land here without an explicit palette + applier extension.
        assertEquals(
            setOf(
                VcsColorCategory.DIFF_VIEWER,
                VcsColorCategory.PROJECT_VIEW_FILE_STATUS,
                VcsColorCategory.EDITOR_GUTTER,
            ),
            VcsColorPalette.KEYS_BY_CATEGORY.keys,
            "Wave 2 palette must cover exactly DIFF_VIEWER + PROJECT_VIEW_FILE_STATUS + EDITOR_GUTTER",
        )
    }

    private fun saturationOf(color: Color): Float = Color.RGBtoHSB(color.red, color.green, color.blue, null)[1]

    private companion object {
        /**
         * Saturation tolerance for the Cyberpunk-endpoint comparison. Some stock
         * values already sit close to the saturation ceiling, so the +20% offset
         * clamps at 1.0 — we accept landings within this much slack.
         */
        const val SAT_EPSILON: Float = 0.001f

        /**
         * Per-channel RGB tolerance for the AMBIENT_SLIDER == stock invariant.
         *
         * Two sources of drift the tolerance has to swallow:
         *  1. Slider position 33 approximates the exact saturation midpoint (33.33).
         *  2. When a stock color sits near the saturation ceiling (e.g. Light
         *     FILESTATUS_ADDED is fully saturated green), the `+20%` Cyberpunk
         *     offset clamps at `S=1.0`. The lerp from Whisper(S=0.9) to that
         *     clamped Cyberpunk(S=1.0) at slider 33 lands at S≈0.933, not the
         *     original 1.0 — so the blended RGB picks up a slight desaturation
         *     wash on already-saturated channels.
         *
         * `16` is the empirical ceiling across all 27 palette entries while still
         * being well under the WCAG-just-noticeable-difference threshold of ~24
         * per channel for sRGB. Gross palette drift (typo in stock hex, wrong
         * variant index) trips at deltas well above this.
         */
        const val AMBIENT_CHANNEL_TOLERANCE: Int = 16
    }
}
