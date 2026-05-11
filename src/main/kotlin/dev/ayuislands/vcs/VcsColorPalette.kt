package dev.ayuislands.vcs

import dev.ayuislands.accent.AyuVariant
import java.awt.Color

/**
 * Phase 40.2 palette — stock 2.6.2 XML hex per (color-key × Ayu variant) plus
 * the Whisper / Cyberpunk slider-endpoint colors computed via HSB-saturation
 * boost from those stock values.
 *
 * The applier consumes [endpoints] to feed [VcsColorBlender.blend], which
 * lerps between Whisper (slider 0, `stock - 10%` saturation) and Cyberpunk
 * (slider 100, `stock + 20%` saturation). Slider position 33 lands on the
 * exact XML stock — that's the Ambient preset's no-op invariant.
 *
 * The preset slider positions [VcsColorPreset.WHISPER_SLIDER],
 * [VcsColorPreset.AMBIENT_SLIDER], [VcsColorPreset.NEON_SLIDER],
 * [VcsColorPreset.CYBERPUNK_SLIDER] sit at evenly-spaced points along this
 * curve so the visible step from one preset to the next is uniform.
 *
 * Wave 2 ColorKey-only scope (9 entries × 3 variants = 27 stock hex):
 *  - Diff viewer: `DIFF_MODIFIED`, `DIFF_INSERTED`, `DIFF_DELETED`
 *  - Project View: `FILESTATUS_MODIFIED`, `FILESTATUS_ADDED`, `FILESTATUS_DELETED`,
 *    `FILESTATUS_IDEA_FILESTATUS_IGNORED`
 *  - Editor gutter: `MODIFIED_LINES_COLOR`, `IGNORED_MODIFIED_LINES_BORDER_COLOR`
 *
 * TextAttributesKey-backed surfaces (diff viewer row backgrounds, error stripes)
 * land in Wave 2.x amendment once the ColorKey path is validated in `runIde`.
 */
object VcsColorPalette {
    /**
     * Saturation delta applied at slider 0 (Whisper endpoint). Negative values
     * desaturate the stock color, producing a softer, more pastel reading.
     */
    private const val WHISPER_SATURATION_DELTA: Float = -0.10f

    /**
     * Saturation delta applied at slider 100 (Cyberpunk endpoint). Positive
     * values push chroma toward the perceptual ceiling, producing the
     * pre-2.6.2 punch at the Neon midpoint and an even-more-saturated peak
     * at Cyberpunk.
     */
    private const val CYBERPUNK_SATURATION_DELTA: Float = 0.20f

    /**
     * Per-(variant × color-key) stock hex from the 2.6.2 XML schemes. Mirrors
     * the values [dev.ayuislands.theme.VcsDiffSchemeColorsTest] pins against
     * the on-disk XML so palette drift fails the regression test rather than
     * silently writing wrong colors at runtime.
     *
     * Light variant inherits 2.6.2 stock unchanged — Light didn't undergo the
     * cyan-to-blue mute that Dark and Mirage did in PR #167, so its stock is
     * already at the post-mute baseline.
     *
     * Hex strings (not [Color] constructor calls) keep the source byte-comparable
     * to the on-disk XML — copy/paste from a `git diff` of the theme files lands
     * directly here, and Color.decode handles parsing.
     */
    private val STOCK_HEX: Map<Pair<AyuVariant, String>, String> =
        mapOf(
            // Diff viewer — top-level ColorKey values
            (AyuVariant.DARK to "DIFF_MODIFIED") to "#73B8FF",
            (AyuVariant.MIRAGE to "DIFF_MODIFIED") to "#80BFFF",
            (AyuVariant.LIGHT to "DIFF_MODIFIED") to "#478ACC",
            (AyuVariant.DARK to "DIFF_INSERTED") to "#70BF56",
            (AyuVariant.MIRAGE to "DIFF_INSERTED") to "#87D96C",
            (AyuVariant.LIGHT to "DIFF_INSERTED") to "#6CBF43",
            (AyuVariant.DARK to "DIFF_DELETED") to "#F26D78",
            (AyuVariant.MIRAGE to "DIFF_DELETED") to "#F27983",
            (AyuVariant.LIGHT to "DIFF_DELETED") to "#FF7383",
            // Project View file status
            (AyuVariant.DARK to "FILESTATUS_MODIFIED") to "#73B8FF",
            (AyuVariant.MIRAGE to "FILESTATUS_MODIFIED") to "#80BFFF",
            (AyuVariant.LIGHT to "FILESTATUS_MODIFIED") to "#478ACC",
            (AyuVariant.DARK to "FILESTATUS_ADDED") to "#AAD94C",
            (AyuVariant.MIRAGE to "FILESTATUS_ADDED") to "#C3E887",
            (AyuVariant.LIGHT to "FILESTATUS_ADDED") to "#86B300",
            (AyuVariant.DARK to "FILESTATUS_DELETED") to "#F07178",
            (AyuVariant.MIRAGE to "FILESTATUS_DELETED") to "#F77669",
            (AyuVariant.LIGHT to "FILESTATUS_DELETED") to "#F07171",
            (AyuVariant.DARK to "FILESTATUS_IDEA_FILESTATUS_IGNORED") to "#9B7700",
            (AyuVariant.MIRAGE to "FILESTATUS_IDEA_FILESTATUS_IGNORED") to "#B58900",
            (AyuVariant.LIGHT to "FILESTATUS_IDEA_FILESTATUS_IGNORED") to "#9EA3A9",
            // Editor gutter
            (AyuVariant.DARK to "MODIFIED_LINES_COLOR") to "#73B8FF",
            (AyuVariant.MIRAGE to "MODIFIED_LINES_COLOR") to "#80BFFF",
            (AyuVariant.LIGHT to "MODIFIED_LINES_COLOR") to "#478ACC",
            (AyuVariant.DARK to "IGNORED_MODIFIED_LINES_BORDER_COLOR") to "#73B8FF",
            (AyuVariant.MIRAGE to "IGNORED_MODIFIED_LINES_BORDER_COLOR") to "#80BFFF",
            (AyuVariant.LIGHT to "IGNORED_MODIFIED_LINES_BORDER_COLOR") to "#478ACC",
        )

