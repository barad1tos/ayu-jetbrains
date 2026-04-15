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
        migrateUserHomeMacro(state)
    }

    /**
     * Back-fill migration: earlier builds persisted paths under `$USER_HOME$/...` because
     * path-macro substitution defaulted to on. Rewrite any such keys in-place on load so
     * existing users keep their mappings after this fix ships.
     */
    private fun migrateUserHomeMacro(state: AccentMappingsState) {
        val userHome = System.getProperty("user.home")?.takeIf { it.isNotBlank() }
        if (userHome == null) {
            logBlankUserHomeIfLegacyKeysPresent(state)
            return
        }

        // Wrap the productive rewrite path too. BaseState-backed maps are platform-shared and
        // mutable; a theoretical concurrent write during startup deserialization would throw
        // ConcurrentModificationException out of the iteration inside rewriteKeys, which
        // would propagate out of loadState and fail settings loading entirely.
        //
        // Compute BOTH rewrites before mutating EITHER map. rewriteKeys builds a new map and
        // returns it without touching the source — so if the second computation throws, the
        // first map is untouched and the state stays consistent (keys-in-lockstep invariant
        // between projectAccents / projectDisplayNames preserved). Only after both rewrites
        // succeed do we swap them into place. rewriteKeys may legitimately return null when
        // a map has no macro-prefixed keys — that's different from exceptional failure, so
        // we distinguish Result.isFailure explicitly rather than collapsing via getOrNull.
        val accentsResult = runCatching { rewriteKeys(state.projectAccents, userHome) }
        val namesResult = runCatching { rewriteKeys(state.projectDisplayNames, userHome) }
        if (accentsResult.isFailure || namesResult.isFailure) {
            // If both rewrites failed, link the second cause via addSuppressed so triage
            // doesn't lose visibility on the second failure mode.
            val primary = accentsResult.exceptionOrNull() ?: namesResult.exceptionOrNull()!!
            if (accentsResult.isFailure && namesResult.isFailure) {
                namesResult.exceptionOrNull()?.let { primary.addSuppressed(it) }
            }
            LOG.warn(
                "Failed to migrate \$USER_HOME\$-prefixed accent-mapping keys; " +
                    "will retry on next IDE restart",
                primary,
            )
            return
        }

        val rewrittenAccents = accentsResult.getOrNull()
        val rewrittenNames = namesResult.getOrNull()
        if (rewrittenAccents != null) {
            state.projectAccents.clear()
            state.projectAccents.putAll(rewrittenAccents)
        }
        if (rewrittenNames != null) {
            state.projectDisplayNames.clear()
            state.projectDisplayNames.putAll(rewrittenNames)
        }
    }

    /**
     * Surface blank-user.home to users so they can find a breadcrumb in idea.log. Silent no-op
     * means stored `$USER_HOME$/...` keys never match the absolute canonical path
     * AccentResolver hands to the map lookup — overrides become invisible with no trace.
     *
     * The probe itself is in runCatching because a CME reading `.keys` during startup
     * deserialization must not propagate out of `loadState` and fail settings loading.
     */
    private fun logBlankUserHomeIfLegacyKeysPresent(state: AccentMappingsState) {
        val hasLegacyKeys =
            runCatching {
                state.projectAccents.keys.any { it.startsWith(USER_HOME_MACRO) } ||
                    state.projectDisplayNames.keys.any { it.startsWith(USER_HOME_MACRO) }
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

    companion object {
        private val LOG = logger<AccentMappingsSettings>()
        private const val USER_HOME_MACRO = $$"$USER_HOME$"

        fun getInstance(): AccentMappingsSettings =
            ApplicationManager
                .getApplication()
                .getService(AccentMappingsSettings::class.java)
    }
}
