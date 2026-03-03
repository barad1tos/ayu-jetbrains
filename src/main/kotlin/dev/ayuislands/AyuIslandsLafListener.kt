package dev.ayuislands

import com.intellij.ide.ui.LafManager
import com.intellij.ide.ui.LafManagerListener
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.ProjectManager
import dev.ayuislands.accent.AccentApplicator
import dev.ayuislands.accent.AyuVariant
import dev.ayuislands.glow.GlowOverlayManager
import dev.ayuislands.settings.AyuIslandsSettings

/** Re-applies accent color on theme change. */
class AyuIslandsLafListener : LafManagerListener {
    override fun lookAndFeelChanged(source: LafManager) {
        val variant = AyuVariant.detect()
        if (variant == null) {
            // Switched away from the Ayu theme -- clean up accent overrides and glow overlays
            AccentApplicator.revertAll()
            updateGlowForAllProjects()
            return
        }

        val settings = AyuIslandsSettings.getInstance()
        val accentHex = settings.getAccentForVariant(variant)
        AccentApplicator.apply(accentHex)
        LOG.info("Ayu Islands accent re-applied on theme change: $accentHex")

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
