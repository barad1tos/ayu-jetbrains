package dev.ayuislands.accent

import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import dev.ayuislands.licensing.LicenseChecker
import dev.ayuislands.settings.AyuIslandsSettings
import dev.ayuislands.settings.AyuIslandsState
import dev.ayuislands.settings.mappings.AccentMappingsSettings
import org.jetbrains.annotations.TestOnly
import java.awt.Color
import java.io.File
import java.util.Collections
import java.util.Locale
import java.util.WeakHashMap
import javax.swing.UIManager

/**
 * Resolves the effective accent hex for a project + accent context in priority order.
 *
 * Native [AyuVariant] contexts keep the historical project + variant chain:
 *
 *  1. **Project override** — `AccentMappingsState.projectAccents` keyed by the project's
 *     canonical base path.
 *  2. **Forced language override** — per-project language id mapped to a language accent.
 *  3. **Language override** — dominant language of the project via [ProjectLanguageDetector].
 *  4. **Language fallback override** — default language accent for detected ids without an exact mapping.
 *  5. **Project fallback** — applied only when [ProjectLanguageDetector.verdict] is
 *     [ProjectLanguageVerdict.NoWinner].
 *  6. **Global** — [AyuIslandsSettings.getAccentForVariant] (which itself honors
 *     follow-system-accent and per-variant stored hex).
 *
 * Per-project and per-language overrides are premium features: when the license check
 * fails, the resolver short-circuits to the global accent regardless of stored mappings.
 * The UI disables override add/edit for unlicensed users, but this guard protects against
 * trial expiry with previously-stored mappings and against manually imported settings XML.
 *
 * Zero-cost path: when no forced-language, language, or project-fallback work exists
 * for a project, the detector is never consulted and resolution stays on pure map lookups.
 */
object AccentResolver {
    enum class Source {
        PROJECT_OVERRIDE,
        FORCED_LANGUAGE_OVERRIDE,
        LANGUAGE_OVERRIDE,
        LANGUAGE_FALLBACK_OVERRIDE,
        PROJECT_FALLBACK,
        MATERIAL_THEME,
        IDE_ACCENT,
        EXTERNAL_ACCENT,
        GLOBAL,
    }

    private val LOG = logger<AccentResolver>()

    /**
     * Per-failure-mode warn gates for [projectKey] — see the [projectKey] KDoc for why.
     * Split across two gates so a project that previously hit one failure mode can still log
     * the other: a project whose `basePath` throws once, then later hands back a path whose
     * canonicalization fails, must not have the second warn silently dropped by a shared gate.
     */
    private val basePathWarnGate = OneShotProjectGate()
    private val canonicalWarnGate = OneShotProjectGate()

    private val uiColorProviderOverride = ThreadLocal<(String) -> Color?>()

    private fun uiColor(key: String): Color? = uiColorProviderOverride.get()?.invoke(key) ?: UIManager.getColor(key)

    /**
     * Resolves the effective accent hex. Delegates to the shared override-traversal helper,
     * falling back to the global per-variant accent when no override applies.
     */
    fun resolve(
        project: Project?,
        variant: AyuVariant,
    ): String = resolveAyu(project, variant).hex

    fun resolve(
        project: Project?,
        context: AccentContext,
    ): String = resolveContext(project, context).hex

    /**
     * Returns which layer of the resolution chain produced the accent for [project].
     * Used by the settings UI to surface "Currently active: ... (project override)" context.
     *
     * Mirrors the license gate in [resolve]: unlicensed callers always see [Source.GLOBAL],
     * so the UI label does not claim a project/language override is "active" when the
     * resolver is actually returning the global accent.
     */
    fun source(project: Project?): Source = findOverride(project, validateHex = false)?.source ?: Source.GLOBAL

    fun source(
        project: Project?,
        context: AccentContext,
    ): Source = resolveContext(project, context).source

