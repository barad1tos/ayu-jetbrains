package dev.ayuislands.onboarding

import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OnboardingOrchestratorTest {
    @BeforeTest
    fun setUp() {
        OnboardingOrchestrator.resetForTesting()
    }

    @AfterTest
    fun tearDown() {
        OnboardingOrchestrator.resetForTesting()
    }

    // resolve() — all input combinations

    @Test
    fun `fresh install shows free wizard`() {
        val result =
            OnboardingOrchestrator.resolve(
                isLicensedOrGrace = false,
                freeOnboardingShown = false,
                premiumOnboardingShown = false,
                isReturningUser = false,
            )
        assertEquals(WizardAction.ShowFreeWizard, result)
    }

    @Test
    fun `licensed fresh install shows free wizard first`() {
        val result =
            OnboardingOrchestrator.resolve(
                isLicensedOrGrace = true,
                freeOnboardingShown = false,
                premiumOnboardingShown = false,
                isReturningUser = false,
            )
        assertEquals(WizardAction.ShowFreeWizard, result)
    }

    @Test
    fun `licensed user with free shown gets premium wizard`() {
        val result =
            OnboardingOrchestrator.resolve(
                isLicensedOrGrace = true,
                freeOnboardingShown = true,
                premiumOnboardingShown = false,
                isReturningUser = false,
            )
        assertEquals(WizardAction.ShowPremiumWizard, result)
    }

    @Test
    fun `unlicensed user with free shown gets no wizard`() {
        val result =
            OnboardingOrchestrator.resolve(
                isLicensedOrGrace = false,
                freeOnboardingShown = true,
                premiumOnboardingShown = false,
                isReturningUser = false,
            )
        assertEquals(WizardAction.NoWizard, result)
    }

    @Test
    fun `licensed user with both shown gets no wizard`() {
        val result =
            OnboardingOrchestrator.resolve(
                isLicensedOrGrace = true,
                freeOnboardingShown = true,
                premiumOnboardingShown = true,
                isReturningUser = false,
            )
        assertEquals(WizardAction.NoWizard, result)
    }

    @Test
    fun `returning unlicensed user skips free and gets no wizard`() {
        val result =
            OnboardingOrchestrator.resolve(
                isLicensedOrGrace = false,
                freeOnboardingShown = false,
                premiumOnboardingShown = false,
                isReturningUser = true,
            )
        assertEquals(WizardAction.NoWizard, result)
    }

    @Test
    fun `returning licensed user skips free and gets premium wizard`() {
        val result =
            OnboardingOrchestrator.resolve(
                isLicensedOrGrace = true,
                freeOnboardingShown = false,
                premiumOnboardingShown = false,
                isReturningUser = true,
            )
        assertEquals(WizardAction.ShowPremiumWizard, result)
    }

    @Test
    fun `returning licensed user with premium already shown gets no wizard`() {
        val result =
            OnboardingOrchestrator.resolve(
                isLicensedOrGrace = true,
                freeOnboardingShown = false,
                premiumOnboardingShown = true,
                isReturningUser = true,
            )
        assertEquals(WizardAction.NoWizard, result)
    }

    // tryPick — one-shot claim guard

    @Test
    fun `tryPick returns true on first call`() {
        assertTrue(OnboardingOrchestrator.tryPick())
    }

    @Test
    fun `tryPick returns false on subsequent calls`() {
        OnboardingOrchestrator.tryPick()
        assertFalse(OnboardingOrchestrator.tryPick())
    }

    @Test
    fun `resetForTesting re-enables tryPick`() {
        OnboardingOrchestrator.tryPick()
        OnboardingOrchestrator.resetForTesting()
        assertTrue(OnboardingOrchestrator.tryPick())
    }
}
