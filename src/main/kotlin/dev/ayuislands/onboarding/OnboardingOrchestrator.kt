package dev.ayuislands.onboarding

import dev.ayuislands.ui.SessionOneShot

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
 * The [gate] ensures at most one project window actually opens the wizard in a JVM session,
 * even when several project windows race to claim it after their individual coroutine
 * delays elapse. Unlike a simple acquire-on-schedule lock, the pick happens *after* the
 * delay so the currently-focused project window wins, not whichever project happened to
 * start first.
 */
internal object OnboardingOrchestrator {
    /** In-session one-shot claim for the onboarding wizard — see [SessionOneShot]. */
    val gate: SessionOneShot = SessionOneShot()

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
