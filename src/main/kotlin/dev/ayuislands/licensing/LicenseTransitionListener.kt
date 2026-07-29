package dev.ayuislands.licensing

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.ui.LicensingFacade
import dev.ayuislands.settings.AyuIslandsSettings

/**
 * Listens for definitive license-state transitions.
 *
 * On unlicensed → licensed transitions it resets
 *     [dev.ayuislands.settings.AyuIslandsState.premiumOnboardingShown] to `false` so
 *     the next IDE startup surfaces the premium wizard via OnboardingOrchestrator.
 *     No mid-session UI is shown — the re-armed wizard appears at next launch only.
 *
 * Runtime surfaces are delegated to [EntitlementReconciler]. A first definitive callback
 * reconciles when it differs from the last persisted confirmation; a fresh initial licensed
 * callback still records state without repainting. UNKNOWN callbacks preserve the remembered
 * state and schedule another short observation.
 *
 * State mutation and runtime reconciliation are dispatched to EDT because Topic
 * listeners may fire on arbitrary threads.
 */
internal class LicenseTransitionListener(
    private val entitlementProvider: () -> LicenseEntitlement = LicenseChecker::currentEntitlement,
    private val reconcile: (LicenseEntitlement, Iterable<Project>) -> ReconciliationResult =
        EntitlementReconciler::reconcile,
    private val projectsProvider: () -> List<Project> = {
        ProjectManager.getInstance().openProjects.toList()
    },
    private val dispatch: (() -> Unit) -> Unit = { ApplicationManager.getApplication().invokeLater(it) },
    private val recheckDelayProvider: () -> Long? = LicenseChecker::nextRecheckDelayMs,
    private val confirmedEntitlementProvider: () -> LicenseEntitlement = {
        LicenseEntitlement.fromName(AyuIslandsSettings.getInstance().state.lastConfirmedEntitlement)
    },
    private val scheduleRecheck: (Long, () -> Unit) -> Unit = { delayMs, action ->
        LicenseRecheckScheduler.getInstance().schedule(LicenseRecheckSlot.TRANSITION, delayMs, action)
    },
) : LicensingFacade.LicenseStateListener {
    private var previousEntitlement: LicenseEntitlement? = null

    override fun licenseStateChanged(facade: LicensingFacade?) {
        dispatch(::processChange)
    }

    private fun processChange() {
        try {
            val previous =
                previousEntitlement
                    ?: confirmedEntitlementProvider().takeUnless { it == LicenseEntitlement.UNKNOWN }
            val entitlement = entitlementProvider()
            if (entitlement == LicenseEntitlement.UNKNOWN) {
                scheduleRetry()
                return
            }

            if (requiresReconciliation(previous, entitlement)) {
                val result = reconcile(entitlement, projectsProvider())
                if (!result.isSuccess) {
                    scheduleRetry()
                    return
                }
                rearmPremiumOnboarding(previous, entitlement)
            }
            previousEntitlement = entitlement
            scheduleNextCheck(entitlement)
        } catch (exception: RuntimeException) {
            LOG.error("Ayu license: failed to handle license state change", exception)
            scheduleRetry()
        }
    }

    private fun scheduleRetry() {
        scheduleRecheck(RECONCILIATION_RETRY_MS) { licenseStateChanged(null) }
    }

    private fun scheduleNextCheck(entitlement: LicenseEntitlement) {
        if (entitlement != LicenseEntitlement.LICENSED) return
        val delayMs = recheckDelayProvider() ?: return
        scheduleRecheck(delayMs) { licenseStateChanged(null) }
    }

    private fun rearmPremiumOnboarding(
        previous: LicenseEntitlement?,
        entitlement: LicenseEntitlement,
    ) {
        if (previous != LicenseEntitlement.UNLICENSED || entitlement != LicenseEntitlement.LICENSED) return
        val state = AyuIslandsSettings.getInstance().state
        if (!state.premiumOnboardingShown) return
        state.premiumOnboardingShown = false
        LOG.info("Ayu license: unlicensed->licensed transition; premium wizard re-armed")
    }

    private fun requiresReconciliation(
        previous: LicenseEntitlement?,
        entitlement: LicenseEntitlement,
    ): Boolean = previous?.let { it != entitlement } ?: (entitlement == LicenseEntitlement.UNLICENSED)

    private companion object {
        private const val RECONCILIATION_RETRY_MS = 5_000L
        private val LOG = logger<LicenseTransitionListener>()
    }
}
