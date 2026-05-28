package dev.ayuislands

import com.intellij.ide.plugins.IdeaPluginDescriptor
import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.extensions.PluginId

/**
 * Identity of this plugin's own descriptor and the single lookup wrapper over
 * the platform plugin descriptor APIs. Source of truth for `com.ayuislands.theme`
 * + its [PluginId], plus the descriptor lookup so the production semantics
 * (enabled-only narrowing, test-env tolerance) live in one place.
 *
 * The wrapper deliberately avoids `PluginManager.findEnabledPlugin`: newer
 * Marketplace verification treats that method as internal API. The replacement
 * keeps the same enabled-only contract by checking [PluginManagerCore.isDisabled]
 * before reading [PluginManagerCore.getPlugin]. `ConflictRegistry` relies on the
 * null-for-disabled narrowing to skip disabled integrations.
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
     * Some unit tests mock a partially-bootstrapped [Application] while the
     * platform plugin set remains absent or synthetic. Catching runtime lookup
     * failures keeps those tests isolated; production should never hit this
     * branch, and the WARN keeps any future platform regression visible.
     */
    fun findEnabledPlugin(pluginId: PluginId): IdeaPluginDescriptor? {
        ApplicationManager.getApplication() ?: return null
        return try {
            if (PluginManagerCore.isDisabled(pluginId)) return null
            PluginManagerCore.getPlugin(pluginId)
        } catch (exception: IllegalStateException) {
            LOG.warn(
                "AyuPlugin.findEnabledPlugin: plugin descriptor lookup failed " +
                    "(expected only in tests with a partially mocked Application).",
                exception,
            )
            null
        } catch (exception: ClassCastException) {
            LOG.warn(
                "AyuPlugin.findEnabledPlugin: plugin descriptor lookup failed " +
                    "(expected only in tests with a partially mocked Application).",
                exception,
            )
            null
        }
    }
}
