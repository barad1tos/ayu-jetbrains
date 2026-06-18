package dev.ayuislands.accent

/**
 * Why a resolution step won or lost the accent-source priority chain.
 *
 * Ordered from "source won" to "source not applicable" so that UI consumers
 * can group outcomes by severity without a separate ordering table.
 */
enum class StepOutcome {
    /** This source won and is the active accent. */
    WON,

    /** Premium feature blocked by license gate. */
    LICENSE_BLOCKED,

    /** No override configured for this source (no mapping, no pinned accent). */
    NOT_SET,

    /** Language was detected but has no accent mapping in settings. */
    NO_MAPPING,

    /** Language detected but did not reach dominance threshold (polyglot). */
    NOT_DOMINANT,

    /** Source not applicable in the current context (e.g. project override in External theme). */
    NOT_APPLICABLE,

    /** Detection unavailable — scanner threw, dumb-mode race, or disposal race. */
    UNAVAILABLE,
}

/**
 * One step in the accent resolution decision trace.
 *
 * Each step records which [source] was considered, what hex it would have
 * produced (null if the source was not set or blocked), the [outcome] that
 * explains why it won or lost, and a human-readable [detail] string for
 * diagnostics.
 *
 * The [AccentResolutionChain] is an ordered list of these steps, with exactly
 * one step having [outcome] == [StepOutcome.WON].
 */
data class AccentResolutionStep(
    val source: AccentResolver.Source,
    val hex: String?,
    val outcome: StepOutcome,
    val detail: String,
)
