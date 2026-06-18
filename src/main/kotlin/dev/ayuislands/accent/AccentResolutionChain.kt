package dev.ayuislands.accent

/**
 * Full decision trace for accent resolution.
 *
 * Contains every source considered in priority order, the single winning step,
 * and the language-detection verdict (if applicable) that influenced the
 * language-override and project-fallback steps.
 *
 * UI consumers (status-bar widget, settings diagnostics) render the [steps]
 * list to show "why this source won or didn't win".
 *
 * ### Construction
 *
 * Built by [AccentResolver.resolveChain]. The [winner] is always one of the
 * [steps] (guaranteed by construction — the global fallback always wins if
 * no override does).
 *
 * ### Verdict
 *
 * [verdict] is non-null only when language detection was consulted during
 * resolution. It carries the [ProjectLanguageVerdict] that determined whether
 * a language override or project fallback could apply.
 */
data class AccentResolutionChain(
    val steps: List<AccentResolutionStep>,
    val winner: AccentResolutionStep,
    val verdict: ProjectLanguageVerdict?,
)