    /**
     * Human-readable label for [source], suitable for tooltips and Settings
     * "Currently active: …" readouts. The quick-switcher chip tooltip
     * (`"{hex} — {label}"`) consumes this label; the `AyuIslandsAccentPanel`
     * "currently active" comment may be migrated to use this helper in a
     * follow-up to deduplicate the [Source]-to-string mapping.
     *
     * Pure pattern-match over the closed [Source] enum; no IO, no reflection.
     * Pattern L regression lock lives in `AccentResolverSourceLabelTest` —
     * adding a new [Source] value without extending this helper fails the
     * `Source.entries.size == 9` assertion so silent "Global" fallback drift
     * cannot land.
     */
    fun sourceLabel(source: Source): String =
        when (source) {
            Source.PROJECT_OVERRIDE -> "Project override"
            Source.FORCED_LANGUAGE_OVERRIDE -> "Forced language override"
            Source.LANGUAGE_OVERRIDE -> "Language override"
            Source.LANGUAGE_FALLBACK_OVERRIDE -> "Language fallback override"
            Source.PROJECT_FALLBACK -> "Project fallback"
            Source.MATERIAL_THEME -> "Material Theme"
            Source.IDE_ACCENT -> "IDE accent"
            Source.EXTERNAL_ACCENT -> "External accent"
            Source.GLOBAL -> "Global"
        }

    private fun resolveContext(
        project: Project?,
        context: AccentContext,
    ): ResolvedAccent =
        when (context) {
            is AccentContext.Ayu -> resolveAyu(project, context.ayuVariant)
            AccentContext.External -> resolveExternal(project)
        }

    private fun resolveAyu(
        project: Project?,
        variant: AyuVariant,
    ): ResolvedAccent {
        val globalAccent = AyuIslandsSettings.getInstance().getAccentForVariant(variant)
        return findOverride(project, validateHex = false) ?: ResolvedAccent(Source.GLOBAL, globalAccent)
    }

    private fun resolveExternal(project: Project?): ResolvedAccent {
        val state = AyuIslandsSettings.getInstance().state
        if (ExternalAccentSource.fromName(state.externalThemeAccentSource) == ExternalAccentSource.MANUAL) {
            return storedExternalAccent(state)
        }

        findOverride(project, validateHex = true)?.let { return it }
        uiColorAccent(MATERIAL_ACCENT_KEY, Source.MATERIAL_THEME)?.let { return it }
        uiColorAccent(COMPONENT_ACCENT_KEY, Source.IDE_ACCENT)?.let { return it }
        uiColorAccent(ACTIONS_BLUE_KEY, Source.IDE_ACCENT)?.let { return it }
        return storedExternalAccent(state)
    }

    private fun uiColorAccent(
        key: String,
        source: Source,
    ): ResolvedAccent? = uiColor(key)?.let { ResolvedAccent(source, it.toHex()) }

    private fun storedExternalAccent(state: AyuIslandsState): ResolvedAccent {
        val hex = AccentHex.of(state.externalThemeAccent)?.value ?: AyuVariant.MIRAGE.defaultAccent
        return ResolvedAccent(Source.EXTERNAL_ACCENT, hex)
    }

    private fun findOverride(
        project: Project?,
        validateHex: Boolean,
    ): ResolvedAccent? {
        if (!LicenseChecker.isLicensedOrGrace()) return null
        val activeProject =
            project
                ?.takeUnless { it.isDefault }
                ?.takeUnless { it.isDisposed }
                ?: return null

        val mappings = AccentMappingsSettings.getInstance().state
        val projectKey = projectKey(activeProject) ?: return null
        mappings.projectAccents[projectKey]
            ?.let { rawHex -> overrideAccent(Source.PROJECT_OVERRIDE, rawHex, validateHex)?.let { return it } }

        val languageRequest =
            LanguageOverrideRequest(
                languageAccents = mappings.languageAccents,
                hasForcedLanguageEntry = mappings.forcedProjectLanguages.containsKey(projectKey),
                forcedLanguageId = mappings.forcedProjectLanguages[projectKey]?.trim()?.takeIf { it.isNotEmpty() },
                languageFallbackAccent = mappings.languageFallbackAccent?.trim()?.takeIf { it.isNotEmpty() },
                validateHex = validateHex,
            )
        val hasProjectFallbackCandidate = mappings.projectFallbackAccents.containsKey(projectKey)
        if (!languageRequest.hasResolutionWork && !hasProjectFallbackCandidate) return null

        val (languageOverride, detectorConsulted) =
            resolveLanguageOverride(
                project = activeProject,
                request = languageRequest,
            )
        languageOverride?.let { return it }
        mappings.projectFallbackAccents[projectKey]
            ?.also {
                if (!detectorConsulted) {
                    ProjectLanguageDetector.dominant(activeProject)
                }
            }?.takeIf { ProjectLanguageDetector.verdict(activeProject) is ProjectLanguageVerdict.NoWinner }
            ?.let { rawHex -> overrideAccent(Source.PROJECT_FALLBACK, rawHex, validateHex)?.let { return it } }
        return null
    }

