package dev.ayuislands.onboarding

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
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
    // resolve() - full truth table (all meaningful input combinations)
    // -----------------------------------------------------------------------

    // Free wizard: fires when effectiveFreeShown=false (new user, free not yet shown)

    @Test
    fun `row 1 - unlicensed fresh install shows free`() {
        assertResolves(
            licensed = false,
            freeShown = false,
            returning = false,
            premiumShown = false,
            expected = WizardAction.ShowFreeWizard,
        )
    }

    @Test
    fun `row 2 - unlicensed fresh install with premiumShown=true still shows free`() {
        assertResolves(
            licensed = false,
            freeShown = false,
            returning = false,
            premiumShown = true,
            expected = WizardAction.ShowFreeWizard,
        )
    }

    @Test
    fun `row 3 - licensed fresh install shows free first`() {
        assertResolves(
            licensed = true,
            freeShown = false,
            returning = false,
            premiumShown = false,
            expected = WizardAction.ShowFreeWizard,
        )
    }

    @Test
    fun `row 4 - licensed fresh install with premiumShown=true still shows free`() {
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
    fun `row 5 - unlicensed with free shown gets no wizard`() {
        assertResolves(
            licensed = false,
            freeShown = true,
            returning = false,
            premiumShown = false,
            expected = WizardAction.NoWizard,
        )
    }

    @Test
    fun `row 6 - unlicensed with both shown gets no wizard`() {
        assertResolves(
            licensed = false,
            freeShown = true,
            returning = false,
            premiumShown = true,
            expected = WizardAction.NoWizard,
        )
    }

    @Test
    fun `row 8 - licensed with both shown gets no wizard`() {
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
    fun `row 7 - licensed with free shown gets premium`() {
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
    fun `row 9 - returning unlicensed user gets no wizard`() {
        assertResolves(
            licensed = false,
            freeShown = false,
            returning = true,
            premiumShown = false,
            expected = WizardAction.NoWizard,
        )
    }

    @Test
    fun `row 10 - returning unlicensed with premiumShown gets no wizard`() {
        assertResolves(
            licensed = false,
            freeShown = false,
            returning = true,
            premiumShown = true,
            expected = WizardAction.NoWizard,
        )
    }

    @Test
    fun `row 11 - returning licensed user gets premium`() {
        assertResolves(
            licensed = true,
            freeShown = false,
            returning = true,
            premiumShown = false,
            expected = WizardAction.ShowPremiumWizard,
        )
    }

    @Test
    fun `row 12 - returning licensed with premiumShown gets no wizard`() {
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
    fun `journey - fresh install unlicensed then stays unlicensed`() {
        // Session 1: free wizard fires
        assertEquals(
            WizardAction.ShowFreeWizard,
            resolve(licensed = false, freeShown = false, returning = false, premiumShown = false),
        )
        // Session 2: flag latched, no wizard (unlicensed, free already shown)
        assertEquals(
            WizardAction.NoWizard,
            resolve(licensed = false, freeShown = true, returning = false, premiumShown = false),
        )
        // Session 3: still no wizard
        assertEquals(
            WizardAction.NoWizard,
            resolve(licensed = false, freeShown = true, returning = false, premiumShown = false),
        )
    }

    @Test
    fun `journey - fresh install with trial active`() {
        // Session 1: free wizard (regardless of license)
        assertEquals(
            WizardAction.ShowFreeWizard,
            resolve(licensed = true, freeShown = false, returning = false, premiumShown = false),
        )
        // Session 2: premium wizard
        assertEquals(
            WizardAction.ShowPremiumWizard,
            resolve(licensed = true, freeShown = true, returning = false, premiumShown = false),
        )
        // Session 3: done
        assertEquals(
            WizardAction.NoWizard,
            resolve(licensed = true, freeShown = true, returning = false, premiumShown = true),
        )
    }

    @Test
    fun `journey - upgrade from v2_3 returning user unlicensed`() {
        // Returning user: lastSeenVersion != null
        assertEquals(
            WizardAction.NoWizard,
            resolve(licensed = false, freeShown = false, returning = true, premiumShown = false),
        )
    }

    @Test
    fun `journey - upgrade from v2_3 returning user with trial`() {
        // Session 1: premium immediately (returning skips free)
        assertEquals(
            WizardAction.ShowPremiumWizard,
            resolve(licensed = true, freeShown = false, returning = true, premiumShown = false),
        )
        // Session 2: done
        assertEquals(
            WizardAction.NoWizard,
            resolve(licensed = true, freeShown = false, returning = true, premiumShown = true),
        )
    }

    @Test
    fun `journey - trial expired then re-purchased`() {
        // Session 1: trial expired, unlicensed, both wizards already seen → nothing
        assertEquals(
            WizardAction.NoWizard,
            resolve(licensed = false, freeShown = true, returning = false, premiumShown = true),
        )
        // Session 2: re-purchase re-arms premiumOnboardingShown (via applyLicensedDefaults)
        assertEquals(
            WizardAction.ShowPremiumWizard,
            resolve(licensed = true, freeShown = true, returning = false, premiumShown = false),
        )
        // Session 3: flag set again, done
        assertEquals(
            WizardAction.NoWizard,
            resolve(licensed = true, freeShown = true, returning = false, premiumShown = true),
        )
    }

    @Test
    fun `journey - free shown then license purchased mid-session`() {
        // License purchased mid-session, next startup shows premium
        assertEquals(
            WizardAction.ShowPremiumWizard,
            resolve(licensed = true, freeShown = true, returning = false, premiumShown = false),
        )
    }

    @Test
    fun `journey - free never interacts then license expires`() {
        // Session 1: premium wizard (free already shown, licensed)
        assertEquals(
            WizardAction.ShowPremiumWizard,
            resolve(licensed = true, freeShown = true, returning = false, premiumShown = false),
        )
        // Session 2: trial expires, both flags still set → no wizard
        assertEquals(
            WizardAction.NoWizard,
            resolve(licensed = false, freeShown = true, returning = false, premiumShown = true),
        )
    }

    // -----------------------------------------------------------------------
    // tryPick - one-shot claim guard
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

    @Test
    fun `tryPick is thread-safe under concurrent access from 100 threads`() {
        OnboardingOrchestrator.resetForTesting()
        val threadCount = 100
        val startLatch = CountDownLatch(1)
        val doneLatch = CountDownLatch(threadCount)
        val trueCount = AtomicInteger(0)
        val executor = Executors.newFixedThreadPool(threadCount)
        try {
            repeat(threadCount) {
                executor.submit {
                    try {
                        // Block all worker threads until the main thread releases them,
                        // so they race on tryPick() instead of running serialized.
                        startLatch.await()
                        if (OnboardingOrchestrator.tryPick()) {
                            trueCount.incrementAndGet()
                        }
                    } finally {
                        doneLatch.countDown()
                    }
                }
            }
            startLatch.countDown()
            assertTrue(
                doneLatch.await(5, TimeUnit.SECONDS),
                "Worker threads did not finish within 5 seconds",
            )
        } finally {
            executor.shutdownNow()
        }

        assertEquals(
            1,
            trueCount.get(),
            "Exactly one of $threadCount concurrent callers must win tryPick()",
        )
        // Subsequent call must still return false - the one-shot is latched.
        assertFalse(OnboardingOrchestrator.tryPick())
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
