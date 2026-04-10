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

    // -----------------------------------------------------------------------
    // resolve() — full truth table (all meaningful input combinations)
    // -----------------------------------------------------------------------

    // Free wizard: fires when effectiveFreeShown=false (new user, free not yet shown)

    @Test
    fun `row 1 — unlicensed fresh install shows free`() {
        assertResolves(
            licensed = false,
            freeShown = false,
            returning = false,
            premiumShown = false,
            expected = WizardAction.ShowFreeWizard,
        )
    }

    @Test
    fun `row 2 — unlicensed fresh install with premiumShown=true still shows free`() {
        assertResolves(
            licensed = false,
            freeShown = false,
            returning = false,
            premiumShown = true,
            expected = WizardAction.ShowFreeWizard,
        )
    }

    @Test
    fun `row 3 — licensed fresh install shows free first`() {
        assertResolves(
            licensed = true,
            freeShown = false,
            returning = false,
            premiumShown = false,
            expected = WizardAction.ShowFreeWizard,
        )
    }

    @Test
    fun `row 4 — licensed fresh install with premiumShown=true still shows free`() {
        assertResolves(
            licensed = true,
            freeShown = false,
            returning = false,
            premiumShown = true,
            expected = WizardAction.ShowFreeWizard,
        )
    }

    // NoWizard: unlicensed user who already saw free, or all shown

    @Test
    fun `row 5 — unlicensed with free shown gets no wizard`() {
        assertResolves(
            licensed = false,
            freeShown = true,
            returning = false,
            premiumShown = false,
            expected = WizardAction.NoWizard,
        )
    }

    @Test
    fun `row 6 — unlicensed with both shown gets no wizard`() {
        assertResolves(
            licensed = false,
            freeShown = true,
            returning = false,
            premiumShown = true,
            expected = WizardAction.NoWizard,
        )
    }

    @Test
    fun `row 8 — licensed with both shown gets no wizard`() {
        assertResolves(
            licensed = true,
            freeShown = true,
            returning = false,
            premiumShown = true,
            expected = WizardAction.NoWizard,
        )
    }

    // Premium wizard: licensed + free already shown + premium not yet shown

    @Test
    fun `row 7 — licensed with free shown gets premium`() {
        assertResolves(
            licensed = true,
            freeShown = true,
            returning = false,
            premiumShown = false,
            expected = WizardAction.ShowPremiumWizard,
        )
    }

    // Returning user: effectiveFreeShown = true regardless of freeShown flag

    @Test
    fun `row 9 — returning unlicensed user gets no wizard`() {
        assertResolves(
            licensed = false,
            freeShown = false,
            returning = true,
            premiumShown = false,
            expected = WizardAction.NoWizard,
        )
    }

    @Test
    fun `row 10 — returning unlicensed with premiumShown gets no wizard`() {
        assertResolves(
            licensed = false,
            freeShown = false,
            returning = true,
            premiumShown = true,
            expected = WizardAction.NoWizard,
        )
    }

    @Test
    fun `row 11 — returning licensed user gets premium`() {
        assertResolves(
            licensed = true,
            freeShown = false,
            returning = true,
            premiumShown = false,
            expected = WizardAction.ShowPremiumWizard,
        )
    }

    @Test
    fun `row 12 — returning licensed with premiumShown gets no wizard`() {
        assertResolves(
            licensed = true,
            freeShown = false,
            returning = true,
            premiumShown = true,
            expected = WizardAction.NoWizard,
        )
    }

    // -----------------------------------------------------------------------
    // Multi-step user journey tests
    // -----------------------------------------------------------------------

    @Test
    fun `journey — fresh install unlicensed then stays unlicensed`() {
        // First startup: free wizard
        var freeShown = false
        var premiumShown = false

        var action = resolve(licensed = false, freeShown = freeShown, returning = false, premiumShown = premiumShown)
        assertEquals(WizardAction.ShowFreeWizard, action)
        freeShown = true // wizard opened, flag set

        // Second startup: no wizard (unlicensed, free already shown)
        action = resolve(licensed = false, freeShown = freeShown, returning = false, premiumShown = premiumShown)
        assertEquals(WizardAction.NoWizard, action)

        // Third startup: still no wizard
        action = resolve(licensed = false, freeShown = freeShown, returning = false, premiumShown = premiumShown)
        assertEquals(WizardAction.NoWizard, action)
    }

    @Test
    fun `journey — fresh install with trial active`() {
        var freeShown = false
        var premiumShown = false

        // First startup: free wizard (regardless of license)
        var action = resolve(licensed = true, freeShown = freeShown, returning = false, premiumShown = premiumShown)
        assertEquals(WizardAction.ShowFreeWizard, action)
        freeShown = true

        // Second startup: premium wizard
        action = resolve(licensed = true, freeShown = freeShown, returning = false, premiumShown = premiumShown)
        assertEquals(WizardAction.ShowPremiumWizard, action)
        premiumShown = true

        // Third startup: no wizard
        action = resolve(licensed = true, freeShown = freeShown, returning = false, premiumShown = premiumShown)
        assertEquals(WizardAction.NoWizard, action)
    }

    @Test
    fun `journey — upgrade from v2_3 returning user unlicensed`() {
        // Returning user: lastSeenVersion != null
        val action = resolve(licensed = false, freeShown = false, returning = true, premiumShown = false)
        assertEquals(WizardAction.NoWizard, action)
    }

    @Test
    fun `journey — upgrade from v2_3 returning user with trial`() {
        var premiumShown = false

        // First startup: premium immediately (returning skips free)
        var action = resolve(licensed = true, freeShown = false, returning = true, premiumShown = premiumShown)
        assertEquals(WizardAction.ShowPremiumWizard, action)
        premiumShown = true

        // Second startup: no wizard
        action = resolve(licensed = true, freeShown = false, returning = true, premiumShown = premiumShown)
        assertEquals(WizardAction.NoWizard, action)
    }

    @Test
    fun `journey — trial expired then re-purchased`() {
        var freeShown = true // already saw free earlier
        var premiumShown = true // already saw premium earlier

        // Trial expired: unlicensed startups show nothing
        var action = resolve(licensed = false, freeShown = freeShown, returning = false, premiumShown = premiumShown)
        assertEquals(WizardAction.NoWizard, action)

        // Re-purchase: premiumOnboardingShown re-armed to false by applyLicensedDefaults
        premiumShown = false
        action = resolve(licensed = true, freeShown = freeShown, returning = false, premiumShown = premiumShown)
        assertEquals(WizardAction.ShowPremiumWizard, action)
        premiumShown = true

        // Next startup: done
        action = resolve(licensed = true, freeShown = freeShown, returning = false, premiumShown = premiumShown)
        assertEquals(WizardAction.NoWizard, action)
    }

    @Test
    fun `journey — free shown then license purchased mid-session`() {
        var freeShown = true
        var premiumShown = false

        // License purchased mid-session → next startup
        val action = resolve(licensed = true, freeShown = freeShown, returning = false, premiumShown = premiumShown)
        assertEquals(WizardAction.ShowPremiumWizard, action)
    }

    @Test
    fun `journey — free never interacts then license expires`() {
        // Fresh install, trial active, but user closes free wizard without interacting
        var freeShown = true // flag set by wizard open
        var premiumShown = false

        // Second startup: premium
        var action = resolve(licensed = true, freeShown = freeShown, returning = false, premiumShown = premiumShown)
        assertEquals(WizardAction.ShowPremiumWizard, action)
        premiumShown = true

        // Trial expires
        action = resolve(licensed = false, freeShown = freeShown, returning = false, premiumShown = premiumShown)
        assertEquals(WizardAction.NoWizard, action)
    }

    // -----------------------------------------------------------------------
    // tryPick — one-shot claim guard
    // -----------------------------------------------------------------------

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

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private fun resolve(
        licensed: Boolean,
        freeShown: Boolean,
        returning: Boolean,
        premiumShown: Boolean,
    ): WizardAction =
        OnboardingOrchestrator.resolve(
            isLicensedOrGrace = licensed,
            freeOnboardingShown = freeShown,
            premiumOnboardingShown = premiumShown,
            isReturningUser = returning,
        )

    private fun assertResolves(
        licensed: Boolean,
        freeShown: Boolean,
        returning: Boolean,
        premiumShown: Boolean,
        expected: WizardAction,
    ) {
        assertEquals(expected, resolve(licensed, freeShown, returning, premiumShown))
    }
}
