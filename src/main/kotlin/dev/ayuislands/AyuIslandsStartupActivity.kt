package dev.ayuislands

import com.intellij.ide.ui.LafManager
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import dev.ayuislands.accent.AccentApplicator
import dev.ayuislands.accent.AyuVariant
import dev.ayuislands.accent.conflict.ConflictRegistry
import dev.ayuislands.glow.GlowOverlayManager
import dev.ayuislands.licensing.LicenseChecker
import dev.ayuislands.settings.AyuIslandsSettings

internal class AyuIslandsStartupActivity : ProjectActivity {
    @Suppress("UnstableApiUsage")
    override suspend fun execute(project: Project) {
        val themeName = LafManager.getInstance().currentUIThemeLookAndFeel.name
        LOG.info("Ayu Islands loaded — active theme: $themeName, project: ${project.name}")

        val variant = AyuVariant.fromThemeName(themeName) ?: return

        // Apply persisted accent color
        val settings = AyuIslandsSettings.getInstance()
        val accentHex = settings.getAccentForVariant(variant)
        AccentApplicator.apply(accentHex)
        LOG.info("Ayu Islands accent applied: $accentHex for variant ${variant.name}")

        // Log detected third-party plugin conflicts
        val conflicts = ConflictRegistry.detectConflicts()
        if (conflicts.isNotEmpty()) {
            LOG.info("Ayu Islands detected third-party plugins: ${conflicts.joinToString { it.pluginDisplayName }}")
        }

        // Check license state
        checkLicenseState(project, variant, settings)

        // Initialize the glow overlay system if the glow is enabled
        // Uses ApplicationManager.invokeLater with project.disposed condition to skip
        // if the project closes before the EDT processes this (execute() runs on a background coroutine)
        if (settings.state.glowEnabled) {
            ApplicationManager.getApplication().invokeLater(
                { GlowOverlayManager.getInstance(project).initialize() },
                project.disposed,
            )
        }
    }

    private fun checkLicenseState(
        project: Project,
        variant: AyuVariant,
        settings: AyuIslandsSettings,
    ) {
        val licenseState = LicenseChecker.isLicensed()
        LOG.info("Ayu Islands license check: ${licenseStateLabel(licenseState)}")

        // Reset the notification flag if the license becomes valid again (user purchased)
        if (licenseState == true && settings.state.trialExpiredNotified) {
            settings.state.trialExpiredNotified = false
        }

        // null = facade not initialized (grace period, treat as licensed)
        // true = licensed or trial active
        // false = not licensed (trial expired or never purchased)
        if (licenseState == false) {
            LicenseChecker.revertToFreeDefaults(variant)
            LOG.info("Ayu Islands reverted to free defaults for ${variant.name}")

            // One-time balloon notification
            if (!settings.state.trialExpiredNotified) {
                LicenseChecker.notifyTrialExpired(project)
                settings.state.trialExpiredNotified = true
            }
        }
    }

    private fun licenseStateLabel(state: Boolean?): String =
        when (state) {
            true -> "licensed"
            false -> "not licensed"
            null -> "facade not initialized (grace period)"
        }

    companion object {
        private val LOG = logger<AyuIslandsStartupActivity>()
    }
}
