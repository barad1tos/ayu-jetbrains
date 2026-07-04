package dev.ayuislands.accent

/**
 * How the resolution engine judges a stored hex before letting a rung win.
 * The engine's consumers agree on ladder order but not on this judgment, so
 * the policy is the seam that lets one engine serve all of them:
 *
 * - [STRICT] — only a valid `#RRGGBB` wins; invalid values fall through to the
 *   next rung. Used by the diagnostics chain ([AccentResolver.resolveChain])
 *   and the external-theme resolve, which must never propagate a raw invalid
 *   hex into the applicator.
 * - [LENIENT] — valid hex wins normalized (trimmed); an invalid value still
 *   wins verbatim EXCEPT on the language-fallback rung, which always validates.
 *   Used by the native-variant [AccentResolver.resolve] / [AccentResolver.source]
 *   pair, where an invalid stored override must still report its source instead
 *   of silently claiming the global accent is active.
 * - [RAW] — everything wins verbatim. Used by the Settings pending preview,
 *   whose model values are already normalized at entry; validating again here
 *   would hide a broken entry path instead of surfacing it.
 */
internal enum class AccentHexPolicy {
    STRICT,
    LENIENT,
    RAW,
    ;

    /**
     * Returns the hex that [source]'s rung may win with, or `null` when the
     * rung must fall through to the next one.
     */
    fun accept(
        source: AccentResolver.Source,
        rawHex: String,
    ): String? =
        when (this) {
            STRICT -> {
                AccentHex.of(rawHex)?.value
            }

            LENIENT -> {
                AccentHex.of(rawHex)?.value
                    ?: rawHex.takeUnless { source == AccentResolver.Source.LANGUAGE_FALLBACK_OVERRIDE }
            }

            RAW -> {
                rawHex
            }
        }
}
