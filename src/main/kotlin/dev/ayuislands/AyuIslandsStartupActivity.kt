package dev.ayuislands

import com.intellij.ide.ui.LafManager
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import dev.ayuislands.accent.AccentApplicator
import dev.ayuislands.accent.AyuVariant
import dev.ayuislands.accent.conflict.ConflictRegistry
import dev.ayuislands.commitpanel.CommitPanelAutoFitManager
import dev.ayuislands.font.FontPreset
import dev.ayuislands.font.FontPresetApplicator
import dev.ayuislands.gitpanel.GitPanelAutoFitManager
import dev.ayuislands.glow.GlowOverlayManager
import dev.ayuislands.licensing.LicenseChecker
import dev.ayuislands.projectview.ProjectViewScrollbarManager
import dev.ayuislands.settings.AyuIslandsSettings
import dev.ayuislands.settings.PanelWidthMode
import javax.swing.SwingUtilities

internal class AyuIslandsStartupActivity : ProjectActivity {
    @Suppress("UnstableApiUsage")
    override suspend fun execute(project: Project) {
        val themeName = LafManager.getInstance().currentUIThemeLookAndFeel.name
        LOG.info("Ayu Islands loaded — active theme: $themeName, project: ${project.name}")

        val variant = AyuVariant.fromThemeName(themeName) ?: return
        val settings = AyuIslandsSettings.getInstance()

        // Belt-and-suspenders: accent is pre-applied in appFrameCreated() (no gold flash),
        // but project-dependent features (BracketFadeManager, editor TextAttributesKey overrides)
        // need this second idempotent call once a project context exists.
        val accentHex = settings.getAccentForVariant(variant)
        AccentApplicator.apply(accentHex)

        // Apply persisted font preset (FontPresetApplicator ensures EDT internally)
        // Migrate legacy preset names (GLOW_WRITER→WHISPER, CLEAN→AMBIENT, etc.)
        val fontPreset = FontPreset.fromName(settings.state.fontPresetName)
        if (fontPreset.name != settings.state.fontPresetName) {
            settings.state.fontPresetName = fontPreset.name
        }
        FontPreset.migrateCustomizations(settings.state.fontPresetCustomizations)

        FontPresetApplicator.applyFromState()

        // Log detected third-party plugin conflicts
        val conflicts = ConflictRegistry.detectConflicts()
        if (conflicts.isNotEmpty()) {
            LOG.info("Ayu Islands detected third-party plugins: ${conflicts.joinToString { it.pluginDisplayName }}")
        }

        // Check license state and initialize workspace services (inside EDT callback)
        checkLicenseState(project, variant, settings)

        // Auto-switch theme to match macOS Light/Dark mode
        if (settings.state.followSystemAppearance) {
            AppearanceSyncService.getInstance().syncIfNeeded()
        }

        // Show a one-time update notification if the plugin version changed
        SwingUtilities.invokeLater { UpdateNotifier.showIfUpdated(project) }

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

        SwingUtilities.invokeLater {
            if (licenseState != false) {
                applyLicensedDefaults(project, settings)
            } else {
                applyUnlicensedDefaults(project, variant, settings)
            }

            // Migrate width modes before service init reads them
            settings.state.migrateWidthModes()

            // Initialize workspace services after defaults are applied (no race)
            initWorkspaceServices(project, settings)
        }
    }

    private fun applyLicensedDefaults(
        project: Project,
        settings: AyuIslandsSettings,
    ) {
        if (settings.state.trialExpiredNotified) {
            settings.state.trialExpiredNotified = false
            settings.state.proDefaultsApplied = false
            settings.state.trialWelcomeShown = false
        }

        if (!settings.state.proDefaultsApplied) {
            LicenseChecker.enableProDefaults()
            LOG.info("Ayu Islands Pro defaults enabled (first-time license activation)")

            if (!settings.state.trialWelcomeShown) {
                LicenseChecker.notifyTrialWelcome(project)
                settings.state.trialWelcomeShown = true
            }
        }

        // Migration: workspace defaults for existing users upgrading to 2.3.0
        if (!settings.state.workspaceDefaultsApplied) {
            LicenseChecker.applyWorkspaceDefaults()
            LOG.info("Ayu Islands workspace defaults migrated for existing user")
        }
    }

    private fun applyUnlicensedDefaults(
        project: Project,
        variant: AyuVariant,
        settings: AyuIslandsSettings,
    ) {
        LicenseChecker.revertToFreeDefaults(variant)
        LOG.info("Ayu Islands reverted to free defaults for ${variant.name}")

        if (!settings.state.trialExpiredNotified) {
            LicenseChecker.notifyTrialExpired(project)
            settings.state.trialExpiredNotified = true
        }
    }

    private fun initWorkspaceServices(
        project: Project,
        settings: AyuIslandsSettings,
    ) {
        if (project.isDisposed) return
        val pvState = settings.state
        val hasProjectViewCustomizations =
            pvState.hideProjectViewHScrollbar ||
                pvState.hideProjectRootPath
        if (hasProjectViewCustomizations ||
            PanelWidthMode.fromString(pvState.projectPanelWidthMode) != PanelWidthMode.DEFAULT
        ) {
            ProjectViewScrollbarManager.getInstance(project)
        }

        if (PanelWidthMode.fromString(pvState.commitPanelWidthMode) != PanelWidthMode.DEFAULT) {
            CommitPanelAutoFitManager.getInstance(project)
        }

        if (PanelWidthMode.fromString(pvState.gitPanelWidthMode) != PanelWidthMode.DEFAULT) {
            GitPanelAutoFitManager.getInstance(project)
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
