package dev.ayuislands.settings

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

class SettingsSessionTest {
    @Test
    fun `successful build opens declared participants in order`() {
        val calls = mutableListOf<String>()
        val system = RecordingParticipant("System", calls)
        val accent = RecordingParticipant("Accent", calls)
        val session = SettingsSession()

        session.build {
            include(
                namedParticipant("System", system),
                namedParticipant("Accent", accent),
            ) {
                calls += "build"
            }
        }

        assertEquals(listOf("System", "Accent"), session.participantNames)
        assertEquals(listOf("build"), calls)
    }

    @Test
    fun `failed build unit disposes candidates and prior participants in reverse order`() {
        val calls = mutableListOf<String>()
        val prior = RecordingParticipant("Prior", calls)
        val first = RecordingParticipant("First", calls)
        val second = RecordingParticipant("Second", calls)
        val session = SettingsSession()

        assertFailsWith<IllegalStateException> {
            session.build {
                include(namedParticipant("Prior", prior)) {}
                include(
                    namedParticipant("First", first),
                    namedParticipant("Second", second),
                ) {
                    error("build failed")
                }
            }
        }

        assertEquals(
            listOf("dispose:Second", "dispose:First", "dispose:Prior"),
            calls,
        )
        assertTrue(session.isClosed)
    }

    @Test
    fun `apply stops at first failure and leaves the session open for retry`() {
        val calls = mutableListOf<String>()
        val first = RecordingParticipant("First", calls)
        val accent = RecordingParticipant("Accent", calls)
        val chrome = RecordingParticipant("Chrome", calls)
        val failure = IllegalStateException("accent failed")
        accent.applyFailure = failure
        var refreshCount = 0
        val session = openSession(first, accent, chrome, afterSuccessfulApply = { refreshCount++ })

        val result = session.apply()

        val failed = assertIs<SettingsApplyResult.Failed>(result)
        assertEquals("Accent", failed.failed)
        assertEquals(listOf("Chrome"), failed.skipped)
        assertSame(failure, failed.cause)
        assertEquals(listOf("apply:First", "apply:Accent"), calls)
        assertEquals(0, refreshCount)
        assertFalse(session.isClosed)

        accent.applyFailure = null
        assertEquals(SettingsApplyResult.Applied, session.apply())
        assertEquals(1, refreshCount)
        assertEquals(
            listOf("apply:First", "apply:Accent", "apply:First", "apply:Accent", "apply:Chrome"),
            calls,
        )
    }

    @Test
    fun `modified check and reset follow participant order`() {
        val calls = mutableListOf<String>()
        val first = RecordingParticipant("First", calls)
        val second = RecordingParticipant("Second", calls).apply { shouldReportModified = true }
        val third = RecordingParticipant("Third", calls)
        val session = openSession(first, second, third)

        assertTrue(session.isModified())
        assertEquals(listOf("modified:First", "modified:Second"), calls)

        calls.clear()
        session.reset()
        assertEquals(listOf("reset:First", "reset:Second", "reset:Third"), calls)
    }

    @Test
    fun `reset preserves ordered fail-fast behavior`() {
        val calls = mutableListOf<String>()
        val first = RecordingParticipant("First", calls)
        val second = RecordingParticipant("Second", calls)
        val third = RecordingParticipant("Third", calls)
        val failure = IllegalStateException("reset failed")
        second.resetFailure = failure
        val session = openSession(first, second, third)

        val thrown = assertFailsWith<IllegalStateException> { session.reset() }

        assertSame(failure, thrown)
        assertEquals(listOf("reset:First", "reset:Second"), calls)
        assertFalse(session.isClosed)
    }

    @Test
    fun `close disposes in reverse order isolates failures and is idempotent`() {
        val calls = mutableListOf<String>()
        val first = RecordingParticipant("First", calls)
        val second = RecordingParticipant("Second", calls)
        val third = RecordingParticipant("Third", calls)
        val failure = IllegalStateException("second dispose failed")
        second.disposeFailure = failure
        val session = openSession(first, second, third)

        val cleanupFailures = session.close()

        assertEquals(listOf("dispose:Third", "dispose:Second", "dispose:First"), calls)
        assertEquals(1, cleanupFailures.size)
        assertEquals("Second", cleanupFailures.single().participant)
        assertSame(failure, cleanupFailures.single().cause)
        assertTrue(session.isClosed)

        assertEquals(emptyList(), session.close())
        assertEquals(listOf("dispose:Third", "dispose:Second", "dispose:First"), calls)
    }

    @Test
    fun `build failure preserves the original error and suppresses cleanup failures`() {
        val calls = mutableListOf<String>()
        val prior = RecordingParticipant("Prior", calls)
        val candidate = RecordingParticipant("Candidate", calls)
        prior.disposeFailure = IllegalStateException("prior cleanup failed")
        candidate.disposeFailure = IllegalStateException("candidate cleanup failed")
        val buildFailure = IllegalArgumentException("build failed")
        val session = SettingsSession()

        val thrown =
            assertFailsWith<IllegalArgumentException> {
                session.build {
                    include(namedParticipant("Prior", prior)) {}
                    include(namedParticipant("Candidate", candidate)) { throw buildFailure }
                }
            }

        assertSame(buildFailure, thrown)
        assertEquals(
            listOf("candidate cleanup failed", "prior cleanup failed"),
            thrown.suppressed.map { it.message },
        )
        assertEquals(listOf("dispose:Candidate", "dispose:Prior"), calls)
        assertTrue(session.isClosed)
    }

    @Test
    fun `lifecycle operations reject sessions that are not open`() {
        val newSession = SettingsSession()

        assertFailsWith<IllegalStateException> { newSession.isModified() }
        assertFailsWith<IllegalStateException> { newSession.apply() }
        assertFailsWith<IllegalStateException> { newSession.reset() }

        assertEquals(emptyList(), newSession.close())
        assertTrue(newSession.isClosed)
        assertFailsWith<IllegalStateException> { newSession.isModified() }
        assertFailsWith<IllegalStateException> { newSession.apply() }
        assertFailsWith<IllegalStateException> { newSession.reset() }
        assertFailsWith<IllegalStateException> { newSession.build {} }
    }

    private fun openSession(
        vararg participants: RecordingParticipant,
        afterSuccessfulApply: () -> Unit = {},
    ): SettingsSession =
        SettingsSession(afterSuccessfulApply).also { session ->
            session.build {
                include(
                    *participants
                        .map { namedParticipant(it.name, it) }
                        .toTypedArray(),
                ) {}
            }
        }

    private class RecordingParticipant(
        val name: String,
        private val calls: MutableList<String>,
    ) : SettingsParticipant {
        var shouldReportModified = false
        var applyFailure: Throwable? = null
        var resetFailure: Throwable? = null
        var disposeFailure: Throwable? = null

        override fun isModified(): Boolean {
            calls += "modified:$name"
            return shouldReportModified
        }

        override fun apply() {
            calls += "apply:$name"
            applyFailure?.let { throw it }
        }

        override fun reset() {
            calls += "reset:$name"
            resetFailure?.let { throw it }
        }

        override fun dispose() {
            calls += "dispose:$name"
            disposeFailure?.let { throw it }
        }
    }
}
