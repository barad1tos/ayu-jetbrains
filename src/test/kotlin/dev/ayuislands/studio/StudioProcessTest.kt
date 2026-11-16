package dev.ayuislands.studio

import dev.ayuislands.studio.model.StudioProcess
import dev.ayuislands.studio.model.StudioProcessRelation
import dev.ayuislands.studio.session.StudioProcessPolicy
import dev.ayuislands.studio.session.observeStudioProcess
import io.mockk.confirmVerified
import io.mockk.every
import io.mockk.mockk
import io.mockk.verifySequence
import java.time.Instant
import java.util.Optional
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class StudioProcessTest {
    private val startedAt = Instant.ofEpochSecond(100, 123456789)

    @Test
    fun `different pid proves different native objects even without start time`() {
        for (recordedStart in listOf(null, startedAt)) {
            for (currentStart in listOf(null, startedAt)) {
                assertEquals(
                    StudioProcessRelation.DIFFERENT,
                    StudioProcessPolicy.compare(StudioProcess(41, recordedStart), StudioProcess(42, currentStart)),
                )
            }
        }
    }

    @Test
    fun `same pid and known exact start identifies the same process`() {
        assertEquals(
            StudioProcessRelation.SAME,
            StudioProcessPolicy.compare(StudioProcess(42, startedAt), StudioProcess(42, startedAt)),
        )
    }

    @Test
    fun `reused pid with a different exact start cannot recover old native objects`() {
        assertEquals(
            StudioProcessRelation.DIFFERENT,
            StudioProcessPolicy.compare(StudioProcess(42, startedAt), StudioProcess(42, startedAt.plusNanos(1))),
        )
    }

    @Test
    fun `matching pid with incomplete observation retains unresolved recovery`() {
        val cases =
            listOf(
                StudioProcess(42, null) to StudioProcess(42, null),
                StudioProcess(42, startedAt) to StudioProcess(42, null),
                StudioProcess(42, null) to StudioProcess(42, startedAt),
                StudioProcess(42, startedAt) to null,
            )
        for ((recorded, current) in cases) {
            assertEquals(StudioProcessRelation.UNCONFIRMED, StudioProcessPolicy.compare(recorded, current))
        }
    }

    @Test
    fun `observation reads only identity fields and preserves missing start time`() {
        for (start in listOf(null, startedAt)) {
            val handle = mockk<ProcessHandle>()
            val information = mockk<ProcessHandle.Info>()
            every { handle.pid() } returns 42L
            every { handle.info() } returns information
            every { information.startInstant() } returns Optional.ofNullable(start)

            assertEquals(StudioProcess(42, start), observeStudioProcess { handle }.getOrThrow())

            verifySequence {
                handle.pid()
                handle.info()
                information.startInstant()
            }
            confirmVerified(handle, information)
        }
    }

    @Test
    fun `unsupported and denied observations retain the original reason`() {
        for (failure in listOf(SecurityException("Denied"), UnsupportedOperationException("Unavailable"))) {
            val result = observeStudioProcess { throw failure }

            assertSame(failure, result.exceptionOrNull())
            assertEquals(
                StudioProcessRelation.UNCONFIRMED,
                StudioProcessPolicy.compare(StudioProcess(42, startedAt), result.getOrNull()),
            )
        }
    }
}
