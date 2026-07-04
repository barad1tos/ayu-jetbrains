package dev.ayuislands

import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import dev.ayuislands.accent.AyuVariant
import dev.ayuislands.commitpanel.CommitPanelAutoFitManager
import dev.ayuislands.editor.EditorScrollbarManager
import dev.ayuislands.gitpanel.GitPanelAutoFitManager
import dev.ayuislands.licensing.LicenseChecker
import dev.ayuislands.onboarding.FreeOnboardingVirtualFile
import dev.ayuislands.onboarding.OnboardingOrchestrator
import dev.ayuislands.onboarding.OnboardingSchedulerService
import dev.ayuislands.onboarding.OnboardingVirtualFile
import dev.ayuislands.onboarding.WizardAction
import dev.ayuislands.projectview.ProjectViewScrollbarManager
import dev.ayuislands.settings.AyuIslandsSettings
import dev.ayuislands.settings.AyuIslandsState
import dev.ayuislands.settings.PanelWidthMode
import dev.ayuislands.ui.FocusWinningTabOpener
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds

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

    /**
     * Focus-race protocol shared with the What's New launcher: EDT hop,
     * disposed guard, focus check, [OnboardingOrchestrator.gate] claim, and
     * release-on-failure so one failed `openFile` can't permanently block the
     * wizard for the whole JVM session.
     */
    private val wizardOpener =
        FocusWinningTabOpener(
            gate = OnboardingOrchestrator.gate,
            log = LOG,
            logPrefix = "Ayu onboarding",
            subject = "wizard",
        )

    /**
     * Schedule a wizard display. Every project running the startup activity schedules
     * its own coroutine. When the coroutines fire after the delay, only the project
     * whose frame is currently active will actually open the wizard — the focus-aware
     * claim logic lives in [FocusWinningTabOpener].
     */
    fun handleWizardAction(
        action: WizardAction,
        project: Project,
        delayMs: Int,
    ) {
        when (action) {
            is WizardAction.NoWizard -> return
            is WizardAction.ShowFreeWizard -> scheduleFreeWizard(project, delayMs)
            is WizardAction.ShowPremiumWizard -> {
                LOG.info("Ayu onboarding: scheduling premium wizard (delay: ${delayMs}ms)")
                scheduleTrialWelcome(project, delayMs)
            }
        }
    }

    internal fun scheduleTrialWelcome(
        project: Project,
        delayMs: Int,
    ) {
        val settings = AyuIslandsSettings.getInstance()
        val scope = OnboardingSchedulerService.getInstance(project).scope()
        scope.launch {
            delay(delayMs.milliseconds)
            if (project.isDisposed) return@launch
            wizardOpener.open(
                project = project,
                onSuccess = { settings.state.premiumOnboardingShown = true },
            ) { target ->
                FileEditorManager.getInstance(target).openFile(OnboardingVirtualFile(), true)
            }
        }
    }

    /**
     * Opens the free onboarding wizard tab after [delayMs].
     * Sets [AyuIslandsState.freeOnboardingShown] only after `openFile` succeeds inside
     * the coroutine body, so a project that loses the focus-aware race does not
     * incorrectly mark the wizard as shown.
     */
    internal fun scheduleFreeWizard(
        project: Project,
        delayMs: Int,
    ) {
        val settings = AyuIslandsSettings.getInstance()
        LOG.info("Ayu onboarding: scheduling free wizard (delay: ${delayMs}ms)")
        val scope = OnboardingSchedulerService.getInstance(project).scope()
        scope.launch {
            delay(delayMs.milliseconds)
            if (project.isDisposed) return@launch
            wizardOpener.open(
                project = project,
                onSuccess = { settings.state.freeOnboardingShown = true },
            ) { target ->
                FileEditorManager.getInstance(target).openFile(FreeOnboardingVirtualFile(), true)
            }
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
