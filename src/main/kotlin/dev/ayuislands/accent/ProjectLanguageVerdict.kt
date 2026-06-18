package dev.ayuislands.accent

/**
 * Read-only project-language detection state for Settings diagnostics and
 * resolver fallback decisions. This is intentionally separate from
 * [ScanOutcome], which is a message-bus event payload.
 */
sealed interface ProjectLanguageVerdict {
    /** No scan has produced a cache entry for this project key yet. */
    object Cold : ProjectLanguageVerdict

    /**
     * The current read cannot produce an authoritative detector verdict.
     *
     * Returned for an unavailable project key and for the current non-cacheable
     * scan attempt; transient scanner failures normally do NOT persist this
     * verdict in [ProjectLanguageDetector].
     */
    object Unavailable : ProjectLanguageVerdict

    /** A scan ran cleanly but found no recognized source files and no legacy hint. */
    object Empty : ProjectLanguageVerdict

    data class Detected(
        val languageId: String,
        val weights: Map<String, Long>?,
    ) : ProjectLanguageVerdict

    data class NoWinner(
        val weights: Map<String, Long>,
    ) : ProjectLanguageVerdict
}
