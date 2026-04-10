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
 * The [AtomicBoolean] guard ensures at most one project window actually opens the wizard
 * in a JVM session, even when several project windows race to claim it after their
 * individual coroutine delays elapse. Unlike a simple acquire-on-schedule lock, the
 * pick happens *after* the delay so the currently-focused project window wins, not
 * whichever project happened to start first.
 */
internal object OnboardingOrchestrator {
    private val wizardShown = AtomicBoolean(false)

    /**
     * Atomically claims the right to open the wizard. Returns `true` only to the
     * first caller; subsequent callers get `false` and must bail without showing.
     * One-shot for the lifetime of the JVM session.
     */
    fun tryPick(): Boolean = wizardShown.compareAndSet(false, true)

    /** Test-only hook: reset the one-shot pick flag between test cases. */
    @org.jetbrains.annotations.TestOnly
    fun resetForTesting() {
        wizardShown.set(false)
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
