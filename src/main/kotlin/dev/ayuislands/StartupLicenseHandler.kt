package dev.ayuislands

import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.asContextElement
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.wm.IdeFocusManager
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
     * Schedule a wizard display. Every project running the startup activity schedules
     * its own coroutine. When the coroutines fire after the delay, only the project
     * whose frame is currently active will actually open the wizard — see
     * [scheduleFreeWizard] / [scheduleTrialWelcome] for the focus-aware claim logic.
     */
    fun handleWizardAction(
        action: WizardAction,
        project: Project,
        delayMs: Int,
        @Suppress("UNUSED_PARAMETER") settings: AyuIslandsSettings,
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
            openWizardIfThisProjectWins(project, OnboardingVirtualFile()) {
                settings.state.premiumOnboardingShown = true
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
            openWizardIfThisProjectWins(project, FreeOnboardingVirtualFile()) {
                settings.state.freeOnboardingShown = true
            }
        }
    }

    /**
     * Hop to EDT and open the wizard in the user's active project tab.
     *
     * In merged-window mode (project tabs), all projects share one OS frame.
     * [IdeFocusManager.getGlobalInstance] `.lastFocusedFrame.project` identifies
     * which project tab the user is currently viewing — even when the IDE itself is in
     * the background. Only the matching project opens the wizard; the rest bail out.
     * If no frame is focused (cold start, or IDE minimized), the first project to call
     * [OnboardingOrchestrator.tryPick] wins as a fallback.
     */
    private suspend fun openWizardIfThisProjectWins(
        project: Project,
        file: VirtualFile,
        onSuccess: () -> Unit,
    ) {
        withContext(Dispatchers.EDT + ModalityState.nonModal().asContextElement()) {
            if (project.isDisposed) return@withContext

            val activeProject = IdeFocusManager.getGlobalInstance().lastFocusedFrame?.project
            if (activeProject != null && activeProject != project) {
                LOG.info("Ayu onboarding: project ${project.name} is not the active tab — deferring")
                return@withContext
            }

            if (!OnboardingOrchestrator.tryPick()) {
                LOG.info("Ayu onboarding: another project already claimed the wizard slot")
                return@withContext
            }

            LOG.info("Ayu onboarding: opening wizard in ${project.name}")
            try {
                FileEditorManager.getInstance(project).openFile(file, true)
                onSuccess()
            } catch (exception: RuntimeException) {
                LOG.error("Ayu onboarding: failed to open wizard in ${project.name}", exception)
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
