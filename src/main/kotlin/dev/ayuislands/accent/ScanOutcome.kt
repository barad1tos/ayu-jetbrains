package dev.ayuislands.accent

/**
 * Typed result payload for [ProjectLanguageDetectionListener.scanCompleted].
 *
 * Replaces the previous nullable `String?` signature so subscribers can
 * distinguish the three post-scan states structurally rather than
 * re-querying [ProjectLanguageDetector.proportions] to tell polyglot
 * (definitive no-winner) apart from a transient failure (scanner threw,
 * rescan with unresolvable project key, disposal race). The payload is
 * advisory — subscribers that render proportions MUST still re-query
 * `proportions()` because the weights cache is the authoritative source
 * for the UI row — but the discriminator lets the UI decide whether the
 * previous paint is still correct (Unavailable → keep it) or a
 * polyglot label should replace it (Polyglot → overwrite).
 *
 * Sealed hierarchy so exhaustive `when` forces callers to handle new
 * outcomes if the set ever grows (e.g., a future "Scanning" interim
 * state).
 */
internal sealed interface ScanOutcome {
    /**
     * Scan settled on a dominant language. [languageId] is the lowercase
     * AYU id from [LanguageDetectionRules].
     */
    data class Detected(
        val languageId: String,
    ) : ScanOutcome

    /**
     * Scan ran cleanly but produced no winner — either no language
     * clears the dominance threshold, no plurality margin, or the
     * legacy SDK/module tiebreaker did not qualify. Definitive verdict:
     * subscribers can render the polyglot copy with confidence that
     * the weights cache is in sync.
     */
    object Polyglot : ScanOutcome

    /**
     * Scan could not produce an authoritative answer — scanner threw,
     * dumb-mode race, disposal race, or a `rescan` was invoked with an
     * unresolvable project key. Subscribers should prefer keeping the
     * previous UI paint and try again on the next event; the cache is
     * intentionally not populated so [ProjectLanguageDetector.dominant]
     * will re-scan on the next call.
     */
    object Unavailable : ScanOutcome
}
