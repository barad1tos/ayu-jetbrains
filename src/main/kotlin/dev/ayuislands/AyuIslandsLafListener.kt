package dev.ayuislands

import com.intellij.ide.ui.LafManager
import com.intellij.ide.ui.LafManagerListener
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.ProjectManager
import dev.ayuislands.accent.AccentApplicator
import dev.ayuislands.accent.AyuVariant
import dev.ayuislands.font.FontPresetApplicator
import dev.ayuislands.glow.GlowOverlayManager
import dev.ayuislands.projectview.ProjectViewScrollbarManager
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
            GlowOverlayManager.syncGlowForAllProjects()
            return
        }

        val settings = AyuIslandsSettings.getInstance()
        val accentHex = settings.getAccentForVariant(variant)
        AccentApplicator.apply(accentHex)
        LOG.info("Ayu Islands accent re-applied on theme change: $accentHex")

        // Re-apply font preset if enabled
        FontPresetApplicator.applyFromState()

        // Track manual sub-variant choices for appearance sync
        val syncService = AppearanceSyncService.getInstance()
        if (settings.state.followSystemAppearance && !syncService.programmaticSwitch) {
            val themeName = source.currentUIThemeLookAndFeel.name
            syncService.recordManualChoice(themeName)
        }
        syncService.clearProgrammaticSwitch()

        // Re-apply Project View scrollbar setting
        reapplyProjectViewScrollbar()

        // Update glow overlays with new accent color
        GlowOverlayManager.syncGlowForAllProjects()
    }

    private fun reapplyProjectViewScrollbar() {
        if (!AyuIslandsSettings.getInstance().state.hideProjectViewHScrollbar) return
        for (openProject in ProjectManager.getInstance().openProjects) {
            try {
                ProjectViewScrollbarManager.getInstance(openProject).apply()
            } catch (exception: RuntimeException) {
                LOG.warn("Failed to re-apply scrollbar for project ${openProject.name}: ${exception.message}")
            }
        }
    }

    companion object {
        private val LOG = logger<AyuIslandsLafListener>()
    }
}
