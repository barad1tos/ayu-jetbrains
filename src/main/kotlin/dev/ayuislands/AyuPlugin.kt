package dev.ayuislands

import com.intellij.ide.plugins.IdeaPluginDescriptor
import com.intellij.ide.plugins.PluginManager
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.extensions.PluginId

/**
 * Identity of this plugin's own descriptor and a thin test-tolerant wrapper
 * over the public [PluginManager] API. Single source of truth for the
 * `com.ayuislands.theme` string and its [PluginId] so the coordinates
 * only need to be updated in one place if they ever change.
 */
internal object AyuPlugin {
    const val ID_STRING: String = "com.ayuislands.theme"
    val ID: PluginId by lazy { PluginId.getId(ID_STRING) }

    /**
     * Returns the enabled descriptor for [pluginId], or `null` when the plugin
     * is not installed, is disabled, or when the IDE [com.intellij.openapi.application.Application]
     * is not bootstrapped (only happens in unit tests that do not start the
     * platform). A mocked test [com.intellij.openapi.application.Application]
     * whose `getService(PluginManager::class.java)` returns a `java.lang.Object`
     * (not a real [PluginManager]) also returns `null` — production never sees
     * this path. All other failures propagate.
     */
    fun findEnabledPlugin(pluginId: PluginId): IdeaPluginDescriptor? {
        ApplicationManager.getApplication() ?: return null
        return try {
            PluginManager.getInstance().findEnabledPlugin(pluginId)
        } catch (_: ClassCastException) {
            null
        }
    }
}