    /**
     * Per-category list of ColorKey names to write. The applier iterates this
     * map to drive per-category slider effects: moving the Diff viewer slider
     * tints all three diff-related keys with the same intensity.
     */
    val KEYS_BY_CATEGORY: Map<VcsColorCategory, List<String>> =
        mapOf(
            VcsColorCategory.DIFF_VIEWER to
                listOf("DIFF_MODIFIED", "DIFF_INSERTED", "DIFF_DELETED"),
            VcsColorCategory.PROJECT_VIEW_FILE_STATUS to
                listOf(
                    "FILESTATUS_MODIFIED",
                    "FILESTATUS_ADDED",
                    "FILESTATUS_DELETED",
                    "FILESTATUS_IDEA_FILESTATUS_IGNORED",
                ),
            VcsColorCategory.EDITOR_GUTTER to
                listOf("MODIFIED_LINES_COLOR", "IGNORED_MODIFIED_LINES_BORDER_COLOR"),
        )

    /**
     * Returns the stock (= 2.6.2 XML) color for [keyName] under [variant].
     * Throws if the pair isn't in the palette — that's a coding bug at
     * applier-call time, not a runtime defensive case.
     */
    fun stock(
        keyName: String,
        variant: AyuVariant,
    ): Color {
        val hex =
            STOCK_HEX[variant to keyName]
                ?: error("VcsColorPalette: no stock color for $keyName / $variant")
        return Color.decode(hex)
    }

    /**
     * Returns the (base, target) blend endpoints for [keyName] under [variant].
     * Base sits at slider 0 (Whisper, `-10%` saturation); target sits at slider 100
     * (Cyberpunk, `+20%` saturation). The applier passes both to
     * [VcsColorBlender.blend] along with the active slider position.
     */
    fun endpoints(
        keyName: String,
        variant: AyuVariant,
    ): Pair<Color, Color> {
        val stockColor = stock(keyName, variant)
        val base = withSaturationDelta(stockColor, WHISPER_SATURATION_DELTA)
        val target = withSaturationDelta(stockColor, CYBERPUNK_SATURATION_DELTA)
        return base to target
    }

    /**
     * Returns the set of every (variant, keyName) pair the palette knows about.
     * Tests pin this against [KEYS_BY_CATEGORY] to catch silent additions to
     * one map without the matching addition to the other.
     */
    fun knownPairs(): Set<Pair<AyuVariant, String>> = STOCK_HEX.keys

    private fun withSaturationDelta(
        color: Color,
        delta: Float,
    ): Color {
        val hsb = Color.RGBtoHSB(color.red, color.green, color.blue, null)
        val adjusted = (hsb[1] + delta).coerceIn(0f, 1f)
        return Color.getHSBColor(hsb[0], adjusted, hsb[2])
    }
}