    private fun resolveLanguageOverride(
        project: Project,
        request: LanguageOverrideRequest,
    ): Pair<ResolvedAccent?, Boolean> {
        request.forcedLanguageId
            ?.let { languageId ->
                overrideAccentForLanguage(
                    languageAccents = request.languageAccents,
                    languageFallbackAccent = request.languageFallbackAccent,
                    languageId = languageId,
                    exactSource = Source.FORCED_LANGUAGE_OVERRIDE,
                    validateHex = request.validateHex,
                )?.let { return it to false }
            }
        if (!request.shouldDetectLanguage) return null to false
        val resolvedAccent =
            ProjectLanguageDetector
                .dominant(project)
                ?.let { languageId ->
                    overrideAccentForLanguage(
                        languageAccents = request.languageAccents,
                        languageFallbackAccent = request.languageFallbackAccent,
                        languageId = languageId,
                        exactSource = Source.LANGUAGE_OVERRIDE,
                        validateHex = request.validateHex,
                    )
                }
        return resolvedAccent to true
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

    @TestOnly
    internal fun resetUiColorProviderForTest() {
        uiColorProviderOverride.remove()
    }

    @TestOnly
    internal fun <T> withUiColorProviderForTest(
        provider: (String) -> Color?,
        block: () -> T,
    ): T {
        val previous = uiColorProviderOverride.get()
        uiColorProviderOverride.set(provider)
        return try {
            block()
        } finally {
            if (previous == null) {
                uiColorProviderOverride.remove()
            } else {
                uiColorProviderOverride.set(previous)
            }
        }
    }

    private fun Color.toHex(): String = "#%02X%02X%02X".format(Locale.ROOT, red, green, blue)

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

    private const val MATERIAL_ACCENT_KEY = "material.accent"
    private const val COMPONENT_ACCENT_KEY = "Component.accentColor"
    private const val ACTIONS_BLUE_KEY = "Actions.Blue"
}

private data class LanguageOverrideRequest(
    val languageAccents: Map<String, String>,
    val hasForcedLanguageEntry: Boolean,
    val forcedLanguageId: String?,
    val languageFallbackAccent: String?,
    val validateHex: Boolean,
) {
    private val hasLanguageCandidate = languageAccents.isNotEmpty() || languageFallbackAccent != null

    val hasResolutionWork: Boolean =
        hasLanguageCandidate && (forcedLanguageId != null || !hasForcedLanguageEntry)

    val shouldDetectLanguage: Boolean =
        hasLanguageCandidate && !hasForcedLanguageEntry
}

private data class ResolvedAccent(
    val source: AccentResolver.Source,
    val hex: String,
)

private fun overrideAccentForLanguage(
    languageAccents: Map<String, String>,
    languageFallbackAccent: String?,
    languageId: String,
    exactSource: AccentResolver.Source,
    validateHex: Boolean,
): ResolvedAccent? {
    languageAccents[languageId]
        ?.let { rawHex -> overrideAccent(exactSource, rawHex, validateHex) }
        ?.let { return it }
    return languageFallbackAccent
        ?.let { rawHex -> overrideAccent(AccentResolver.Source.LANGUAGE_FALLBACK_OVERRIDE, rawHex, validateHex) }
}

private fun overrideAccent(
    source: AccentResolver.Source,
    rawHex: String,
    validateHex: Boolean,
): ResolvedAccent? {
    if (!validateHex) {
        return ResolvedAccent(source, rawHex)
    }
    return AccentHex.of(rawHex)?.let { ResolvedAccent(source, it.value) }
}
