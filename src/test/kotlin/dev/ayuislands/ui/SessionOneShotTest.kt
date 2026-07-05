package dev.ayuislands.ui

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SessionOneShotTest {
    @Test
    fun `tryAcquire returns true on first call`() {
        assertTrue(SessionOneShot().tryAcquire())
    }

    @Test
    fun `tryAcquire returns false on subsequent calls`() {
        val gate = SessionOneShot()
        gate.tryAcquire()
        assertFalse(gate.tryAcquire())
    }

    @Test
    fun `resetForTesting re-enables tryAcquire`() {
        val gate = SessionOneShot()
        gate.tryAcquire()
        gate.resetForTesting()
        assertTrue(gate.tryAcquire())
    }

    @Test
    fun `release re-enables tryAcquire after a failed open attempt`() {
        // Production scenario: the opener's `tryAcquire()` succeeded, then the
        // open action threw, leaving the gate stuck claimed. Without
        // `release()` the JVM session is dead — no other window's auto-trigger
        // and no manual menu reopen could ever fire again.
        val gate = SessionOneShot()
        assertTrue(gate.tryAcquire(), "first acquire must succeed")
        assertFalse(gate.tryAcquire(), "second acquire must fail before release")
        gate.release()
        assertTrue(gate.tryAcquire(), "post-release acquire must succeed again")
    }

    @Test
    fun `release reports whether it flipped a held claim`() {
        // Callers gate their "released" log breadcrumb on the return value,
        // so a stray release must report false instead of emitting a
        // misleading signal.
        val gate = SessionOneShot()
        assertFalse(gate.release(), "release before any acquire must report no flip")
        assertTrue(gate.tryAcquire())
        assertTrue(gate.release(), "release of a held claim must report the flip")
        assertFalse(gate.release(), "second release must report a no-op")
    }

    @Test
    fun `release on un-claimed gate is a no-op`() {
        // Idempotent — calling `release()` before any acquire must not flip
        // state into an unexpected configuration.
        val gate = SessionOneShot()
        gate.release()
        assertTrue(gate.tryAcquire(), "first acquire after stray release must still win")
        assertFalse(gate.tryAcquire(), "second acquire must lose as usual")
    }

    @Test
    fun `double release after single acquire is a no-op on the second call`() {
        // The conditional `compareAndSet` in `release()` guarantees the second
        // release from a caller that has already released (or was never
        // claimed) doesn't re-open the gate. Pin this so a future refactor to
        // an unconditional `set(false)` regresses in CI.
        val gate = SessionOneShot()
        gate.tryAcquire()
        gate.release() // clears claim
        assertTrue(gate.tryAcquire(), "acquire after release must succeed")
        gate.release() // clears second claim
        // A stray third release must NOT flip state — the next `tryAcquire` owns it.
        gate.release()
        assertTrue(gate.tryAcquire(), "gate stays healthy through stray release calls")
    }

    @Test
    fun `instances are independent`() {
        // Each feature (What's New tab, onboarding wizard) holds its own gate;
        // claiming one must never suppress the other.
        val first = SessionOneShot()
        val second = SessionOneShot()
        assertTrue(first.tryAcquire())
        assertTrue(second.tryAcquire(), "claiming one gate must not claim another")
    }

    @Test
    fun `tryAcquire is thread-safe under concurrent access from 100 threads`() {
        // Multi-window startup race: 3 IDE windows can all schedule the open
        // simultaneously after their delays elapse. Exactly one must claim.
        // 100 threads is overkill for the actual scenario but flushes out any
        // weak compareAndSet behavior under contention.
        val gate = SessionOneShot()
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
                        if (gate.tryAcquire()) {
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
            "Exactly one of $threadCount concurrent callers must win tryAcquire()",
        )
        assertFalse(gate.tryAcquire(), "post-race calls must still return false")
    }
}
