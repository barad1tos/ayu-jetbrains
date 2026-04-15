package dev.ayuislands.whatsnew

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

class WhatsNewOrchestratorTest {
    @BeforeTest
    fun setUp() {
        WhatsNewOrchestrator.resetForTesting()
    }

    @AfterTest
    fun tearDown() {
        WhatsNewOrchestrator.resetForTesting()
    }

    @Test
    fun `tryPick returns true on first call`() {
        assertTrue(WhatsNewOrchestrator.tryPick())
    }

    @Test
    fun `tryPick returns false on subsequent calls`() {
        WhatsNewOrchestrator.tryPick()
        assertFalse(WhatsNewOrchestrator.tryPick())
    }

    @Test
    fun `resetForTesting re-enables tryPick`() {
        WhatsNewOrchestrator.tryPick()
        WhatsNewOrchestrator.resetForTesting()
        assertTrue(WhatsNewOrchestrator.tryPick())
    }

    @Test
    fun `release re-enables tryPick after a failed open attempt`() {
        // Production scenario: launcher's tryPick() succeeded, then openFile
        // threw, leaving the orchestrator stuck in shown=true. Without release()
        // the JVM session is dead — no other window's auto-trigger and no
        // manual menu reopen could ever fire again.
        assertTrue(WhatsNewOrchestrator.tryPick(), "first pick must succeed")
        assertFalse(WhatsNewOrchestrator.tryPick(), "second pick must fail before release")
        WhatsNewOrchestrator.release()
        assertTrue(WhatsNewOrchestrator.tryPick(), "post-release pick must succeed again")
    }

    @Test
    fun `release on un-claimed orchestrator is a no-op`() {
        // Idempotent — calling release() before any pick must not flip state
        // into an unexpected configuration.
        WhatsNewOrchestrator.release()
        assertTrue(WhatsNewOrchestrator.tryPick(), "first pick after stray release must still win")
        assertFalse(WhatsNewOrchestrator.tryPick(), "second pick must lose as usual")
    }

    @Test
    fun `tryPick is thread-safe under concurrent access from 100 threads`() {
        // Multi-window startup race: 3 IDE windows can all schedule the open
        // simultaneously after their delays elapse. Exactly one must claim.
        // 100 threads is overkill for the actual scenario but flushes out any
        // weak compareAndSet behavior under contention.
        val threadCount = 100
        val startLatch = CountDownLatch(1)
        val doneLatch = CountDownLatch(threadCount)
        val trueCount = AtomicInteger(0)
        val executor = Executors.newFixedThreadPool(threadCount)
        try {
            repeat(threadCount) {
                executor.submit {
                    try {
                        startLatch.await()
                        if (WhatsNewOrchestrator.tryPick()) {
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
        assertFalse(WhatsNewOrchestrator.tryPick(), "post-race calls must still return false")
    }
}
