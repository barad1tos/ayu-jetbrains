package dev.ayuislands

import com.intellij.ide.plugins.cl.PluginAwareClassLoader
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.extensions.PluginDescriptor
import com.intellij.openapi.extensions.PluginId

/**
 * Identity of this plugin's own descriptor and the single lookup wrapper over
 * public IntelliJ Platform plugin APIs. Source of truth for
 * `com.ayuislands.theme` + its [PluginId], plus descriptor lookup semantics
 * (loaded-only narrowing, test-env tolerance) in one place.
 *
 * The lookup deliberately avoids IntelliJ's plugin registry facade: Marketplace
 * verification for 2026.2 EAP marks that surface as internal API. Instead, known
 * plugin descriptors are resolved from marker classes that become visible
 * through optional plugin dependencies declared in `plugin.xml`.
 */
internal object AyuPlugin {
    private val LOG = logger<AyuPlugin>()

    const val ID_STRING: String = "com.ayuislands.theme"
    val ID: PluginId by lazy { PluginId.getId(ID_STRING) }

    private const val CODE_GLANCE_PRO_ID = "com.nasller.CodeGlancePro"
    private const val CODE_GLANCE_PRO_MARKER_CLASS = "com.nasller.codeglance.config.CodeGlanceConfigService"
    private const val INDENT_RAINBOW_ID = "indent-rainbow.indent-rainbow"
    private const val INDENT_RAINBOW_MARKER_CLASS = "indent.rainbow.settings.IrConfig"

    /**
     * Returns the loaded descriptor for [pluginId], or `null` when the plugin
     * is not installed, is disabled, has no known public marker class, or when
     * the IDE [com.intellij.openapi.application.Application] is not bootstrapped
     * (only unit tests that do not start the platform see the last path).
     *
     * Disabled optional dependencies do not expose their classes to this plugin,
     * so classloader-based lookup preserves the enabled-only contract without
     * calling internal plugin registry APIs.
     */
    fun findLoadedPlugin(pluginId: PluginId): PluginDescriptor? {
        ApplicationManager.getApplication() ?: return null
        return try {
            val markerClassName = markerClassName(pluginId) ?: return null
            findDescriptorByMarkerClassName(markerClassName, pluginId)
        } catch (_: ClassNotFoundException) {
            null
        } catch (exception: RuntimeException) {
            logPluginLookupFailure(exception)
        }
    }

    internal fun descriptorFromPluginAwareClassLoader(
        classLoader: PluginAwareClassLoader,
        expectedPluginId: PluginId,
    ): PluginDescriptor? {
        val descriptor = classLoader.pluginDescriptor
        return descriptor.takeIf { it.pluginId == expectedPluginId }
    }

    private fun markerClassName(pluginId: PluginId): String? =
        when (pluginId.idString) {
            ID_STRING -> AyuPlugin::class.java.name
            CODE_GLANCE_PRO_ID -> CODE_GLANCE_PRO_MARKER_CLASS
            INDENT_RAINBOW_ID -> INDENT_RAINBOW_MARKER_CLASS
            else -> null
        }

    private fun findDescriptorByMarkerClassName(
        markerClassName: String,
        expectedPluginId: PluginId,
    ): PluginDescriptor? {
        val markerClass = Class.forName(markerClassName, false, AyuPlugin::class.java.classLoader)
        val classLoader = markerClass.classLoader as? PluginAwareClassLoader ?: return null
        return descriptorFromPluginAwareClassLoader(classLoader, expectedPluginId)
    }

    private fun logPluginLookupFailure(exception: RuntimeException): Nothing? {
        LOG.warn(
            "AyuPlugin.findLoadedPlugin: plugin descriptor lookup failed " +
                "(expected only in tests with a partially mocked Application).",
            exception,
        )
        return null
    }
}
