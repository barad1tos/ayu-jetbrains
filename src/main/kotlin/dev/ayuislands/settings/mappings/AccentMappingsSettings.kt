package dev.ayuislands.settings.mappings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.SimplePersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.diagnostic.logger

/**
 * Application-level persistent store for per-project and per-language accent overrides.
 *
 * Lives in its own XML file (not [AyuIslandsState][dev.ayuislands.settings.AyuIslandsState])
 * to keep the existing state file focused and to reduce blast radius if nested-map
 * XML serialization surprises us.
 *
 * `usePathMacroManager = false`: project paths are stored verbatim (absolute canonical paths),
 * not auto-substituted with `$USER_HOME$` macros. The platform expands macros for known path
 * fields but NOT for arbitrary map keys, which would silently break
 * [dev.ayuislands.accent.AccentResolver] lookups — an absolute `projectKey` would never match
 * a macro-prefixed stored key. The `usePathMacroManager` flag is `@ApiStatus.Internal` on
 * [Storage], but it is the only documented way to opt out of macro substitution for arbitrary
 * map keys — hence the `@Suppress("UnstableApiUsage")`.
 */
@Suppress("UnstableApiUsage")
@Service
@State(
    name = "AyuIslandsAccentMappings",
    storages = [Storage(value = "ayuIslandsAccentMappings.xml", usePathMacroManager = false)],
)
class AccentMappingsSettings : SimplePersistentStateComponent<AccentMappingsState>(AccentMappingsState()) {
    override fun loadState(state: AccentMappingsState) {
        super.loadState(state)
        migrateUserHomeMacro(
            projectAccents = state.projectAccents,
            projectDisplayNames = state.projectDisplayNames,
            projectFallbackAccents = state.projectFallbackAccents,
            forcedProjectLanguages = state.forcedProjectLanguages,
        )
    }

    /**
     * Back-fill migration: earlier builds persisted paths under `$USER_HOME$/...` because
     * path-macro substitution defaulted to on. Rewrite any such keys in-place on load so
     * existing users keep their mappings after this fix ships.
     *
     * Takes maps as parameters (rather than an [AccentMappingsState]) so tests can substitute
     * throwing maps to exercise the retryable-failure branches. `BaseState`'s `map()` delegate
     * does not expose backing-map substitution — `setValue` copies entries rather than
     * swapping the reference — so there's no way to install a custom `MutableMap` subclass
     * on a real state instance via its public property; the only alternative to a map-typed
     * helper is reflection or a test-only subclass, both uglier.
     *
     * Visibility is `internal` rather than `@TestOnly` because [loadState] is a production
     * caller; `@TestOnly` would fire an inspection warning on the real call site. The sole
     * production call site is [loadState] above, which holds the keys-in-lockstep invariant
     * between `projectAccents` / `projectDisplayNames`. Future non-test callers should route
     * through [loadState] to preserve that invariant rather than invoking this directly.
     */
    internal fun migrateUserHomeMacro(
        projectAccents: MutableMap<String, String>,
        projectDisplayNames: MutableMap<String, String>,
        projectFallbackAccents: MutableMap<String, String>,
        forcedProjectLanguages: MutableMap<String, String>,
    ) {
        val userHome = System.getProperty("user.home")?.takeIf { it.isNotBlank() }
        if (userHome == null) {
            logBlankUserHomeIfLegacyKeysPresent(
                projectAccents = projectAccents,
                projectDisplayNames = projectDisplayNames,
                projectFallbackAccents = projectFallbackAccents,
                forcedProjectLanguages = forcedProjectLanguages,
            )
            return
        }

        // Wrap the productive rewrite path too, but only for retryable RuntimeExceptions.
        // BaseState-backed maps are platform-shared and mutable; a theoretical concurrent
        // write during startup deserialization would throw ConcurrentModificationException
        // out of the iteration inside rewriteKeys, which would propagate out of loadState
        // and fail settings loading entirely.
        //
        // Compute all four rewrites before mutating any map. rewriteKeys builds a new map and
        // returns it without touching the source — so if a later computation throws, earlier
        // maps stay untouched and the state remains consistent. Only after all four rewrites
        // succeed do we swap them into place. rewriteKeys may legitimately return null when
        // a map has no macro-prefixed keys — that's different from exceptional failure, so
        // we distinguish Result.isFailure explicitly rather than collapsing via getOrNull.
        val accentsResult = captureRuntimeException { rewriteKeys(projectAccents, userHome) }
        val namesResult = captureRuntimeException { rewriteKeys(projectDisplayNames, userHome) }
        val fallbackResult = captureRuntimeException { rewriteKeys(projectFallbackAccents, userHome) }
        val forcedResult = captureRuntimeException { rewriteKeys(forcedProjectLanguages, userHome) }
        if (listOf(accentsResult, namesResult, fallbackResult, forcedResult).any { it.isFailure }) {
            warnMigrationFailed(
                listOfNotNull(
                    accentsResult.exceptionOrNull(),
                    namesResult.exceptionOrNull(),
                    fallbackResult.exceptionOrNull(),
                    forcedResult.exceptionOrNull(),
                ),
            )
            return
        }

        val rewrittenAccents = accentsResult.getOrNull()
        val rewrittenNames = namesResult.getOrNull()
        val rewrittenFallbacks = fallbackResult.getOrNull()
        val rewrittenForcedLanguages = forcedResult.getOrNull()
        if (rewrittenAccents != null) {
            projectAccents.clear()
            projectAccents.putAll(rewrittenAccents)
        }
        if (rewrittenNames != null) {
            projectDisplayNames.clear()
            projectDisplayNames.putAll(rewrittenNames)
        }
        if (rewrittenFallbacks != null) {
            projectFallbackAccents.clear()
            projectFallbackAccents.putAll(rewrittenFallbacks)
        }
        if (rewrittenForcedLanguages != null) {
            forcedProjectLanguages.clear()
            forcedProjectLanguages.putAll(rewrittenForcedLanguages)
        }
    }

