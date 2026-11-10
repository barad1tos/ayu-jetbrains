package dev.ayuislands.studio

import dev.ayuislands.studio.model.DraftWriteIssue
import dev.ayuislands.studio.model.DraftWritePlan
import dev.ayuislands.studio.model.SessionAvailability
import dev.ayuislands.studio.model.StudioList
import dev.ayuislands.studio.persistence.DecodeResult
import dev.ayuislands.studio.persistence.StudioStateCodec
import dev.ayuislands.studio.session.StudioJournalPolicy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class StudioJournalPolicyTest {
    @Test
    fun `autosave changes only its journal record and preserves latest metadata and list order`() {
        val initial = sessionDocument()
        val record = requireNotNull(editedSession(initial).record)
        val before = sessionRecord(initial)
        val latest =
            initial.copy(
                header = initial.header.copy(revision = 9),
                sessions = StudioList(listOf(before.copy(id = "first"), before, before.copy(id = "last"))),
            )
        val result = assertIs<DraftWritePlan.Write>(StudioJournalPolicy.save(latest, record, "journal-1"))
        val candidate = result.candidate
        assertEquals(listOf("first", "session", "last"), candidate.sessions.map { it.id })
        assertEquals(record, candidate.sessions[1])
        assertEquals(10, candidate.header.revision)
        assertEquals("journal-1", candidate.header.transactionId)
        assertEquals(latest, candidate.copy(header = latest.header, sessions = latest.sessions))
        assertEquals(before.baseDocumentRevision, candidate.sessions[1].baseDocumentRevision)
    }

    @Test
    fun `persisted retirement wins over late live saves even when they use the latest document revision`() {
        val initial = sessionDocument()
        val record = requireNotNull(editedSession(initial).record)
        val retired = record.copy(generation = 3, availability = SessionAvailability.RETIRED)
        val latest = initial.copy(header = initial.header.copy(revision = 12), sessions = StudioList(listOf(retired)))
        for (generation in listOf(0L, 2L, 3L, 4L)) {
            val result =
                assertIs<DraftWritePlan.Rejected>(
                    StudioJournalPolicy.save(latest, record.copy(generation = generation), "late-$generation"),
                )
            assertEquals(DraftWriteIssue.RETIRED, result.issue)
        }
        assertEquals(retired, latest.sessions.single())
    }

    @Test
    fun `same generation is idempotent only for the identical immutable record`() {
        val initial = sessionDocument()
        val record = requireNotNull(editedSession(initial).record)
        val latest = initial.copy(sessions = StudioList(listOf(record)))
        assertEquals(
            DraftWritePlan.Unchanged(latest.header.revision),
            StudioJournalPolicy.save(latest, record, "duplicate"),
        )
        val changed = record.copy(work = sessionRecord(initial).work)
        assertEquals(
            DraftWriteIssue.CONFLICT,
            assertIs<DraftWritePlan.Rejected>(StudioJournalPolicy.save(latest, changed, "conflict")).issue,
        )
        assertEquals(
            DraftWriteIssue.STALE,
            assertIs<DraftWritePlan.Rejected>(
                StudioJournalPolicy.save(latest, record.copy(generation = 0), "old"),
            ).issue,
        )
    }

    @Test
    fun `a durable retire retains the journal payload and never changes accepted appearance`() {
        val initial = sessionDocument()
        val record = requireNotNull(editedSession(initial).record)
        val latest = initial.copy(sessions = StudioList(listOf(record)))
        val retired = record.copy(generation = 2, availability = SessionAvailability.RETIRED)
        val candidate = assertIs<DraftWritePlan.Write>(StudioJournalPolicy.save(latest, retired, "retire")).candidate
        assertEquals(latest.currents, candidate.currents)
        assertEquals(latest.runtime, candidate.runtime)
        assertEquals(record.work, candidate.sessions.single().work)
        assertEquals(
            candidate,
            assertIs<DecodeResult.Ready>(StudioStateCodec.decode(StudioStateCodec.encode(candidate))).document,
        )
    }
}
