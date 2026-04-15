package dev.ayuislands.accent

import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins the contract of [runCatchingPreservingCancellation] so that a future refactor
 * cannot silently swap it back to raw [kotlin.runCatching]. Callers (AccentResolver,
 * ProjectLanguageDetector) rely on both halves of the contract: success / non-cancellation
 * Throwable is captured in `Result`, but `CancellationException` is rethrown.
 */
class CancellationSafeRunCatchingTest {
    @Test
    fun `success returns Result with the produced value`() {
        val result = runCatchingPreservingCancellation { "ok" }
        assertTrue(result.isSuccess, "successful block must yield Result.success")
        assertEquals("ok", result.getOrNull())
    }

    @Test
    fun `RuntimeException is captured as Result failure`() {
        val boom = IllegalStateException("transient platform failure")
        val result = runCatchingPreservingCancellation { throw boom }
        assertTrue(result.isFailure, "RuntimeException must be captured, not rethrown")
        assertEquals(boom, result.exceptionOrNull())
        assertNull(result.getOrNull())
    }

    @Test
    fun `Error subtypes are captured as Result failure`() {
        // The helper's KDoc promises that Error subtypes (like NoClassDefFoundError from a
        // mid-dispose race on a lazily-loaded service) keep the fall-back-to-null behavior
        // of the original runCatching. Verify NoClassDefFoundError in particular — it's the
        // one explicitly named in the KDoc.
        val boom = NoClassDefFoundError("simulated class-loading race")
        val result = runCatchingPreservingCancellation { throw boom }
        assertTrue(result.isFailure, "Error subtypes must still be captured, not rethrown")
        assertEquals(boom, result.exceptionOrNull())
    }

    @Test
    fun `CancellationException is rethrown instead of being captured`() {
        // Structured-concurrency guarantee: resolver chain callers run on coroutine bodies,
        // so a captured CancellationException would keep a cancelled coroutine alive past
        // its scope. The helper must rethrow.
        val boom = CancellationException("coroutine cancelled mid-probe")
        val thrown =
            assertFailsWith<CancellationException> {
                runCatchingPreservingCancellation<String> { throw boom }
            }
        assertEquals("coroutine cancelled mid-probe", thrown.message)
        assertEquals(boom, thrown, "must rethrow the ORIGINAL instance, not a wrapper")
    }

    @Test
    fun `CancellationException subtypes are also rethrown`() {
        // JobCancellationException etc. extend CancellationException — the `is` check in
        // the helper uses instanceof semantics so subtype coverage transfers automatically.
        // This test pins that behavior against a refactor to `== CancellationException`.
        class StartupCancelledException : CancellationException("startup cancelled")

        val boom = StartupCancelledException()
        val thrown =
            assertFailsWith<CancellationException> {
                runCatchingPreservingCancellation<Unit> { throw boom }
            }
        assertEquals(boom, thrown)
    }
}
