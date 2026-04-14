package dev.ayuislands

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.wm.WindowManager
import com.intellij.util.IJSwingUtilities
import dev.ayuislands.accent.AccentApplicator
import dev.ayuislands.accent.AccentResolver
import dev.ayuislands.accent.AyuVariant
import dev.ayuislands.accent.conflict.ConflictRegistry
import dev.ayuislands.font.FontPreset
import dev.ayuislands.font.FontPresetApplicator
import dev.ayuislands.glow.GlowOverlayManager
import dev.ayuislands.licensing.LicenseChecker
import dev.ayuislands.rotation.AccentRotationService
import dev.ayuislands.settings.AyuIslandsSettings
import dev.ayuislands.settings.mappings.ProjectAccentSwapService
import javax.swing.SwingUtilities

internal class AyuIslandsStartupActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        val themeName = AyuVariant.currentThemeName()
        LOG.info("Ayu Islands loaded — active theme: $themeName, project: ${project.name}")

        val variant = AyuVariant.fromThemeName(themeName) ?: return
        val settings = AyuIslandsSettings.getInstance()

        // Belt-and-suspenders: accent is pre-applied in appFrameCreated() (no gold flash),
        // but project-dependent features (BracketFadeManager, editor TextAttributesKey overrides)
        // need this second idempotent call once a project context exists.
        // Project/language overrides win over the global accent; AccentResolver centralizes the chain.
        val accentHex = AccentResolver.resolve(project, variant)
        AccentApplicator.apply(accentHex)
        // Install the focus-swap listener once per IDE lifetime; subsequent calls are no-ops.
        // Also sync the cache so the listener doesn't skip the first real WINDOW_ACTIVATED event.
        val swapService = ProjectAccentSwapService.getInstance()
        swapService.install()
        swapService.notifyExternalApply(accentHex)

        // Force a component-tree LAF refresh on the project frame so already-rendered toolbar,
        // tab underlines, scrollbar chrome, and focus rings pick up the resolved accent.
        // AccentApplicator only updates UIManager + editor scheme; cached JBColor instances on
        // already-painted components otherwise keep the global-accent values they captured when
        // the frame first rendered.
        SwingUtilities.invokeLater {
            if (project.isDisposed) return@invokeLater
            val frame = WindowManager.getInstance().getFrame(project) ?: return@invokeLater
            try {
                IJSwingUtilities.updateComponentTreeUI(frame)
            } catch (exception: RuntimeException) {
                LOG.warn("Startup UI refresh failed for ${project.name}: ${exception.message}")
            }
        }

        // Apply persisted font preset (FontPresetApplicator ensures EDT internally)
        // Migrate legacy preset names (GLOW_WRITER→WHISPER, CLEAN→AMBIENT, etc.)
        val fontPreset = FontPreset.fromName(settings.state.fontPresetName)
        if (fontPreset.name != settings.state.fontPresetName) {
            settings.state.fontPresetName = fontPreset.name
        }
        FontPreset.migrateCustomizations(settings.state.fontPresetCustomizations)

        // Seed installedFonts from the JVM font registry on first run so returning
        // users who pre-installed via the Settings panel aren't re-prompted by the wizard.
        settings.seedInstalledFontsFromDiskIfNeeded()

        FontPresetApplicator.applyFromState()

        // Log detected third-party plugin conflicts
        val conflicts = ConflictRegistry.detectConflicts()
        if (conflicts.isNotEmpty()) {
            LOG.info("Ayu Islands detected third-party plugins: ${conflicts.joinToString { it.pluginDisplayName }}")
        }

        // Snapshot BEFORE UpdateNotifier can mutate it (prevents fresh-install false positive)
        val isReturningUser = settings.state.lastSeenVersion != null

        // Check license state and initialize workspace services (inside EDT callback)
        checkLicenseState(project, variant, settings, isReturningUser)

        // Auto-switch theme to match macOS Light/Dark mode
        if (settings.state.followSystemAppearance) {
            AppearanceSyncService.getInstance().syncIfNeeded()
        }

        // Start accent rotation if enabled (premium feature)
        try {
            if (settings.state.accentRotationEnabled && LicenseChecker.isLicensedOrGrace()) {
                val rotationService = AccentRotationService.getInstance()
                val lastSwitch = settings.state.accentRotationLastSwitchMs
                val intervalMs = settings.state.accentRotationIntervalHours * MS_PER_HOUR
                val elapsed = System.currentTimeMillis() - lastSwitch
                if (lastSwitch == 0L || elapsed >= intervalMs) {
                    rotationService.rotateNow()
                } else {
                    val remainingMs = intervalMs - elapsed
                    rotationService.startRotationWithDelay(remainingMs)
                }
            }
        } catch (exception: RuntimeException) {
            LOG.error("Accent rotation startup failed", exception)
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
        isReturningUser: Boolean,
    ) {
        val isLicensed = LicenseChecker.isLicensedOrGrace()
        LOG.info("Ayu Islands license check: ${if (isLicensed) "licensed" else "not licensed"}")

        val trialDays = LicenseChecker.getTrialDaysRemaining()
        if (trialDays != null) {
            LOG.info("Ayu Islands trial: $trialDays days remaining")
        }

        // Compute adaptive delay on background thread (execute() coroutine)
        val adaptiveDelayMs = StartupLicenseHandler.computeAdaptiveDelay()

        SwingUtilities.invokeLater {
            if (project.isDisposed) return@invokeLater
            try {
                // Run migration and orchestrator before license defaults
                StartupLicenseHandler.runOnboardingMigration(settings)
                val wizardAction =
                    StartupLicenseHandler.resolveOnboarding(
                        isLicensed,
                        settings,
                        isReturningUser,
                    )

                if (isLicensed) {
                    StartupLicenseHandler.applyLicensedDefaults(settings)
                } else {
                    StartupLicenseHandler.applyUnlicensedDefaults(project, variant, settings)
                }

                settings.state.migrateWidthModes()
                StartupLicenseHandler.initWorkspaceServices(project, settings)

                // Schedule wizard based on orchestrator decision
                StartupLicenseHandler.handleWizardAction(wizardAction, project, adaptiveDelayMs, settings)

                // Check trial expiry warning (only runs for trial users)
                if (isLicensed) {
                    LicenseChecker.checkTrialExpiryWarning(project)
                }
            } catch (e: RuntimeException) {
                LOG.error("License defaults failed", e)
            }
        }
    }

    companion object {
        private val LOG = logger<AyuIslandsStartupActivity>()
        private const val MS_PER_HOUR = 3_600_000L
    }
}
