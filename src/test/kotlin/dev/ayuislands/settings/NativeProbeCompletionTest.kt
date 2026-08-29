package dev.ayuislands.settings

import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.util.Disposer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

class NativeProbeCompletionTest {
    @Test
    fun `ordinary failure completes as deferred on the requested dispatcher`() {
        val parent = Disposer.newDisposable()
        val dispatches = mutableListOf<() -> Unit>()
        val results = mutableListOf<SyntaxProbeResult>()
        val completion =
            NativeProbeCompletion(
                languageId = "Swift",
                generation = 7,
                parent = parent,
                completed = results::add,
                dispatch = dispatches::add,
            )

        completion.failed(IllegalStateException("NoClassDefFoundError: internal.plugin.Probe"))

        assertTrue(results.isEmpty())
        assertEquals(1, dispatches.size)
        dispatches.single().invoke()
        val result = assertIs<SyntaxProbeResult.Deferred>(results.single())
        assertEquals("Swift", result.languageId)
        assertEquals(7, result.generation)
        assertEquals(
            "Could not check Swift support. Retry, or update or enable its language plugin if the problem continues.",
            result.reason,
        )
        Disposer.dispose(parent)
    }

    @Test
    fun `failure completion is ignored after the probe lifetime ends`() {
        val parent = Disposer.newDisposable()
        val results = mutableListOf<SyntaxProbeResult>()
        val completion =
            NativeProbeCompletion(
                languageId = "Swift",
                generation = 7,
                parent = parent,
                completed = results::add,
                dispatch = { action -> action() },
            )
        Disposer.dispose(parent)

        completion.failed(IllegalStateException("late failure"))

        assertTrue(results.isEmpty())
    }

    @Test
    fun `active cancellation preserves exception identity`() {
        val parent = Disposer.newDisposable()
        val cancellation = ProcessCanceledException()
        val completion =
            NativeProbeCompletion(
                languageId = "Swift",
                generation = 7,
                parent = parent,
                completed = {},
                dispatch = { action -> action() },
            )

        val thrown = assertFails { completion.failed(cancellation) }

        assertSame(cancellation, thrown)
        Disposer.dispose(parent)
    }
}
