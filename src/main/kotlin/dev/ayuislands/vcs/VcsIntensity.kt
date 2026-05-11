package dev.ayuislands.vcs

/**
 * Typed wrapper for the per-category VCS color intensity percent fed into
 * [VcsColorBlender.blend].
 *
 * Mirrors the [dev.ayuislands.accent.TintIntensity] pattern from Phase 40 chrome
 * tinting — the persisted `Int` fields on [dev.ayuislands.settings.AyuIslandsState]
 * stay raw for `BaseState` XML serialization, and every read path resolves through
 * this wrapper so a corrupted persisted value can never reach the HSB blender out
 * of range.
 *
 * Range `[MIN, MAX]` is `[0, 100]` — the full slider span. Unlike chrome tinting,
 * VCS color intensity has no user-visible cap below 100; the math operates on the
 * normal `0..100` percent range with no surface-readability cliff to defend against.
 */
@JvmInline
value class VcsIntensity private constructor(
    val percent: Int,
) {
    companion object {
        /** Lower bound for the slider-visible range (inclusive). */
        const val MIN: Int = 0

        /** Upper bound for the slider-visible range (inclusive). */
        const val MAX: Int = 100

        /**
         * Default intensity for the [VcsColorPreset.AMBIENT] baseline — equals
         * stock 2.6.2 XML colors. New state instances start at this value so
         * a Pro user enabling the master toggle observes no visible change
         * until they pick a different preset or move a slider.
         */
        val DEFAULT: VcsIntensity = of(VcsColorPreset.AMBIENT_SLIDER)

        /**
         * Coerces [raw] into `[MIN, MAX]` and wraps the result. Values outside the
         * slider-visible range do not throw — they clamp, matching the defensive
         * read-boundary behaviour established by [dev.ayuislands.accent.TintIntensity.of]
         * so a corrupted persisted XML never reaches the blender with an out-of-range
         * value.
         */
        fun of(raw: Int): VcsIntensity = VcsIntensity(raw.coerceIn(MIN, MAX))
    }
}
