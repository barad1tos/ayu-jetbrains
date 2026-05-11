package dev.ayuislands.vcs

/**
 * Preset levels for VCS color intensity.
 *
 * Each preset maps every [VcsColorCategory] to a percent in `[0, 100]` that the
 * applier feeds into [VcsColorBlender.blend] as the linear-interpolation ratio
 * between the stock 2.6.2 XML color (baseline) and the per-category vibrant
 * target.
 *
 *  - [MUTED] — 0% across all categories. Default for upgraders from 2.6.2.
 *    Byte-identical to stock XML colors; no live theme change when the master
 *    toggle is off.
 *  - [BALANCED] — midpoint between Muted and Vibrant. Reasonable opening
 *    balance for users who want some life back without going to peak.
 *  - [VIBRANT] — 100% across all categories. Restores pre-2.6.2 cyan diff colors
 *    plus proportional boosts on the other ten surfaces.
 *  - [CUSTOM] — unlocks the per-category sliders in the settings panel; the
 *    snapshot's per-category map is consulted directly instead of the preset
 *    lookup table.
 *
 * Per-preset values are hand-tuned (not algorithmic): the vibrant targets per
 * category are defined separately in the applier, while the preset just declares
 * *how much* of that target to mix at this preset level.
 */
enum class VcsColorPreset {
    MUTED,
    BALANCED,
    VIBRANT,
    CUSTOM,
    ;

    /**
     * Returns the intensity percent this preset assigns to [category].
     *
     * For [CUSTOM], the call resolves to the same value as [BALANCED] as a
     * sentinel — callers should branch on the preset and read the per-category
     * map directly from the snapshot rather than invoke this method for
     * [CUSTOM]. The fallback value protects against accidental fall-through
     * when a future code path queries this method without checking the preset
     * type first.
     */
    fun intensityFor(category: VcsColorCategory): Int =
        when (this) {
            MUTED -> MUTED_INTENSITIES.getValue(category)
            BALANCED -> BALANCED_INTENSITIES.getValue(category)
            VIBRANT -> VIBRANT_INTENSITIES.getValue(category)
            CUSTOM -> BALANCED_INTENSITIES.getValue(category)
        }

    companion object {
        /**
         * Resolves a persisted preset string into the matching enum value,
         * defaulting to [MUTED] when the string is `null`, empty, unknown, or
         * corrupted (hand-edited XML, schema migration artifact).
         */
        fun byName(name: String?): VcsColorPreset = entries.firstOrNull { it.name == name } ?: MUTED

        /** Stock XML baseline — every category at 0%. */
        private val MUTED_INTENSITIES: Map<VcsColorCategory, Int> =
            VcsColorCategory.entries.associateWith { 0 }

        /**
         * Midpoint preset — 50% across all categories. Hand-tuned to be the
         * "I want some life back, but not eye-bleeding" default that a Pro user
         * would land on when they first open the panel.
         */
        private val BALANCED_INTENSITIES: Map<VcsColorCategory, Int> =
            VcsColorCategory.entries.associateWith { 50 }

        /**
         * Peak preset — 100% across all categories. Restores pre-2.6.2 cyan diff
         * brightness and proportionally boosts the other surfaces toward their
         * vibrant targets.
         */
        private val VIBRANT_INTENSITIES: Map<VcsColorCategory, Int> =
            VcsColorCategory.entries.associateWith { 100 }
    }
}
