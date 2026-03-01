package dev.ayuislands

import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.ide.ui.LafManager
import com.intellij.ide.ui.LafManagerListener
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.project.ProjectManager
import dev.ayuislands.accent.AccentApplicator
import dev.ayuislands.accent.AyuVariant
import dev.ayuislands.glow.GlowOverlayManager
import dev.ayuislands.settings.AyuIslandsSettings

/** Re-applies accent color on theme change and syncs CodeGlance Pro viewport color. */
class AyuIslandsLafListener : LafManagerListener {

    override fun lookAndFeelChanged(source: LafManager) {
        val variant = AyuVariant.detect()
        if (variant == null) {
            // Switched away from Ayu theme -- remove glow overlays
            updateGlowForAllProjects()
            return
        }

        val settings = AyuIslandsSettings.getInstance()
        val accentHex = settings.getAccentForVariant(variant)
        AccentApplicator.apply(accentHex)
        LOG.info("Ayu Islands accent re-applied on theme change: $accentHex")

        if (settings.state.cgpIntegrationEnabled) {
            applyCgpViewportColor(accentHex)
        }

        // Update glow overlays with new accent color
        updateGlowForAllProjects()
    }

    private fun updateGlowForAllProjects() {
        for (openProject in ProjectManager.getInstance().openProjects) {
            try {
                GlowOverlayManager.getInstance(openProject).updateGlow()
            } catch (exception: Exception) {
                LOG.warn("Failed to update glow for project ${openProject.name}: ${exception.message}")
            }
        }
    }

    fun applyCgpViewportColor(accentHex: String) {
        try {
            val hexWithoutHash = accentHex.removePrefix("#")

            // Use CodeGlance's own classloader to avoid ClassCastException across plugin boundaries
            val cgpPlugin = PluginManagerCore.getPlugin(PluginId.getId("com.nasller.CodeGlancePro"))
                ?: throw IllegalStateException("CodeGlance Pro plugin not found")
            val cgpClassLoader = cgpPlugin.pluginClassLoader
                ?: throw IllegalStateException("CodeGlance Pro classloader not available")

            val serviceClass = Class.forName(
                "com.nasller.codeglance.config.CodeGlanceConfigService",
                true,
                cgpClassLoader,
            )

            // Get service via IntelliJ's service manager (returns instance from CGP's classloader)
            val service = ApplicationManager.getApplication().getService(serviceClass)
                ?: throw IllegalStateException("CodeGlanceConfigService not registered")

            // SimplePersistentStateComponent.getState() returns the config object
            val config = service.javaClass.getMethod("getState").invoke(service)

            config.javaClass.getMethod("setViewportColor", String::class.java)
                .invoke(config, hexWithoutHash)
            config.javaClass.getMethod("setViewportBorderColor", String::class.java)
                .invoke(config, hexWithoutHash)

            // The viewport overlay paints at 15% opacity — the color difference is invisible.
            // The border renders at 100% opacity, so set a visible thickness.
            config.javaClass.getMethod("setViewportBorderThickness", Int::class.java)
                .invoke(config, 2)

            // Repaint all GlancePanel components directly instead of onGlobalChanged()
            // which does a full dispose+recreate and may reset our in-memory config changes.
            refreshCodeGlancePanels(cgpClassLoader)

            LOG.info("CodeGlance Pro viewport + border color set to $hexWithoutHash")
        } catch (exception: Exception) {
            val cause = if (exception is java.lang.reflect.InvocationTargetException) exception.cause else exception
            LOG.warn("CodeGlance Pro integration failed: ${cause?.javaClass?.simpleName}: ${cause?.message}")
        }
    }

    /**
     * Repaints all CodeGlance Pro panels without dispose+recreate.
     * The config singleton already has updated values — panels read them during paint().
     * We skip onGlobalChanged() because it destroys/recreates panels on EDT
     * which fails from background threads and is unnecessarily heavy.
     */
    private fun refreshCodeGlancePanels(@Suppress("UNUSED_PARAMETER") cgpClassLoader: ClassLoader) {
        javax.swing.SwingUtilities.invokeLater {
            for (window in java.awt.Window.getWindows()) {
                repaintCodeGlancePanels(window)
            }
        }
    }

    private fun repaintCodeGlancePanels(container: java.awt.Container) {
        for (component in container.components) {
            if (component.javaClass.name.contains("GlancePanel")) {
                component.repaint()
            }
            if (component is java.awt.Container) {
                repaintCodeGlancePanels(component)
            }
        }
    }

    companion object {
        private val LOG = logger<AyuIslandsLafListener>()
    }
}
