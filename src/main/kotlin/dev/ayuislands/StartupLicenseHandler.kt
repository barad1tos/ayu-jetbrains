package dev.ayuislands

import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import dev.ayuislands.accent.AyuVariant
import dev.ayuislands.accent.rethrowIfCancelled
import dev.ayuislands.licensing.LicenseChecker
import dev.ayuislands.licensing.LicenseEntitlement
import dev.ayuislands.licensing.LicenseRecheckScheduler
import dev.ayuislands.licensing.LicenseRecheckSlot
import dev.ayuislands.onboarding.FreeOnboardingVirtualFile
import dev.ayuislands.onboarding.OnboardingOrchestrator
import dev.ayuislands.onboarding.OnboardingSchedulerService
import dev.ayuislands.onboarding.OnboardingVirtualFile
import dev.ayuislands.onboarding.WizardAction
import dev.ayuislands.studio.StudioApplicationGraph
import dev.ayuislands.studio.StudioService
import dev.ayuislands.studio.model.AcceptanceResult
import dev.ayuislands.studio.model.FieldAddress
import dev.ayuislands.studio.model.FieldValue
import dev.ayuislands.studio.model.LegacyFieldKey
import dev.ayuislands.studio.model.LegacyStore
import dev.ayuislands.studio.model.RawValue
import dev.ayuislands.studio.model.StudioMetadataCommand
import dev.ayuislands.studio.model.StudioTarget
import dev.ayuislands.studio.persistence.AyuSettingsView
import dev.ayuislands.studio.persistence.StudioLegacyProjection
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

    /** Startup observes durable licensing before scheduling any onboarding or expiry notice. */
    fun start(
        project: Project,
        graph: StudioApplicationGraph,
        isReturningUser: Boolean,
        delayMs: Int,
    ) {
        graph.scope.launch {
            try {
                val nextCheck = graph.refreshEntitlement()
                if (nextCheck == LicenseRecheckScheduler.RETRY_DELAY_MS) {
                    scheduleRetry(graph, isReturningUser, delayMs, nextCheck)
                    return@launch
                }
                val target =
                    project.takeUnless { it.isDisposed }
                        ?: graph.projects.openProjects().firstOrNull() ?: return@launch
                if (!complete(target, graph, isReturningUser, delayMs)) {
                    scheduleRetry(graph, isReturningUser, delayMs, LicenseRecheckScheduler.RETRY_DELAY_MS)
                } else if (nextCheck != null) {
                    scheduleRetry(graph, isReturningUser, delayMs, nextCheck)
                }
            } catch (failure: RuntimeException) {
                failure.rethrowIfCancelled()
                LOG.warn("Startup licensing could not settle; retrying", failure)
                scheduleRetry(graph, isReturningUser, delayMs, LicenseRecheckScheduler.RETRY_DELAY_MS)
            }
        }
    }

    private fun scheduleRetry(
        graph: StudioApplicationGraph,
        returning: Boolean,
        delayMs: Int,
        retryMs: Long,
    ) {
        LicenseRecheckScheduler.getInstance().schedule(LicenseRecheckSlot.STARTUP, retryMs) {
            val project = graph.projects.openProjects().firstOrNull() ?: return@schedule
            start(project, graph, returning, delayMs)
        }
    }

    private suspend fun complete(
        project: Project,
        graph: StudioApplicationGraph,
        isReturningUser: Boolean,
        delayMs: Int,
    ): Boolean {
        var state = settings(graph) ?: return false
        val entitlement = LicenseEntitlement.fromName(state.lastConfirmedEntitlement)
        if (entitlement == LicenseEntitlement.UNKNOWN) return false
        if (isReturningUser && !state.freeOnboardingShown) {
            if (!recordUi(graph, "freeOnboardingShown")) return false
            state = settings(graph) ?: return false
        }
        if (entitlement == LicenseEntitlement.UNLICENSED && !state.trialExpiredNotified) {
            if (!recordUi(graph, "trialExpiredNotified")) return false
            LicenseChecker.notifyTrialExpired(project)
        }
        if (AyuVariant.isAyuActive()) {
            handleWizardAction(
                resolveOnboarding(entitlement == LicenseEntitlement.LICENSED, state, isReturningUser),
                project,
                delayMs,
            )
        }
        if (entitlement == LicenseEntitlement.LICENSED) {
            LicenseChecker.checkTrialExpiryWarning(project, state) { recordUi(graph, it) }
        }
        return true
    }

    private fun settings(graph: StudioApplicationGraph): AyuSettingsView? =
        graph.sessions?.document?.let { StudioLegacyProjection.project(it, StudioTarget.Global)?.settings }

    private suspend fun recordUi(
        graph: StudioApplicationGraph,
        name: String,
    ): Boolean {
        val result =
            graph.submitMetadata(
                StudioMetadataCommand.UiChoice(
                    FieldAddress(LegacyFieldKey(LegacyStore.SETTINGS, name)),
                    FieldValue.Present(RawValue.BooleanValue(true)),
                ),
            )
        return result is AcceptanceResult.Accepted || result == AcceptanceResult.NoChanges
    }

    internal fun resolveOnboarding(
        licensed: Boolean,
        state: AyuSettingsView,
        isReturningUser: Boolean,
    ): WizardAction =
        OnboardingOrchestrator.resolve(
            licensed,
            state.freeOnboardingShown,
            state.premiumOnboardingShown,
            isReturningUser,
        )

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
        val scope = OnboardingSchedulerService.getInstance(project).scope()
        scope.launch {
            delay(delayMs.milliseconds)
            if (project.isDisposed) return@launch
            wizardOpener.open(
                project = project,
                onSuccess = {
                    StudioService.getInstance().updateUi(
                        "premiumOnboardingShown",
                        RawValue.BooleanValue(true),
                    )
                },
            ) { target ->
                FileEditorManager.getInstance(target).openFile(OnboardingVirtualFile(), true)
            }
        }
    }

    /**
     * Opens the free onboarding wizard tab after [delayMs].
     * Records the free wizard marker only after `openFile` succeeds inside
     * the coroutine body, so a project that loses the focus-aware race does not
     * incorrectly mark the wizard as shown.
     */
    internal fun scheduleFreeWizard(
        project: Project,
        delayMs: Int,
    ) {
        LOG.info("Ayu onboarding: scheduling free wizard (delay: ${delayMs}ms)")
        val scope = OnboardingSchedulerService.getInstance(project).scope()
        scope.launch {
            delay(delayMs.milliseconds)
            if (project.isDisposed) return@launch
            wizardOpener.open(
                project = project,
                onSuccess = {
                    StudioService.getInstance().updateUi(
                        "freeOnboardingShown",
                        RawValue.BooleanValue(true),
                    )
                },
            ) { target ->
                FileEditorManager.getInstance(target).openFile(FreeOnboardingVirtualFile(), true)
            }
        }
    }
}
