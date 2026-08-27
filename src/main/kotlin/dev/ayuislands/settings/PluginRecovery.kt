package dev.ayuislands.settings

import com.intellij.ide.BrowserUtil
import com.intellij.openapi.extensions.PluginId
import dev.ayuislands.AyuPlugin
import dev.ayuislands.syntax.LanguageSpecification
import dev.ayuislands.syntax.PluginRequirement

internal fun interface PluginAvailability {
    fun isLoaded(pluginId: String): Boolean
}

internal class PluginRecoveryResolver(
    private val plugins: PluginAvailability =
        PluginAvailability { pluginId ->
            AyuPlugin.findLoadedPlugin(PluginId.getId(pluginId)) != null
        },
) {
    fun unavailable(
        specification: LanguageSpecification,
        generation: Long,
    ): SyntaxProbeResult {
        val requirement = specification.pluginRequirement
        if (requirement != null && plugins.isLoaded(requirement.pluginId)) {
            return SyntaxProbeResult.Deferred(
                languageId = specification.storageId,
                generation = generation,
                reason = INSTALLED_PLUGIN_PENDING_MESSAGE,
            )
        }
        return SyntaxProbeResult.MissingPlugin(
            languageId = specification.storageId,
            generation = generation,
            recovery = PluginRecovery(requirement),
        )
    }

    private companion object {
        private const val INSTALLED_PLUGIN_PENDING_MESSAGE =
            "The installed language plugin has not registered syntax support yet. Retry."
    }
}

internal class PluginMarketplace(
    private val browse: (String) -> Unit = { url -> BrowserUtil.browse(url) },
) {
    fun open(requirement: PluginRequirement?) {
        browse(requirement?.marketplaceUrl ?: MARKETPLACE_URL)
    }

    private companion object {
        private const val MARKETPLACE_URL = "https://plugins.jetbrains.com/"
    }
}
