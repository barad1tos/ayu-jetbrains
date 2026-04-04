package dev.ayuislands.onboarding

import java.util.concurrent.atomic.AtomicBoolean

/** Decision result from the onboarding state machine. */
sealed class WizardAction {
    data object ShowFreeWizard : WizardAction()

    data object ShowPremiumWizard : WizardAction()

    data object NoWizard : WizardAction()
}

/**
 * Pure-logic state machine that resolves which onboarding wizard to display.
 *
 * No platform dependencies — all inputs are plain booleans, making this trivially testable.
 * The [AtomicBoolean] guard prevents concurrent wizard display across multiple project windows.
 */
internal object OnboardingOrchestrator {
    private val wizardShowing = AtomicBoolean(false)

    /** Atomically acquires the wizard display lock. Returns `true` if acquired, `false` if already held. */
    fun tryAcquire(): Boolean = wizardShowing.compareAndSet(false, true)

    /** Releases the wizard display lock so another project window can show a wizard. */
    fun release() {
        wizardShowing.set(false)
    }

    /**
     * Resolves which wizard action to take based on license and onboarding state.
     *
     * Returning users (those with [isReturningUser] = true, i.e. `lastSeenVersion != null`)
     * skip the free wizard automatically — they already know the plugin.
     */
    fun resolve(
        isLicensedOrGrace: Boolean,
        freeOnboardingShown: Boolean,
        premiumOnboardingShown: Boolean,
        isReturningUser: Boolean,
    ): WizardAction {
        val effectiveFreeShown = freeOnboardingShown || isReturningUser
        return when {
            !effectiveFreeShown -> WizardAction.ShowFreeWizard
            isLicensedOrGrace && !premiumOnboardingShown -> WizardAction.ShowPremiumWizard
            else -> WizardAction.NoWizard
        }
    }
}