    /**
     * Emits a single WARN for a rewrite failure.
     * When multiple rewrites fail,
     * remaining causes are linked to the primary via [Throwable.addSuppressed]
     * so triage sees every failure mode instead of losing secondary context to
     * the `?:` collapse. When no cause is present (defensive — the call site's
     * `isFailure` gate should prevent this) the helper logs a self-describing
     * marker WARN instead of throwing, so a regression in the caller doesn't
     * become an NPE inside the log path.
     */
    internal fun warnMigrationFailed(causes: List<Throwable>) {
        val primary =
            causes.firstOrNull()
                ?: run {
                    LOG.warn(
                        "warnMigrationFailed invoked with successful results; " +
                            "call-site gate regressed - no cause to report",
                    )
                    return
                }
        causes.drop(1).forEach(primary::addSuppressed)
        LOG.warn(
            $$"Failed to migrate $USER_HOME$-prefixed accent-mapping keys; " +
                "will retry on next IDE restart",
            primary,
        )
    }

    /**
     * Surface blank-user.home to users so they can find a breadcrumb in idea.log. Silent no-op
     * means stored `$USER_HOME$/...` keys never match the absolute canonical path
     * AccentResolver hands to the map lookup — overrides become invisible with no trace.
     *
     * The probe itself catches retryable RuntimeExceptions because a CME reading `.keys`
     * during startup deserialization must not propagate out of `loadState` and fail
     * settings loading.
     */
    private fun logBlankUserHomeIfLegacyKeysPresent(
        projectAccents: Map<String, String>,
        projectDisplayNames: Map<String, String>,
        projectFallbackAccents: Map<String, String>,
        forcedProjectLanguages: Map<String, String>,
    ) {
        val hasLegacyKeys =
            captureRuntimeException {
                projectAccents.keys.any { it.startsWith(USER_HOME_MACRO) } ||
                    projectDisplayNames.keys.any { it.startsWith(USER_HOME_MACRO) } ||
                    projectFallbackAccents.keys.any { it.startsWith(USER_HOME_MACRO) } ||
                    forcedProjectLanguages.keys.any { it.startsWith(USER_HOME_MACRO) }
            }.getOrDefault(false)
        if (hasLegacyKeys) {
            LOG.warn(
                "Cannot migrate legacy $USER_HOME_MACRO-prefixed accent-mapping keys: " +
                    "System.getProperty(\"user.home\") is unavailable. " +
                    "Affected per-project overrides won't resolve until re-added under " +
                    "Settings > Ayu Islands > Accent > Overrides.",
            )
        }
    }

    private fun rewriteKeys(
        source: Map<String, String>,
        userHome: String,
    ): Map<String, String>? {
        if (source.none { it.key.startsWith(USER_HOME_MACRO) }) return null
        return source.mapKeys { (key, _) ->
            if (key.startsWith(USER_HOME_MACRO)) userHome + key.removePrefix(USER_HOME_MACRO) else key
        }
    }

    private inline fun <T> captureRuntimeException(block: () -> T): Result<T> =
        try {
            Result.success(block())
        } catch (exception: RuntimeException) {
            Result.failure(exception)
        }

    companion object {
        private val LOG = logger<AccentMappingsSettings>()
        private const val USER_HOME_MACRO = $$"$USER_HOME$"

        fun getInstance(): AccentMappingsSettings =
            ApplicationManager
                .getApplication()
                .getService(AccentMappingsSettings::class.java)
    }
}
