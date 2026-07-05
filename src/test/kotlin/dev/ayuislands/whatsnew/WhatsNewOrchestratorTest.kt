package dev.ayuislands.whatsnew

import dev.ayuislands.onboarding.OnboardingOrchestrator
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * CAS semantics (acquire/release/reset, thread safety) live in
 * `dev.ayuislands.ui.SessionOneShotTest`. This file locks the
 * orchestrator-level invariant instead: the What's New gate is a separate
 * instance from the onboarding gate.
 */
class WhatsNewOrchestratorTest {
    @Test
    fun `whats new gate is independent from the onboarding wizard gate`() {
        // A fresh installer who finishes the onboarding wizard must not
        // accidentally suppress the next-version What's New tab in the same
        // session — the two features claim through distinct gates. Both gates
        // are global JVM state, so reset in finally.
        try {
            assertTrue(OnboardingOrchestrator.gate.tryAcquire(), "onboarding claim must start free")
            assertTrue(
                WhatsNewOrchestrator.gate.tryAcquire(),
                "an onboarding claim must not suppress the What's New tab in the same session",
            )
            assertFalse(WhatsNewOrchestrator.gate.tryAcquire(), "second What's New pick must lose as usual")
        } finally {
            WhatsNewOrchestrator.gate.resetForTesting()
            OnboardingOrchestrator.gate.resetForTesting()
        }
    }
}
