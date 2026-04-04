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
        OnboardingOrchestrator.release()
    }

    @AfterTest
    fun tearDown() {
        OnboardingOrchestrator.release()
    }

    // resolve() — all input combinations

    @Test
    fun `fresh install shows free wizard`() {
        val result = OnboardingOrchestrator.resolve(
            isLicensedOrGrace = false,
            freeOnboardingShown = false,
            premiumOnboardingShown = false,
            isReturningUser = false,
        )
        assertEquals(WizardAction.ShowFreeWizard, result)
    }

    @Test
    fun `licensed fresh install shows free wizard first`() {
        val result = OnboardingOrchestrator.resolve(
            isLicensedOrGrace = true,
            freeOnboardingShown = false,
            premiumOnboardingShown = false,
            isReturningUser = false,
        )
        assertEquals(WizardAction.ShowFreeWizard, result)
    }

    @Test
    fun `licensed user with free shown gets premium wizard`() {
        val result = OnboardingOrchestrator.resolve(
            isLicensedOrGrace = true,
            freeOnboardingShown = true,
            premiumOnboardingShown = false,
            isReturningUser = false,
        )
        assertEquals(WizardAction.ShowPremiumWizard, result)
    }

    @Test
    fun `unlicensed user with free shown gets no wizard`() {
        val result = OnboardingOrchestrator.resolve(
            isLicensedOrGrace = false,
            freeOnboardingShown = true,
            premiumOnboardingShown = false,
            isReturningUser = false,
        )
        assertEquals(WizardAction.NoWizard, result)
    }

    @Test
    fun `licensed user with both shown gets no wizard`() {
        val result = OnboardingOrchestrator.resolve(
            isLicensedOrGrace = true,
            freeOnboardingShown = true,
            premiumOnboardingShown = true,
            isReturningUser = false,
        )
        assertEquals(WizardAction.NoWizard, result)
    }

    @Test
    fun `returning unlicensed user skips free and gets no wizard`() {
        val result = OnboardingOrchestrator.resolve(
            isLicensedOrGrace = false,
            freeOnboardingShown = false,
            premiumOnboardingShown = false,
            isReturningUser = true,
        )
        assertEquals(WizardAction.NoWizard, result)
    }

    @Test
    fun `returning licensed user skips free and gets premium wizard`() {
        val result = OnboardingOrchestrator.resolve(
            isLicensedOrGrace = true,
            freeOnboardingShown = false,
            premiumOnboardingShown = false,
            isReturningUser = true,
        )
        assertEquals(WizardAction.ShowPremiumWizard, result)
    }

    @Test
    fun `returning licensed user with premium already shown gets no wizard`() {
        val result = OnboardingOrchestrator.resolve(
            isLicensedOrGrace = true,
            freeOnboardingShown = false,
            premiumOnboardingShown = true,
            isReturningUser = true,
        )
        assertEquals(WizardAction.NoWizard, result)
    }

    // tryAcquire / release — AtomicBoolean guard

    @Test
    fun `tryAcquire returns true on first call`() {
        assertTrue(OnboardingOrchestrator.tryAcquire())
    }

    @Test
    fun `tryAcquire returns false when already acquired`() {
        OnboardingOrchestrator.tryAcquire()
        assertFalse(OnboardingOrchestrator.tryAcquire())
    }

    @Test
    fun `release resets guard for next tryAcquire`() {
        OnboardingOrchestrator.tryAcquire()
        OnboardingOrchestrator.release()
        assertTrue(OnboardingOrchestrator.tryAcquire())
    }
}
