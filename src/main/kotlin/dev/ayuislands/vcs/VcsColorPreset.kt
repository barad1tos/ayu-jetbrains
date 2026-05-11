package dev.ayuislands.vcs

/**
 * Named intensity levels for VCS color customization.
 *
 * Mirrors the Font tab's preset-then-custom UX: instead of a generic
 * "intensity %" knob, users pick a recognizable named profile, and only
 * unlock per-category sliders via [CUSTOM] when they want to fine-tune.
 * The four named presets sit at evenly-spaced slider positions so the
 * step from one preset to the next is identical (33 slider units ≈
 * 10% HSB saturation delta).
 *
 *  - [WHISPER]   → slider 0   — `-10%` saturation from 2.6.2 stock
 *  - [AMBIENT]   → slider 33  — equals 2.6.2 XML stock (default for upgraders)
 *  - [NEON]      → slider 67  — `+10%` saturation, restores pre-2.6.2 cyan punch
 *  - [CYBERPUNK] → slider 100 — `+20%` saturation, peak vibrancy
 *  - [CUSTOM]    → unlocks per-category sliders in the panel; the snapshot's
 *    per-category map is consulted directly instead of the preset table
 *
 * Per-preset slider positions are hand-tuned (not algorithmic). The actual
 * HSB-saturation curve is implemented by [VcsColorPalette] — the preset
 * declares *how far along the curve* this profile sits, while the palette
 * declares *what the endpoints are* per (key × variant).
 */
enum class VcsColorPreset {
    WHISPER,
    AMBIENT,
    NEON,
    CYBERPUNK,
    CUSTOM,
    ;

    /**
     * Returns the slider position this preset assigns to [category].
     *
     * For [CUSTOM], the call returns [AMBIENT_SLIDER] as a sentinel — callers
     * should branch on the preset and read the per-category map directly from
     * the snapshot rather than invoke this method for [CUSTOM]. The fallback
     * value protects against accidental fall-through when a future code path
     * queries this method without checking the preset type first.
     */
    fun intensityFor(category: VcsColorCategory): Int =
        when (this) {
            WHISPER -> WHISPER_INTENSITIES.getValue(category)
            AMBIENT -> AMBIENT_INTENSITIES.getValue(category)
            NEON -> NEON_INTENSITIES.getValue(category)
            CYBERPUNK -> CYBERPUNK_INTENSITIES.getValue(category)
            CUSTOM -> AMBIENT_INTENSITIES.getValue(category)
        }

    companion object {
        /** Slider position for the [WHISPER] preset — `-10%` saturation endpoint. */
        const val WHISPER_SLIDER: Int = 0

        /** Slider position for the [AMBIENT] preset — `0%` (2.6.2 stock baseline). */
        const val AMBIENT_SLIDER: Int = 33

        /** Slider position for the [NEON] preset — `+10%` saturation. */
        const val NEON_SLIDER: Int = 67

        /** Slider position for the [CYBERPUNK] preset — `+20%` saturation. */
        const val CYBERPUNK_SLIDER: Int = 100

        /**
         * Resolves a persisted preset string into the matching enum value,
         * defaulting to [AMBIENT] when the string is `null`, empty, unknown, or
         * corrupted (hand-edited XML, schema migration artifact). Ambient is
         * the no-op default — falling back to it keeps a corrupted persisted
         * value from accidentally tinting surfaces a user never opted into.
         */
        fun byName(name: String?): VcsColorPreset = entries.firstOrNull { it.name == name } ?: AMBIENT

        /** Whisper — slider 0 across all categories. */
        private val WHISPER_INTENSITIES: Map<VcsColorCategory, Int> =
            VcsColorCategory.entries.associateWith { WHISPER_SLIDER }

        /** Ambient — slider 33 across all categories (= 2.6.2 XML stock). */
        private val AMBIENT_INTENSITIES: Map<VcsColorCategory, Int> =
            VcsColorCategory.entries.associateWith { AMBIENT_SLIDER }

        /** Neon — slider 67 across all categories (`+10%` saturation). */
        private val NEON_INTENSITIES: Map<VcsColorCategory, Int> =
            VcsColorCategory.entries.associateWith { NEON_SLIDER }

        /** Cyberpunk — slider 100 across all categories (`+20%` saturation). */
        private val CYBERPUNK_INTENSITIES: Map<VcsColorCategory, Int> =
            VcsColorCategory.entries.associateWith { CYBERPUNK_SLIDER }
    }
}
