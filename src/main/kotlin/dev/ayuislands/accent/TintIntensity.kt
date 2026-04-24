package dev.ayuislands.accent

/**
 * Typed wrapper for the chrome tint intensity percent fed into
 * [ChromeTintBlender.blend].
 *
 * ### Why a value class
 *
 * [dev.ayuislands.settings.AyuIslandsState.chromeTintIntensity] is persisted
 * as a raw `Int` and the valid range `[MIN, MAX]` was previously enforced
 * at the read boundary via `effectiveChromeTintIntensity().coerceIn(...)`.
 * Every caller had to remember to call the helper — easy to forget, and a
 * future caller reaching the raw delegate gets an unchecked `Int` that may
 * carry a corrupted out-of-range value straight into the HSB blender.
 *
 * Lifting the clamp into the type means the only way to construct a
 * `TintIntensity` is through [of], which coerces at the boundary. The
 * blender then reads `intensity.percent` with the range invariant already
 * proven by construction.
 *
 * ### Persistence
 *
 * The wrapper deliberately does NOT replace the persisted field. `BaseState`
 * requires `Int` property delegates for XML serialization; changing the
 * stored type would break forward/backward compat. Callers resolve the
 * wrapper at read time via
 * [dev.ayuislands.settings.AyuIslandsState.effectiveChromeTintIntensity].
 */
@JvmInline
value class TintIntensity private constructor(
    val percent: Int,
) {
    companion object {
        /** Lower bound for the slider-visible range (inclusive). */
        const val MIN: Int = 0

        /**
         * Upper bound for the slider-visible range (inclusive). Mirrors
         * [dev.ayuislands.settings.AyuIslandsState.MAX_CHROME_TINT_INTENSITY]
         * — the user-visible cap — not the blender's internal 0-100 math-safety
         * clamp. A persisted value above [MAX] (legacy pre-cap session, hand-edited
         * XML) coerces down to [MAX] here so every live user observes the same
         * ceiling the slider exposes.
         */
        const val MAX: Int = 50

        /**
         * Default intensity for newly-enabled chrome tinting — the Peacock-parity
         * opening balance per Phase 40 `CONTEXT.md §specifics`. Matches the
         * `DEFAULT_CHROME_TINT_INTENSITY` constant exposed on `AyuIslandsState`.
         */
        val DEFAULT: TintIntensity = of(40)

        /**
         * Coerces [raw] into `[MIN, MAX]` and wraps the result. Values outside the
         * slider-visible range do not throw — they clamp, matching the defensive
         * behaviour of the prior `coerceIn` read boundary so a corrupted persisted
         * XML never desaturates or over-saturates chrome.
         */
        fun of(raw: Int): TintIntensity = TintIntensity(raw.coerceIn(MIN, MAX))
    }
}
