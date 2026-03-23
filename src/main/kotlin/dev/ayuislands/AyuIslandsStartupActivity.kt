package dev.ayuislands

import com.intellij.ide.ui.LafManager
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import dev.ayuislands.accent.AccentApplicator
import dev.ayuislands.accent.AyuVariant
import dev.ayuislands.accent.conflict.ConflictRegistry
import dev.ayuislands.font.FontPreset
import dev.ayuislands.font.FontPresetApplicator
import dev.ayuislands.glow.GlowOverlayManager
import dev.ayuislands.licensing.LicenseChecker
import dev.ayuislands.settings.AyuIslandsSettings
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
            if (project.isDisposed) return@invokeLater
            try {
                if (licenseState != false) {
                    StartupLicenseHandler.applyLicensedDefaults(project, settings)
                } else {
                    StartupLicenseHandler.applyUnlicensedDefaults(project, variant, settings)
                }

                settings.state.migrateWidthModes()
                StartupLicenseHandler.initWorkspaceServices(project, settings)
            } catch (e: RuntimeException) {
                LOG.error("License defaults failed", e)
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
