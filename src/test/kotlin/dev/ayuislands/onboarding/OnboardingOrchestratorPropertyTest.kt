package dev.ayuislands.onboarding

import io.kotest.property.Exhaustive
import io.kotest.property.checkAll
import io.kotest.property.exhaustive.boolean
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class OnboardingOrchestratorPropertyTest {
    @Test
    fun `resolve returns exactly one variant for every boolean combination`(): Unit =
        runBlocking {
            checkAll(
                Exhaustive.boolean(),
                Exhaustive.boolean(),
                Exhaustive.boolean(),
                Exhaustive.boolean(),
            ) { licensed, freeShown, premiumShown, returning ->
                val result =
                    OnboardingOrchestrator.resolve(
                        isLicensedOrGrace = licensed,
                        freeOnboardingShown = freeShown,
                        premiumOnboardingShown = premiumShown,
                        isReturningUser = returning,
                    )
                assertTrue(
                    result is WizardAction.ShowFreeWizard ||
                        result is WizardAction.ShowPremiumWizard ||
                        result is WizardAction.NoWizard,
                    "Result must be one of the three actions, got: $result",
                )
            }
        }

    @Test
    fun `free wizard only when effective free not shown and not returning`(): Unit =
        runBlocking {
            checkAll(
                Exhaustive.boolean(),
                Exhaustive.boolean(),
                Exhaustive.boolean(),
                Exhaustive.boolean(),
            ) { licensed, freeShown, premiumShown, returning ->
                val result =
                    OnboardingOrchestrator.resolve(
                        isLicensedOrGrace = licensed,
                        freeOnboardingShown = freeShown,
                        premiumOnboardingShown = premiumShown,
                        isReturningUser = returning,
                    )
                val effectiveFreeShown = freeShown || returning
                if (result == WizardAction.ShowFreeWizard) {
                    assertTrue(
                        !effectiveFreeShown,
                        "Free wizard requires effectiveFreeShown=false " +
                            "(freeShown=$freeShown, returning=$returning)",
                    )
                }
                if (!effectiveFreeShown) {
                    assertEquals(
                        WizardAction.ShowFreeWizard,
                        result,
                        "When effectiveFreeShown=false, must show free wizard",
                    )
                }
            }
        }

    @Test
    fun `premium wizard never shown when not licensed`(): Unit =
        runBlocking {
            checkAll(
                Exhaustive.boolean(),
                Exhaustive.boolean(),
                Exhaustive.boolean(),
            ) { freeShown, premiumShown, returning ->
                val result =
                    OnboardingOrchestrator.resolve(
                        isLicensedOrGrace = false,
                        freeOnboardingShown = freeShown,
                        premiumOnboardingShown = premiumShown,
                        isReturningUser = returning,
                    )
                assertNotEquals(
                    WizardAction.ShowPremiumWizard,
                    result,
                    "Premium wizard must not appear for unlicensed users " +
                        "(freeShown=$freeShown, premiumShown=$premiumShown, returning=$returning)",
                )
            }
        }

    @Test
    fun `premium wizard requires licensed and effective free shown and premium not shown`(): Unit =
        runBlocking {
            checkAll(
                Exhaustive.boolean(),
                Exhaustive.boolean(),
                Exhaustive.boolean(),
                Exhaustive.boolean(),
            ) { licensed, freeShown, premiumShown, returning ->
                val result =
                    OnboardingOrchestrator.resolve(
                        isLicensedOrGrace = licensed,
                        freeOnboardingShown = freeShown,
                        premiumOnboardingShown = premiumShown,
                        isReturningUser = returning,
                    )
                val effectiveFreeShown = freeShown || returning
                if (result == WizardAction.ShowPremiumWizard) {
                    assertTrue(licensed, "Premium requires licensed=true")
                    assertTrue(effectiveFreeShown, "Premium requires effectiveFreeShown=true")
                    assertTrue(!premiumShown, "Premium requires premiumShown=false")
                }
            }
        }

    @Test
    fun `no wizard when all onboarding already completed`(): Unit =
        runBlocking {
            checkAll(
                Exhaustive.boolean(),
                Exhaustive.boolean(),
            ) { licensed, returning ->
                val result =
                    OnboardingOrchestrator.resolve(
                        isLicensedOrGrace = licensed,
                        freeOnboardingShown = true,
                        premiumOnboardingShown = true,
                        isReturningUser = returning,
                    )
                assertEquals(
                    WizardAction.NoWizard,
                    result,
                    "Both shown -> no wizard (licensed=$licensed, returning=$returning)",
                )
            }
        }
}
