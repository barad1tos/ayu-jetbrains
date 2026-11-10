package dev.ayuislands

import dev.ayuislands.onboarding.WizardAction
import dev.ayuislands.settings.AyuIslandsState
import dev.ayuislands.studio.StudioFixtures
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StartupLicenseStateTest {
    @Test
    fun `adaptive onboarding delay remains inside its established bounds`() {
        assertTrue(StartupLicenseHandler.computeAdaptiveDelay() in 3_000..45_000)
    }

    @Test
    fun `fresh free user gets the free wizard without mutating the accepted marker`() {
        val state = StudioFixtures.legacyView(AyuIslandsState()).settings
        assertEquals(WizardAction.ShowFreeWizard, StartupLicenseHandler.resolveOnboarding(false, state, false))
        assertFalse(state.freeOnboardingShown)
    }

    @Test
    fun `returning user skips free wizard without a speculative marker mutation`() {
        val state = StudioFixtures.legacyView(AyuIslandsState()).settings
        assertEquals(WizardAction.NoWizard, StartupLicenseHandler.resolveOnboarding(false, state, true))
        assertFalse(state.freeOnboardingShown)
    }

    @Test
    fun `licensed user with acknowledged free wizard gets premium onboarding only once`() {
        val bean = AyuIslandsState().apply { freeOnboardingShown = true }
        val before = StudioFixtures.legacyView(bean).settings
        assertEquals(WizardAction.ShowPremiumWizard, StartupLicenseHandler.resolveOnboarding(true, before, false))
        bean.premiumOnboardingShown = true
        val after = StudioFixtures.legacyView(bean).settings
        assertEquals(WizardAction.NoWizard, StartupLicenseHandler.resolveOnboarding(true, after, false))
    }
}
