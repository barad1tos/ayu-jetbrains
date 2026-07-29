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
 * Runtime surfaces are delegated to [EntitlementReconciler]. An initial licensed
 * callback records state without repainting; an initial unlicensed callback reconciles
 * once because startup may have rendered optimistically while licensing was unavailable.
 * UNKNOWN callbacks do not update the remembered state and cannot manufacture a later
 * transition.
 *
 * State mutation and runtime reconciliation are dispatched to EDT because Topic
 * listeners may fire on arbitrary threads.
 */
internal class LicenseTransitionListener(
    private val entitlementProvider: () -> LicenseEntitlement = LicenseChecker::currentEntitlement,
    private val reconcile: (LicenseEntitlement, Iterable<Project>) -> ReconciliationResult =
        EntitlementReconciler::reconcile,
    private val dispatch: (() -> Unit) -> Unit = { ApplicationManager.getApplication().invokeLater(it) },
    private val recheckDelayProvider: () -> Long? = LicenseChecker::nextRecheckDelayMs,
    private val scheduleRecheck: (Long, () -> Unit) -> Unit = { delayMs, action ->
        LicenseRecheckScheduler.getInstance().schedule(delayMs, action)
    },
) : LicensingFacade.LicenseStateListener {
    private var previousEntitlement: LicenseEntitlement? = null

    override fun licenseStateChanged(facade: LicensingFacade?) {
        dispatch(::processChange)
    }

    private fun processChange() {
        try {
            val entitlement = entitlementProvider()
            if (entitlement == LicenseEntitlement.UNKNOWN) return

            val previous = previousEntitlement
            if (requiresReconciliation(previous, entitlement)) {
                val result = reconcile(entitlement, openProjects())
                if (!result.isSuccess) {
                    scheduleNextCheck(entitlement)
                    return
                }
                rearmPremiumOnboarding(previous, entitlement)
            }
            previousEntitlement = entitlement
            scheduleNextCheck(entitlement)
        } catch (exception: RuntimeException) {
            LOG.error("Ayu license: failed to handle license state change", exception)
        }
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

    private fun openProjects(): List<Project> =
        try {
            ProjectManager.getInstance().openProjects.toList()
        } catch (exception: RuntimeException) {
            LOG.warn("Ayu license: failed to enumerate projects for reconciliation", exception)
            emptyList()
        }

    private companion object {
        private val LOG = logger<LicenseTransitionListener>()
    }
}
