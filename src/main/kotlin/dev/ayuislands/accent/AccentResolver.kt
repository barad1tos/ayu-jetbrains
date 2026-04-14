package dev.ayuislands.accent

import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import dev.ayuislands.licensing.LicenseChecker
import dev.ayuislands.settings.AyuIslandsSettings
import dev.ayuislands.settings.mappings.AccentMappingsSettings
import java.io.File
import java.util.Collections
import java.util.WeakHashMap

/**
 * Resolves the effective accent hex for a project + variant pair in priority order:
 *
 *  1. **Project override** — `AccentMappingsState.projectAccents` keyed by the project's
 *     canonical base path.
 *  2. **Language override** — dominant language of the project via [ProjectLanguageDetector].
 *  3. **Global** — [AyuIslandsSettings.getAccentForVariant] (which itself honors
 *     follow-system-accent and per-variant stored hex).
 *
 * Per-project and per-language overrides are premium features: when the license check
 * fails, the resolver short-circuits to the global accent regardless of stored mappings.
 * The UI disables override add/edit for unlicensed users, but this guard protects against
 * trial expiry with previously-stored mappings and against manually imported settings XML.
 *
 * Zero-cost path: when `languageAccents` is empty the detector is never consulted,
 * so projects without any language overrides take a pure map-lookup path.
 */
object AccentResolver {
    enum class Source { PROJECT_OVERRIDE, LANGUAGE_OVERRIDE, GLOBAL }

    private val LOG = logger<AccentResolver>()

    /**
     * Dedup set for canonicalPath warnings — a failing project base path is hot-path on focus
     * swap / rotation ticks, so we log the first failure and suppress subsequent ones for the
     * same project to avoid flooding idea.log. WeakHashMap keyed by Project so entries age out
     * with the project's disposal.
     */
    private val loggedCanonicalFailures: MutableSet<Project> =
        Collections.newSetFromMap(WeakHashMap())

    /**
     * Resolves the effective accent hex. Delegates to the shared override-traversal helper,
     * falling back to the global per-variant accent when no override applies.
     */
    fun resolve(
        project: Project?,
        variant: AyuVariant,
    ): String {
        val globalAccent = AyuIslandsSettings.getInstance().getAccentForVariant(variant)
        return findOverride(project)?.second ?: globalAccent
    }

    /**
     * Returns which layer of the resolution chain produced the accent for [project].
     * Used by the settings UI to surface "Currently active: ... (project override)" context.
     *
     * Mirrors the license gate in [resolve]: unlicensed callers always see [Source.GLOBAL],
     * so the UI label does not claim a project/language override is "active" when the
     * resolver is actually returning the global accent.
     */
    fun source(project: Project?): Source = findOverride(project)?.first ?: Source.GLOBAL

    /**
     * Single traversal of the override priority chain shared by [resolve] and [source].
     * Returns `null` when no override applies (global wins) — either because the license
     * check fails, the project is null/default/disposed, or no mapping matches.
     *
     * Centralizing the traversal means adding a new override tier (e.g. folder override)
     * touches only this function, and [resolve]/[source] cannot drift out of sync.
     */
    private fun findOverride(project: Project?): Pair<Source, String>? {
        if (!LicenseChecker.isLicensedOrGrace()) return null
        if (project == null || project.isDefault || project.isDisposed) return null

        val mappings = AccentMappingsSettings.getInstance().state
        projectKey(project)
            ?.let { mappings.projectAccents[it] }
            ?.let { return Source.PROJECT_OVERRIDE to it }

        if (mappings.languageAccents.isNotEmpty()) {
            ProjectLanguageDetector.dominant(project)?.let { languageId ->
                mappings.languageAccents[languageId]?.let { return Source.LANGUAGE_OVERRIDE to it }
            }
        }
        return null
    }

    /**
     * Stable key for a [project]: the canonicalized absolute path of `basePath`.
     * Returns `null` when the project has no base path or canonicalization throws
     * (permission error, symlink loop, missing directory, network-share unreachable).
     *
     * Canonicalization failures are logged once per project — this runs on hot paths
     * (focus swap, rotation tick) so we guard against log spam via [loggedCanonicalFailures].
     */
    fun projectKey(project: Project): String? {
        val raw = project.basePath ?: return null
        return runCatching { File(raw).canonicalPath }
            .onFailure { exception ->
                if (loggedCanonicalFailures.add(project)) {
                    LOG.warn(
                        "Failed to canonicalize basePath for '${project.name}' ($raw); " +
                            "project-override resolution will fall back to global accent",
                        exception,
                    )
                }
            }.getOrNull()
    }
}
