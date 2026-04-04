package dev.ayuislands

import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import dev.ayuislands.accent.AyuVariant
import dev.ayuislands.commitpanel.CommitPanelAutoFitManager
import dev.ayuislands.editor.EditorScrollbarManager
import dev.ayuislands.gitpanel.GitPanelAutoFitManager
import dev.ayuislands.licensing.LicenseChecker
import dev.ayuislands.onboarding.OnboardingOrchestrator
import dev.ayuislands.onboarding.OnboardingVirtualFile
import dev.ayuislands.onboarding.WizardAction
import dev.ayuislands.projectview.ProjectViewScrollbarManager
import dev.ayuislands.settings.AyuIslandsSettings
import dev.ayuislands.settings.PanelWidthMode

/**
 * License-state dispatch logic, extracted from
 * [AyuIslandsStartupActivity] for testability.
 */
internal object StartupLicenseHandler {
    private val LOG = logger<StartupLicenseHandler>()

    // Calibrated on MacBook Pro M1 Pro (measured 17ms for 10K SHA-256 iterations)
    private const val BASELINE_MS = 17L
    private const val BASE_DELAY_MS = 15_000
    private const val MIN_DELAY_MS = 3_000
    private const val MAX_DELAY_MS = 45_000

    /**
     * Computes an adaptive onboarding delay based on CPU speed.
     * Faster machines get shorter delays, slower machines get longer.
     *
     * Must be called from a background thread (runs the benchmark).
     */
    fun computeAdaptiveDelay(): Int {
        val measuredMs = StartupBenchmark.measureCpuSpeed()
        val coefficient = measuredMs.toDouble() / BASELINE_MS
        val delay = (BASE_DELAY_MS * coefficient).toInt()
        return delay.coerceIn(MIN_DELAY_MS, MAX_DELAY_MS).also {
            LOG.info(
                "Ayu onboarding delay: ${it}ms " +
                    "(coefficient: ${"%.2f".format(coefficient)})",
            )
        }
    }

    fun applyLicensedDefaults(settings: AyuIslandsSettings) {
        if (settings.state.trialExpiredNotified) {
            settings.state.trialExpiredNotified = false
            settings.state.proDefaultsApplied = false
            // Reset premium onboarding so re-purchase shows premium wizard again
            settings.state.premiumOnboardingShown = false
        }

        if (!settings.state.proDefaultsApplied) {
            LicenseChecker.enableProDefaults()
            LOG.info(
                "Ayu Islands Pro defaults enabled " +
                    "(first-time license activation)",
            )
        }

        if (!settings.state.workspaceDefaultsApplied) {
            LicenseChecker.applyWorkspaceDefaults()
            LOG.info(
                "Ayu Islands workspace defaults " +
                    "migrated for existing user",
            )
        }
    }

    /** Migrate legacy `trialWelcomeShown` flag to the new `premiumOnboardingShown` flag. */
    fun runOnboardingMigration(settings: AyuIslandsSettings) {
        val state = settings.state
        if (state.trialWelcomeShown && !state.premiumOnboardingShown) {
            state.premiumOnboardingShown = true
            LOG.info("Ayu onboarding: migrated trialWelcomeShown -> premiumOnboardingShown")
        }
    }

    /** Delegate to [OnboardingOrchestrator.resolve], applying returning-user skip logic first. */
    fun resolveOnboarding(
        isLicensedOrGrace: Boolean,
        settings: AyuIslandsSettings,
        isReturningUser: Boolean,
    ): WizardAction {
        val state = settings.state
        // Returning users auto-skip free wizard (they already know the plugin)
        if (isReturningUser && !state.freeOnboardingShown) {
            state.freeOnboardingShown = true
            LOG.info("Ayu onboarding: returning user — skipping free wizard")
        }
        return OnboardingOrchestrator.resolve(
            isLicensedOrGrace = isLicensedOrGrace,
            freeOnboardingShown = state.freeOnboardingShown,
            premiumOnboardingShown = state.premiumOnboardingShown,
            isReturningUser = isReturningUser,
        )
    }

    /** Schedule wizard display based on orchestrator decision, guarded by [OnboardingOrchestrator.tryAcquire]. */
    fun handleWizardAction(
        action: WizardAction,
        project: Project,
        delayMs: Int,
        settings: AyuIslandsSettings,
    ) {
        if (action is WizardAction.NoWizard) return

        if (!OnboardingOrchestrator.tryAcquire()) {
            LOG.info("Ayu onboarding: wizard already showing in another window")
            return
        }

        when (action) {
            is WizardAction.ShowFreeWizard -> {
                LOG.info("Ayu onboarding: scheduling free wizard (delay: ${delayMs}ms)")
                // Phase 22 will implement the actual free wizard UI here
                settings.state.freeOnboardingShown = true
                OnboardingOrchestrator.release()
            }
            is WizardAction.ShowPremiumWizard -> {
                LOG.info("Ayu onboarding: scheduling premium wizard (delay: ${delayMs}ms)")
                settings.state.premiumOnboardingShown = true
                scheduleTrialWelcome(project, delayMs)
                // Release immediately since scheduleTrialWelcome handles its own lifecycle
                OnboardingOrchestrator.release()
            }
        }
    }

    internal fun scheduleTrialWelcome(
        project: Project,
        delayMs: Int,
    ) {
        javax.swing
            .Timer(delayMs) {
                if (!project.isDisposed) {
                    FileEditorManager
                        .getInstance(project)
                        .openFile(OnboardingVirtualFile(), true)
                }
            }.apply {
                isRepeats = false
                start()
            }
    }

    fun applyUnlicensedDefaults(
        project: Project,
        variant: AyuVariant,
        settings: AyuIslandsSettings,
    ) {
        LicenseChecker.revertToFreeDefaults(variant)
        LOG.info(
            "Ayu Islands reverted to free defaults " +
                "for ${variant.name}",
        )

        if (!settings.state.trialExpiredNotified) {
            LicenseChecker.notifyTrialExpired(project)
            settings.state.trialExpiredNotified = true
        }
    }

    fun initWorkspaceServices(
        project: Project,
        settings: AyuIslandsSettings,
    ) {
        if (project.isDisposed) {
            LOG.info("Skipping workspace services — project disposed")
            return
        }
        val state = settings.state
        val hasProjectViewCustomizations =
            state.hideProjectViewHScrollbar ||
                state.hideProjectRootPath
        if (hasProjectViewCustomizations ||
            PanelWidthMode.fromString(
                state.projectPanelWidthMode,
            ) != PanelWidthMode.DEFAULT
        ) {
            ProjectViewScrollbarManager.getInstance(project)
        }

        if (state.hideEditorVScrollbar || state.hideEditorHScrollbar) {
            EditorScrollbarManager.getInstance(project)
        }

        if (PanelWidthMode.fromString(
                state.commitPanelWidthMode,
            ) != PanelWidthMode.DEFAULT
        ) {
            CommitPanelAutoFitManager.getInstance(project)
        }

        if (PanelWidthMode.fromString(
                state.gitPanelWidthMode,
            ) != PanelWidthMode.DEFAULT
        ) {
            GitPanelAutoFitManager.getInstance(project)
        }
    }
}
