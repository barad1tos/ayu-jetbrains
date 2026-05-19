package dev.ayuislands

import com.intellij.ide.plugins.IdeaPluginDescriptor
import com.intellij.ide.plugins.PluginManager
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.extensions.PluginId

/**
 * Identity of this plugin's own descriptor and the single lookup wrapper over
 * the public [PluginManager] API. Source of truth for `com.ayuislands.theme`
 * + its [PluginId], plus the descriptor lookup so the production semantics
 * (enabled-only narrowing, test-env tolerance) live in one place.
 *
 * The wrapper deliberately calls `PluginManager.getInstance().findEnabledPlugin`
 * (NOT `PluginManagerCore.getPlugin` or `PluginManager.getPlugin`). Both
 * `getPlugin` variants are flagged by the JetBrains Marketplace verifier:
 * the former as `@ApiStatus.Internal`, the latter as `@Deprecated`. Equally
 * important, `getPlugin` returns the descriptor even for DISABLED plugins,
 * while `findEnabledPlugin` returns null for disabled installations — and
 * `ConflictRegistry` relies on that narrowing to skip disabled integrations.
 */
internal object AyuPlugin {
    private val LOG = logger<AyuPlugin>()

    const val ID_STRING: String = "com.ayuislands.theme"
    val ID: PluginId by lazy { PluginId.getId(ID_STRING) }

    /**
     * Returns the enabled descriptor for [pluginId], or `null` when the
     * plugin is not installed, is disabled, or when the IDE
     * [com.intellij.openapi.application.Application] is not bootstrapped
     * (only unit tests that do not start the platform see the last path).
     *
     * `PluginManager.getInstance()` internally resolves the service via
     * `Application.getService(PluginManager::class.java)`. A mocked test
     * `Application` whose `getService` returns a `java.lang.Object` (not
     * a real [PluginManager]) triggers a `ClassCastException` inside the
     * platform-side cast in `getInstance()`. We catch + log + return null
     * so a partially-mocked Application in unit tests does not crash this
     * wrapper. Production never sees this path; the WARN keeps a future
     * platform CCE auditable.
     */
    fun findEnabledPlugin(pluginId: PluginId): IdeaPluginDescriptor? {
        ApplicationManager.getApplication() ?: return null
        return try {
            PluginManager.getInstance().findEnabledPlugin(pluginId)
        } catch (exception: ClassCastException) {
            LOG.warn(
                "AyuPlugin.findEnabledPlugin: ClassCastException from PluginManager.getInstance() — " +
                    "Application service registry returned a non-PluginManager value (expected only " +
                    "in unit tests with a partially mocked Application).",
                exception,
            )
            null
        }
    }
}
