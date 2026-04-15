package dev.ayuislands.accent

import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import dev.ayuislands.licensing.LicenseChecker
import dev.ayuislands.settings.AyuIslandsSettings
import dev.ayuislands.settings.mappings.AccentMappingsSettings
import org.jetbrains.annotations.TestOnly
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
     * Per-failure-mode warn gates for [projectKey] — see the [projectKey] KDoc for why.
     * Split across two gates so a project that previously hit one failure mode can still log
     * the other: a project whose `basePath` throws once, then later hands back a path whose
     * canonicalization fails, must not have the second warn silently dropped by a shared gate.
     */
    private val basePathWarnGate = OneShotProjectGate()
    private val canonicalWarnGate = OneShotProjectGate()

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
     * Returns `null` when:
     *  - the project has no base path
     *  - the project is being disposed concurrently (basePath / name access throws
     *    `AlreadyDisposedException` — possible when focus swap / rotation races with
     *    a project-close event)
     *  - canonicalization throws (permission error, symlink loop, missing directory,
     *    network-share unreachable)
     *
     * Failures are logged once per project per failure mode — this runs on hot paths
     * (focus swap, rotation tick) so we guard against log spam via per-mode
     * [OneShotProjectGate]s ([basePathWarnGate] and [canonicalWarnGate]). `basePath` /
     * `name` access are each wrapped in [runCatchingPreservingCancellation] so a race
     * mid-dispose degrades to "return null" silently instead of escalating through the
     * resolver — while still letting [kotlin.coroutines.cancellation.CancellationException]
     * propagate, since the resolver is reachable from coroutine contexts
     * (`AyuIslandsStartupActivity.execute`) where swallowing cancellation would keep
     * a cancelled coroutine alive past its structured-concurrency boundary.
     */
    fun projectKey(project: Project): String? {
        val raw =
            runCatchingPreservingCancellation { project.basePath }
                .onFailure { exception ->
                    // Symmetric with the canonicalization branch below: a platform regression
                    // making `project.basePath` throw for any project type would silently
                    // demote every project to global accent without a breadcrumb unless we
                    // log here too. `project.name` is read defensively for the same reason —
                    // when basePath throws, name access is likely to throw from the same
                    // mid-dispose race, and we'd rather log a marker than a NPE.
                    if (basePathWarnGate.tryAcquire(project)) {
                        val name =
                            runCatchingPreservingCancellation { project.name }
                                .getOrDefault("<disposed>")
                        LOG.warn(
                            "Failed to read basePath for project '$name'; falling back to global accent",
                            exception,
                        )
                    }
                }.getOrNull() ?: return null
        return runCatchingPreservingCancellation { File(raw).canonicalPath }
            .onFailure { exception ->
                if (canonicalWarnGate.tryAcquire(project)) {
                    val name =
                        runCatchingPreservingCancellation { project.name }
                            .getOrDefault("<disposed>")
                    LOG.warn(
                        "Failed to canonicalize basePath for '$name' ($raw); " +
                            "project-override resolution will fall back to global accent",
                        exception,
                    )
                }
            }.getOrNull()
    }

    /**
     * Test-only seam: clear both warn gates so each test gets a fresh budget. Without this,
     * `object`-scoped state leaks across test methods when a Project mock happens to be
     * reused, silently dropping dedup-assertable warns under test-ordering variance.
     */
    @TestOnly
    internal fun resetWarnGatesForTest() {
        basePathWarnGate.clear()
        canonicalWarnGate.clear()
    }

    /**
     * One-shot per-project warn gate. [tryAcquire] returns `true` the first time a given
     * project is seen and `false` on every subsequent call — so hot-path callers can emit
     * a warn at first failure without spamming idea.log every focus swap / rotation tick.
     *
     * Backed by a synchronized WeakHashMap because the resolver is called from multiple
     * threads (EDT from the AWT listener, coroutine from
     * [dev.ayuislands.AyuIslandsStartupActivity], background from rotation). Weak references
     * let entries age out with project disposal, so long-running IDE sessions don't
     * accumulate unbounded gate state as projects open and close.
     *
     * Encapsulated as its own type so the invariant ("membership = warn-has-fired; only
     * `add()`'s return value is load-bearing") lives in the type instead of comments, and
     * the `Set.clear / contains / remove / iterate` surface is hidden from callers.
     */
    private class OneShotProjectGate {
        private val seen: MutableSet<Project> =
            Collections.synchronizedSet(Collections.newSetFromMap(WeakHashMap()))

        fun tryAcquire(project: Project): Boolean = seen.add(project)

        fun clear() {
            seen.clear()
        }
    }
}
