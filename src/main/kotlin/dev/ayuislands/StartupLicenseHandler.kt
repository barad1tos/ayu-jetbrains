package dev.ayuislands

import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.asContextElement
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
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
                scheduleFreeWizard(project, delayMs)
                // guard released inside scheduleFreeWizard coroutine finally block
            }
            is WizardAction.ShowPremiumWizard -> {
                LOG.info("Ayu onboarding: scheduling premium wizard (delay: ${delayMs}ms)")
                settings.state.premiumOnboardingShown = true
                scheduleTrialWelcome(project, delayMs)
                // one-shot per IDE session: never released after acquire
            }
        }
    }

    internal fun scheduleTrialWelcome(
        project: Project,
        delayMs: Int,
    ) {
        val scope = OnboardingSchedulerService.getInstance(project).scope()
        scope.launch {
            delay(delayMs.toLong())
            if (project.isDisposed) return@launch
            openWizardTab(project, OnboardingVirtualFile())
        }
    }

    /**
     * Opens the free onboarding wizard tab after [delayMs].
     * Sets [AyuIslandsState.freeOnboardingShown] after openFile succeeds inside
     * the coroutine body; releases the orchestrator guard in the finally block.
     */
    internal fun scheduleFreeWizard(
        project: Project,
        delayMs: Int,
    ) {
        val settings = AyuIslandsSettings.getInstance()
        LOG.info("Ayu onboarding: scheduling free wizard (delay: ${delayMs}ms)")
        val scope = OnboardingSchedulerService.getInstance(project).scope()
        scope.launch {
            try {
                delay(delayMs.toLong())
                if (project.isDisposed) return@launch
                openWizardTab(project, FreeOnboardingVirtualFile())
                settings.state.freeOnboardingShown = true
            } finally {
                OnboardingOrchestrator.release()
            }
        }
    }

    /**
     * Opens a wizard tab from a coroutine via [Dispatchers.EDT].
     *
     * Uses the stable [FileEditorManager.openFile] interface call rather than
     * `FileEditorManagerEx` + `FileEditorOpenOptions`, because the latter has
     * a binary-incompatible constructor signature between IntelliJ Platform
     * 2025.1 (build target) and 2026.1 (runtime). The coroutine refactor still
     * eliminates the prior `javax.swing.Timer`-on-EDT chain that caused the
     * 15-second freeze, since the delay no longer occupies EDT and the EDT hop
     * happens cooperatively through the coroutine dispatcher.
     */
    private suspend fun openWizardTab(
        project: Project,
        file: VirtualFile,
    ) {
        withContext(Dispatchers.EDT + ModalityState.nonModal().asContextElement()) {
            if (project.isDisposed) return@withContext
            FileEditorManager.getInstance(project).openFile(file, true)
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
