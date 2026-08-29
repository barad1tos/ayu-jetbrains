package dev.ayuislands.settings

import com.intellij.openapi.progress.ProcessCanceledException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertSame

class CleanupStepsTest {
    @Test
    fun `cleanup runs every step and rethrows the first failure`() {
        val calls = mutableListOf<String>()
        val first = IllegalStateException("first")
        val second = IllegalArgumentException("second")

        val thrown =
            assertFails {
                runCleanupSteps(
                    {
                        calls += "first"
                        throw first
                    },
                    { calls += "middle" },
                    {
                        calls += "last"
                        throw second
                    },
                )
            }

        assertSame(first, thrown)
        assertEquals(listOf(second), thrown.suppressed.toList())
        assertEquals(listOf("first", "middle", "last"), calls)
    }

    @Test
    fun `cancellation wins after every cleanup step has run`() {
        val calls = mutableListOf<String>()
        val ordinaryFailure = IllegalStateException("ordinary")
        val cancellation = ProcessCanceledException()

        val thrown =
            assertFails {
                runCleanupSteps(
                    {
                        calls += "first"
                        throw ordinaryFailure
                    },
                    {
                        calls += "cancel"
                        throw cancellation
                    },
                    { calls += "last" },
                )
            }

        assertSame(cancellation, thrown)
        assertEquals(listOf(ordinaryFailure), thrown.suppressed.toList())
        assertEquals(listOf("first", "cancel", "last"), calls)
    }
}
