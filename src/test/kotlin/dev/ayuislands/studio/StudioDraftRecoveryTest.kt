package dev.ayuislands.studio

import dev.ayuislands.studio.model.DraftRestore
import dev.ayuislands.studio.model.DraftWriteIssue
import dev.ayuislands.studio.model.DraftWritePlan
import dev.ayuislands.studio.model.SessionAvailability
import dev.ayuislands.studio.model.StudioList
import dev.ayuislands.studio.model.StudioProjectKey
import dev.ayuislands.studio.model.StudioProjectRecord
import dev.ayuislands.studio.model.StudioTarget
import dev.ayuislands.studio.model.TargetObservation
import dev.ayuislands.studio.model.TargetPresence
import dev.ayuislands.studio.session.StudioDraftRecovery
import dev.ayuislands.studio.session.StudioJournalPolicy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class StudioDraftRecoveryTest {
    @Test
    fun `restart offers unattached work in retained order without selecting or applying it`() {
        val document = catalogTestDocument()
        val orphan = editedJournalRecord(document)
        val recovered = orphan.copy(id = "recovered", availability = SessionAvailability.RECOVERED)
        val retired = orphan.copy(id = "retired", availability = SessionAvailability.RETIRED)
        val attached = orphan.copy(id = "attached")
        val latest = document.copy(sessions = StudioList(listOf(recovered, attached, orphan, retired)))
        assertEquals(listOf(recovered, orphan), StudioDraftRecovery.offers(latest, setOf("attached")))
        assertEquals(document.currents, latest.currents)
        assertEquals(document.runtime, latest.runtime)
        assertEquals(orphan, latest.sessions[2])
    }

    @Test
    fun `Restore reopens draft work for an inactive project without accepting any changes`() {
        val initial = catalogTestDocument()
        val target = StudioTarget.Project(StudioProjectKey("workspace"))
        val project = StudioProjectRecord(target.key, "Workspace", initial.currents.global)
        val orphan = editedJournalRecord(initial).copy(target = target)
        val document =
            initial.copy(
                currents = initial.currents.copy(projects = StudioList(listOf(project))),
                sessions = StudioList(listOf(orphan)),
            )
        val request =
            DraftRestore(orphan.id, "restored", "restore-1", TargetObservation(target, 7, TargetPresence.INACTIVE))
        val candidate =
            assertIs<DraftWritePlan.Write>(
                StudioDraftRecovery.restore(document, request, emptySet()),
            ).candidate
        val restored = candidate.sessions.last()
        assertEquals("restored", restored.id)
        assertEquals(0, restored.generation)
        assertEquals(SessionAvailability.LIVE, restored.availability)
        assertEquals(orphan.work, restored.work)
        assertEquals(orphan.target, restored.target)
        assertEquals(orphan.baseDocumentRevision, restored.baseDocumentRevision)
        assertEquals(SessionAvailability.RETIRED, candidate.sessions.first().availability)
        assertEquals(orphan.generation + 1, candidate.sessions.first().generation)
        assertEquals(document, candidate.copy(header = document.header, sessions = document.sessions))
        assertTrue(StudioDraftRecovery.offers(candidate, setOf(restored.id)).isEmpty())
    }

    @Test
    fun `Restore cannot steal an attached session or replace an unavailable captured target`() {
        val initial = catalogTestDocument()
        val orphan = editedJournalRecord(initial)
        val document = initial.copy(sessions = StudioList(listOf(orphan)))
        val request =
            DraftRestore(orphan.id, "restored", "restore-2", TargetObservation(orphan.target, 1, TargetPresence.ACTIVE))
        assertEquals(
            DraftWriteIssue.CONFLICT,
            assertIs<DraftWritePlan.Rejected>(StudioDraftRecovery.restore(document, request, setOf(orphan.id))).issue,
        )
        val unavailable = request.copy(target = request.target.copy(presence = TargetPresence.UNAVAILABLE))
        assertEquals(
            DraftWriteIssue.TARGET_UNAVAILABLE,
            assertIs<DraftWritePlan.Rejected>(StudioDraftRecovery.restore(document, unavailable, emptySet())).issue,
        )
        assertEquals(orphan, document.sessions.single())
        assertEquals(
            DraftWriteIssue.MISSING,
            assertIs<DraftWritePlan.Rejected>(StudioDraftRecovery.restore(initial, request, emptySet())).issue,
        )
    }

    @Test
    fun `explicit discard persists a watermark and late autosave cannot restore the orphan`() {
        val initial = catalogTestDocument()
        val orphan = editedJournalRecord(initial)
        val document = initial.copy(sessions = StudioList(listOf(orphan)))
        val discarded =
            assertIs<DraftWritePlan.Write>(
                StudioDraftRecovery.discard(document, orphan.id, "discard", emptySet()),
            ).candidate
        assertTrue(StudioDraftRecovery.offers(discarded, emptySet()).isEmpty())
        assertEquals(
            DraftWriteIssue.RETIRED,
            assertIs<DraftWritePlan.Rejected>(StudioJournalPolicy.save(discarded, orphan, "late")).issue,
        )
        assertEquals(document.currents, discarded.currents)
        assertEquals(document.runtime, discarded.runtime)
        assertEquals(orphan.work, discarded.sessions.single().work)
    }
}
