package dev.ayuislands.accent

import dev.ayuislands.settings.mappings.AccentMappingsSettings
import dev.ayuislands.settings.mappings.AccentMappingsState

/**
 * Read-side contract over one accent-override mapping set, walked by
 * [AccentResolutionChainBuilder] — the single resolution engine behind
 * [AccentResolver.resolve], [AccentResolver.source], [AccentResolver.resolveChain],
 * and the Settings pending preview. Abstracting the mapping reads is what lets the
 * persisted state and the not-yet-applied Settings models share one priority-ladder
 * walk instead of three hand-kept copies.
 *
 * All hex-valued reads return the stored value verbatim: whether an invalid or
 * padded hex wins, loses, or passes through raw is the [AccentHexPolicy]'s decision,
 * not the view's.
 *
 * [hasForcedLanguageEntry] is deliberately separate from [forcedLanguageId]:
 * a forced entry whose id normalizes to `null` (blank after trim) must still
 * suppress language detection — presence of the entry, not validity of the id,
 * gates the detection rung.
 */
internal interface AccentMappingsView {
    /** Raw project-override hex pinned to [projectKey], or `null` when none is stored. */
    fun projectAccent(projectKey: String): String?

    /** Normalized forced-language id for [projectKey]: trimmed, `null` when absent or blank. */
    fun forcedLanguageId(projectKey: String): String?

    /** Whether ANY forced-language entry exists for [projectKey], even a blank one. */
    fun hasForcedLanguageEntry(projectKey: String): Boolean

    /** Raw language-accent hex mapped to [languageId], or `null` when unmapped. */
    fun languageAccent(languageId: String): String?

    /** Whether at least one language-accent mapping exists. */
    val hasLanguageAccents: Boolean

    /** Normalized language-fallback hex: trimmed, `null` when absent or blank. */
    val languageFallbackAccent: String?

    /** Raw project-fallback hex for [projectKey], or `null` when none is stored. */
    fun projectFallbackAccent(projectKey: String): String?

    /** Whether a project-fallback entry exists for [projectKey]. */
    fun hasProjectFallbackCandidate(projectKey: String): Boolean
}

/**
 * [AccentMappingsView] over the persisted [AccentMappingsState].
 *
 * The state is read lazily so constructing a view (e.g. as a default argument)
 * never touches the [AccentMappingsSettings] service before the engine's license
 * and project gates have passed — unlicensed and no-project resolutions must stay
 * zero-cost.
 *
 * Normalization note: [forcedLanguageId] and [languageFallbackAccent] are
 * trim-and-blank-filtered here, once, for every consumer. Blank or padded
 * hand-edited XML entries therefore resolve identically for the live accent and
 * the diagnostics trace — the live resolver's semantics, which treat such
 * entries as absent rather than as unmatchable lookup keys.
 */
internal class PersistedAccentMappingsView(
    stateSupplier: () -> AccentMappingsState = { AccentMappingsSettings.getInstance().state },
) : AccentMappingsView {
    private val state: AccentMappingsState by lazy(LazyThreadSafetyMode.NONE, stateSupplier)

    override fun projectAccent(projectKey: String): String? = state.projectAccents[projectKey]

    override fun forcedLanguageId(projectKey: String): String? =
        state.forcedProjectLanguages[projectKey]?.trim()?.takeIf { it.isNotEmpty() }

    override fun hasForcedLanguageEntry(projectKey: String): Boolean =
        state.forcedProjectLanguages.containsKey(projectKey)

    override fun languageAccent(languageId: String): String? = state.languageAccents[languageId]

    override val hasLanguageAccents: Boolean
        get() = state.languageAccents.isNotEmpty()

    override val languageFallbackAccent: String?
        get() = state.languageFallbackAccent?.trim()?.takeIf { it.isNotEmpty() }

    override fun projectFallbackAccent(projectKey: String): String? = state.projectFallbackAccents[projectKey]

    override fun hasProjectFallbackCandidate(projectKey: String): Boolean =
        state.projectFallbackAccents.containsKey(projectKey)
}
