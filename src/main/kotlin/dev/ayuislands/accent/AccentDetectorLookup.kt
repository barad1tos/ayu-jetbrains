package dev.ayuislands.accent

import com.intellij.openapi.project.Project

/**
 * How the resolution engine consults [ProjectLanguageDetector] per rung.
 *
 * The live resolver and the diagnostics chain intentionally diverge here: the
 * live path may warm the detector cache (kick a scan) because its answer is
 * applied to the UI, while diagnostics must stay read-only so opening Settings
 * never schedules background work. On a cold cache the two can therefore
 * disagree — live resolve may already serve a language override while the
 * chain trace still shows "Detection pending". That asymmetry is a feature;
 * this seam pins each caller's exact detector calls.
 */
internal sealed interface AccentDetectorLookup {
    /**
     * Verdict backing the language-override rung, or `null` when this lookup
     * does not consult the detector for the given candidate set. A `null`
     * marks the rung as "skipped" — the engine renders a skip step and the
     * fallback rung later receives `null` as its consulted-verdict input.
     */
    fun languageRungVerdict(
        project: Project,
        hasLanguageCandidate: Boolean,
        hasFallbackCandidate: Boolean,
    ): ProjectLanguageVerdict?

    /**
     * Verdict backing the project-fallback rung's no-winner check.
     * [languageRungVerdict] is the value the language rung consulted, or
     * `null` when that rung was skipped (forced-language entry present, or a
     * warming lookup with no language candidates).
     */
    fun fallbackRungVerdict(
        project: Project,
        languageRungVerdict: ProjectLanguageVerdict?,
    ): ProjectLanguageVerdict

    /**
     * Read-only lookup for diagnostics surfaces: cache reads only, except the
     * forced-language + fallback-candidate corner, which warms the cache
     * (`warmCache = true`) so the fallback answer is not permanently stuck on
     * "Detection pending".
     */
    data object CacheOnlyLookup : AccentDetectorLookup {
        override fun languageRungVerdict(
            project: Project,
            hasLanguageCandidate: Boolean,
            hasFallbackCandidate: Boolean,
        ): ProjectLanguageVerdict? =
            if (hasLanguageCandidate || hasFallbackCandidate) {
                ProjectLanguageDetector.verdict(project)
            } else {
                null
            }

        override fun fallbackRungVerdict(
            project: Project,
            languageRungVerdict: ProjectLanguageVerdict?,
        ): ProjectLanguageVerdict = languageRungVerdict ?: ProjectLanguageDetector.verdict(project, warmCache = true)
    }

    /**
     * Strictly read-only lookup for the pending cache-only preview: NO rung
     * ever warms the cache, so merely opening or refreshing the read-only
     * Settings resolution panel cannot enqueue a background scan. This
     * reproduces the pre-engine pending walker exactly
     * (`warmCache = !detectorConsulted && warmDetector` — always false when
     * cacheOnly). Diagnostics chains use [CacheOnlyLookup] instead, whose
     * fallback-corner warm-up is pre-existing pinned behavior.
     */
    data object StrictCacheOnlyLookup : AccentDetectorLookup {
        override fun languageRungVerdict(
            project: Project,
            hasLanguageCandidate: Boolean,
            hasFallbackCandidate: Boolean,
        ): ProjectLanguageVerdict? =
            if (hasLanguageCandidate || hasFallbackCandidate) {
                ProjectLanguageDetector.verdict(project)
            } else {
                null
            }

        override fun fallbackRungVerdict(
            project: Project,
            languageRungVerdict: ProjectLanguageVerdict?,
        ): ProjectLanguageVerdict = languageRungVerdict ?: ProjectLanguageDetector.verdict(project)
    }

    /**
     * Live-resolve lookup: the language rung rides [ProjectLanguageDetector.dominant]
     * (which warms the cache off the EDT and schedules a background scan on it),
     * and the fallback rung warms only when the language rung was not consulted.
     * `AccentResolverTest` pins this call pattern with interaction assertions —
     * an extra warm call would schedule scans on projects with no override work.
     */
    data object WarmingLookup : AccentDetectorLookup {
        override fun languageRungVerdict(
            project: Project,
            hasLanguageCandidate: Boolean,
            hasFallbackCandidate: Boolean,
        ): ProjectLanguageVerdict? {
            if (!hasLanguageCandidate) return null
            val dominantLanguageId = ProjectLanguageDetector.dominant(project) ?: return ProjectLanguageVerdict.Cold
            return ProjectLanguageVerdict.Detected(dominantLanguageId, weights = null)
        }

        override fun fallbackRungVerdict(
            project: Project,
            languageRungVerdict: ProjectLanguageVerdict?,
        ): ProjectLanguageVerdict = ProjectLanguageDetector.verdict(project, warmCache = languageRungVerdict == null)
    }
}
