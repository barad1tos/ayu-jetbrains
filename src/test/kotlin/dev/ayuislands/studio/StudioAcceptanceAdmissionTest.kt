package dev.ayuislands.studio

import dev.ayuislands.studio.model.AcceptanceBlock
import dev.ayuislands.studio.model.AcceptanceObservation
import dev.ayuislands.studio.model.AuthorityStatus
import dev.ayuislands.studio.model.SessionAvailability
import dev.ayuislands.studio.model.SharedIntent
import dev.ayuislands.studio.model.StudioList
import dev.ayuislands.studio.model.StudioProjectKey
import dev.ayuislands.studio.model.StudioProjectRecord
import dev.ayuislands.studio.model.StudioTarget
import dev.ayuislands.studio.model.SystemAccentPolicy
import dev.ayuislands.studio.model.TargetObservation
import dev.ayuislands.studio.model.TargetPresence
import dev.ayuislands.studio.session.StudioAcceptanceAdmission
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class StudioAcceptanceAdmissionTest {
    @Test
    fun `store recovery takes precedence over a missing project`() {
        val document = catalogTestDocument()
        val target = StudioTarget.Project(StudioProjectKey("closed"))
        val session = journalRecord(document).copy(target = target)
        val afterClose = TargetObservation(target, 0, TargetPresence.UNAVAILABLE)
        assertEquals(
            AcceptanceBlock.RECOVERY,
            StudioAcceptanceAdmission.block(
                session,
                1,
                document,
                AcceptanceObservation(AuthorityStatus.RECOVERY, afterClose),
            ),
        )
        assertEquals(
            AcceptanceBlock.TARGET_UNAVAILABLE,
            StudioAcceptanceAdmission.block(
                session,
                1,
                document,
                AcceptanceObservation(AuthorityStatus.READY, afterClose),
            ),
        )
    }

    @Test
    fun `reopening a project with the same key cannot apply an old draft`() {
        val initial = catalogTestDocument()
        val target = StudioTarget.Project(StudioProjectKey("same"))
        val project = StudioProjectRecord(target.key, "Project", initial.currents.global)
        val document = initial.copy(currents = initial.currents.copy(projects = StudioList(listOf(project))))
        val session = journalRecord(document).copy(target = target)
        val reopened = TargetObservation(target, 2, TargetPresence.ACTIVE)
        assertEquals(
            AcceptanceBlock.TARGET_REPLACED,
            StudioAcceptanceAdmission.block(
                session,
                1,
                document,
                AcceptanceObservation(AuthorityStatus.READY, reopened),
            ),
        )
        assertNull(
            StudioAcceptanceAdmission.block(
                session,
                2,
                document,
                AcceptanceObservation(AuthorityStatus.READY, reopened),
            ),
        )
    }

    @Test
    fun `inactive project accepts appearance edits but blocks a combined shared change`() {
        val initial = catalogTestDocument()
        val target = StudioTarget.Project(StudioProjectKey("inactive"))
        val project = StudioProjectRecord(target.key, "Project", initial.currents.global)
        val document = initial.copy(currents = initial.currents.copy(projects = StudioList(listOf(project))))
        val draft = editedJournalRecord(document).copy(target = target)
        val inactive = TargetObservation(target, 3, TargetPresence.INACTIVE)
        assertNull(
            StudioAcceptanceAdmission.block(draft, 3, document, AcceptanceObservation(AuthorityStatus.READY, inactive)),
        )
        val shared = SharedIntent.SystemPolicy(SystemAccentPolicy.BY_PROFILE, SystemAccentPolicy.FORCE_ON)
        val combined = draft.copy(work = draft.work.copy(shared = StudioList(listOf(shared))))
        assertEquals(
            AcceptanceBlock.ACTIVATE_TARGET,
            StudioAcceptanceAdmission.block(
                combined,
                3,
                document,
                AcceptanceObservation(AuthorityStatus.READY, inactive),
            ),
        )
    }

    @Test
    fun `a durable retirement or newer saved edits invalidate a queued Apply`() {
        val initial = catalogTestDocument()
        val requested = editedJournalRecord(initial)
        val target = TargetObservation(requested.target, 1, TargetPresence.ACTIVE)
        val retired = requested.copy(generation = requested.generation + 1, availability = SessionAvailability.RETIRED)
        val retiredDocument = initial.copy(sessions = StudioList(listOf(retired)))
        assertEquals(
            AcceptanceBlock.STALE_SESSION,
            StudioAcceptanceAdmission.block(
                requested,
                1,
                retiredDocument,
                AcceptanceObservation(AuthorityStatus.READY, target),
            ),
        )
        val newer = requested.copy(generation = requested.generation + 1)
        val latest = initial.copy(sessions = StudioList(listOf(newer)))
        assertEquals(
            AcceptanceBlock.STALE_SESSION,
            StudioAcceptanceAdmission.block(requested, 1, latest, AcceptanceObservation(AuthorityStatus.READY, target)),
        )
        assertNull(
            StudioAcceptanceAdmission.block(newer, 1, latest, AcceptanceObservation(AuthorityStatus.READY, target)),
        )
    }
}
