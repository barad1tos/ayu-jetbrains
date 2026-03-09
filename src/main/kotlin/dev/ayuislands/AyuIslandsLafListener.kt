package dev.ayuislands

import com.intellij.ide.ui.LafManager
import com.intellij.ide.ui.LafManagerListener
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.ProjectManager
import dev.ayuislands.accent.AccentApplicator
import dev.ayuislands.accent.AyuVariant
import dev.ayuislands.font.FontPreset
import dev.ayuislands.font.FontPresetApplicator
import dev.ayuislands.font.FontSettings
import dev.ayuislands.glow.GlowOverlayManager
import dev.ayuislands.settings.AyuIslandsSettings

/** Re-applies accent color on theme change. */
class AyuIslandsLafListener : LafManagerListener {
    @Suppress("UnstableApiUsage")
    override fun lookAndFeelChanged(source: LafManager) {
        val variant = AyuVariant.detect()
        if (variant == null) {
            // Switched away from the Ayu theme -- clean up accent overrides and glow overlays
            AccentApplicator.revertAll()
            FontPresetApplicator.revert()
            updateGlowForAllProjects()
            return
        }

        val settings = AyuIslandsSettings.getInstance()
        val accentHex = settings.getAccentForVariant(variant)
        AccentApplicator.apply(accentHex)
        LOG.info("Ayu Islands accent re-applied on theme change: $accentHex")

        // Re-apply font preset if enabled
        if (settings.state.fontPresetEnabled) {
            val fontPreset = FontPreset.fromName(settings.state.fontPresetName)
            val encoded = settings.state.fontPresetCustomizations[fontPreset.name]
            val fontSettings = FontSettings.decode(encoded, fontPreset)
            FontPresetApplicator.apply(
                fontSettings.copy(applyToConsole = settings.state.fontApplyToConsole),
            )
        }

        // Track manual sub-variant choices for appearance sync
        val syncService = AppearanceSyncService.getInstance()
        if (settings.state.followSystemAppearance && !syncService.programmaticSwitch) {
            val themeName = source.currentUIThemeLookAndFeel.name
            syncService.recordManualChoice(themeName)
        }
        syncService.clearProgrammaticSwitch()

        // Update glow overlays with new accent color
        updateGlowForAllProjects()
    }

    private fun updateGlowForAllProjects() {
        for (openProject in ProjectManager.getInstance().openProjects) {
            try {
                GlowOverlayManager.getInstance(openProject).updateGlow()
            } catch (exception: RuntimeException) {
                LOG.warn("Failed to update glow for project ${openProject.name}: ${exception.message}")
            }
        }
    }

    companion object {
        private val LOG = logger<AyuIslandsLafListener>()
    }
}
