package dev.ayuislands

import com.intellij.ide.ui.LafManager
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.logger
import dev.ayuislands.accent.AyuVariant
import dev.ayuislands.accent.SystemAppearanceProvider
import dev.ayuislands.accent.SystemAppearanceProvider.Appearance
import dev.ayuislands.settings.AyuIslandsSettings
import javax.swing.SwingUtilities

@Service
class AppearanceSyncService {
    private var lastSyncedAppearance: Appearance? = null

    /** Set by [syncIfNeeded] before programmatic switch, cleared by [AyuIslandsLafListener]. */
    @Volatile
    var programmaticSwitch: Boolean = false
        private set

    fun clearProgrammaticSwitch() {
        programmaticSwitch = false
    }

    fun syncIfNeeded() {
        val appearance = SystemAppearanceProvider.resolve() ?: return
        if (appearance == lastSyncedAppearance) return

        // Skip when no Ayu theme is active — don't update the lastSyncedAppearance
        // so sync triggers immediately when an Ayu theme becomes active
        if (AyuVariant.detect() == null) return

        val settings = AyuIslandsSettings.getInstance()
        val targetThemeName =
            when (appearance) {
                Appearance.DARK -> settings.state.lastDarkAppearanceTheme
                Appearance.LIGHT -> settings.state.lastLightAppearanceTheme
            } ?: return

        lastSyncedAppearance = appearance
        switchToTheme(targetThemeName)
    }

    /** Records which theme the user manually picked for the given appearance slot. */
    fun recordManualChoice(themeName: String) {
        val variant = AyuVariant.fromThemeName(themeName) ?: return
        val settings = AyuIslandsSettings.getInstance()
        when (variant) {
            AyuVariant.MIRAGE, AyuVariant.DARK -> settings.state.lastDarkAppearanceTheme = themeName
            AyuVariant.LIGHT -> settings.state.lastLightAppearanceTheme = themeName
        }
        LOG.info("Recorded manual appearance choice: $themeName")
    }

    @Suppress("UnstableApiUsage")
    private fun switchToTheme(targetThemeName: String) {
        val lafManager = LafManager.getInstance()
        val currentThemeName = lafManager.currentUIThemeLookAndFeel.name
        if (currentThemeName == targetThemeName) return

        val target =
            lafManager.installedThemes
                .firstOrNull { it.name == targetThemeName }
        if (target == null) {
            LOG.warn("Target theme not found: $targetThemeName")
            return
        }

        LOG.info("Switching theme: $currentThemeName -> $targetThemeName")
        programmaticSwitch = true
        SwingUtilities.invokeLater {
            lafManager.setCurrentLookAndFeel(target, true)
            lafManager.updateUI()
        }
    }

    companion object {
        private val LOG = logger<AppearanceSyncService>()

        fun getInstance(): AppearanceSyncService =
            ApplicationManager
                .getApplication()
                .getService(AppearanceSyncService::class.java)
    }
}
