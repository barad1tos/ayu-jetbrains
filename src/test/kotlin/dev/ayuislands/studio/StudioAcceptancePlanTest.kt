package dev.ayuislands.studio

import dev.ayuislands.rotation.RotationAcceptanceOrigin
import dev.ayuislands.studio.model.AcceptanceObservation
import dev.ayuislands.studio.model.AcceptancePlan
import dev.ayuislands.studio.model.ApplyContext
import dev.ayuislands.studio.model.ApplyRequest
import dev.ayuislands.studio.model.AuthorityStatus
import dev.ayuislands.studio.model.CatalogCommand
import dev.ayuislands.studio.model.CatalogStage
import dev.ayuislands.studio.model.DraftId
import dev.ayuislands.studio.model.DraftTracking
import dev.ayuislands.studio.model.EffectObservation
import dev.ayuislands.studio.model.EffectOwner
import dev.ayuislands.studio.model.FieldAddress
import dev.ayuislands.studio.model.FieldValue
import dev.ayuislands.studio.model.LegacyFieldKey
import dev.ayuislands.studio.model.LegacyStore
import dev.ayuislands.studio.model.ProfileId
import dev.ayuislands.studio.model.RawValue
import dev.ayuislands.studio.model.SessionAvailability
import dev.ayuislands.studio.model.SharedIntent
import dev.ayuislands.studio.model.StudioDocument
import dev.ayuislands.studio.model.StudioList
import dev.ayuislands.studio.model.StudioSessionRecord
import dev.ayuislands.studio.model.TargetObservation
import dev.ayuislands.studio.model.TargetPresence
import dev.ayuislands.studio.persistence.DecodeResult
import dev.ayuislands.studio.persistence.StudioStateCodec
import dev.ayuislands.studio.session.StudioAcceptancePlanner
import dev.ayuislands.studio.session.StudioApplyCandidate
import dev.ayuislands.studio.session.StudioCatalogPolicy
import dev.ayuislands.studio.session.StudioDraftPolicy
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class StudioAcceptancePlanTest {
    @Test
    fun `clean Apply has no durable operation`() {
        val latest = catalogTestDocument()
        assertEquals(AcceptancePlan.NoChanges, plan(latest, journalRecord(latest)))
    }

    @Test
    fun `one prepared snapshot advances Current shared settings and the submitting draft together`() {
        val original = catalogTestDocument()
        val edited = editedJournalRecord(original)
        val field =
            original.shared.workspace.records
                .single { it.key.name == "hideEditorVScrollbar" }
        val after = field.copy(value = RawValue.BooleanValue(!(field.value as RawValue.BooleanValue).value))
        val session =
            edited.copy(
                work = edited.work.copy(shared = StudioList(listOf(SharedIntent.Field(field, after)))),
            )
        val recovery = edited.copy(id = "other", availability = SessionAvailability.RECOVERED)
        val latest = original.copy(sessions = StudioList(listOf(recovery, session)))

        val accepted = assertIs<AcceptancePlan.Commit>(plan(latest, session))
        assertEquals(latest.header.revision, accepted.lease.documentRevision)
        assertEquals(latest.header.revision + 1, accepted.document.header.revision)
        assertEquals(transaction.toString(), accepted.document.header.transactionId)
        assertEquals(listOf(EffectOwner.GLOW, EffectOwner.WORKSPACE), accepted.route.owners)
        assertEquals(session.target, accepted.target)
        assertEquals(recovery, accepted.document.sessions.first())
        val saved = accepted.document.sessions.last()
        assertEquals(session.id, saved.id)
        assertEquals(session.generation + 1, saved.generation)
        assertEquals(accepted.document.header.revision, saved.baseDocumentRevision)
        assertFalse(StudioApplyCandidate.hasPending(saved.work))
        assertEquals(
            after.value,
            accepted.document.shared.workspace.records
                .single { it.key == field.key }
                .value,
        )
        val current = accepted.document.currents.global
        assertEquals(
            edited.work.drafts
                .single()
                .appearance.glow,
            current.appearance.glow,
        )
        assertEquals(
            current,
            saved.work.drafts
                .single()
                .base,
        )
        assertEquals(
            current.appearance,
            saved.work.drafts
                .single()
                .appearance,
        )
        val decoded = assertIs<DecodeResult.Ready>(StudioStateCodec.decode(StudioStateCodec.encode(accepted.document)))
        assertEquals(accepted.document, decoded.document)
    }

    @Test
    fun `saving a hidden draft clears its edits without activating it or touching untouched drafts`() {
        val opened = catalogTestDocument()
        val current = journalRecord(opened)
        val hidden =
            editedJournalRecord(opened)
                .work.drafts
                .single()
                .copy(id = DraftId("hidden"))
        val acceptedElsewhere = opened.currents.global.copy(appearance = hidden.appearance, revision = 1)
        val latest = opened.copy(currents = opened.currents.copy(global = acceptedElsewhere))
        val untouched =
            current.work.drafts
                .single()
                .copy(id = DraftId("untouched"))
        val work = current.work.copy(drafts = StudioList(listOf(hidden, current.work.drafts.single(), untouched)))
        val command = CatalogCommand.Create(hidden.id, ProfileId("saved"), "Saved appearance")
        val staged = assertIs<CatalogStage.Staged>(StudioCatalogPolicy.stage(latest, work, command)).work
        val session = current.copy(work = staged)

        val accepted = assertIs<AcceptancePlan.Commit>(plan(latest, session))
        assertEquals(latest.currents, accepted.document.currents)
        assertEquals(
            hidden.appearance,
            accepted.document.catalog
                .last()
                .appearance,
        )
        assertTrue(accepted.route.owners.isEmpty())
        val finalized =
            accepted.document.sessions
                .single()
                .work
        assertEquals(work.selected, finalized.selected)
        assertEquals(work.drafts.map { it.id }, finalized.drafts.map { it.id })
        assertFalse(StudioApplyCandidate.hasPending(finalized))
        assertFalse(StudioDraftPolicy.isDirty(finalized.drafts.first()))
        assertEquals(DraftTracking(hidden.appearance), finalized.drafts.first().tracking)
        assertEquals(hidden.appearance, finalized.drafts.first().appearance)
        assertEquals(current.work.drafts.single(), finalized.drafts.single { it.id == finalized.selected })
        assertEquals(untouched, finalized.drafts.last())
    }

    @Test
    fun `a reverted rotation edit does not tag a later workspace Apply`() {
        val latest = catalogTestDocument()
        val current = journalRecord(latest)
        val draft = current.work.drafts.single()
        val key = LegacyFieldKey(LegacyStore.SETTINGS, "accentRotationEnabled")
        val rotation =
            draft.appearance.accent.records
                .single { it.key == key }
                .value as RawValue.BooleanValue
        val address = FieldAddress(key)
        val changed =
            StudioDraftPolicy.edit(
                draft,
                address,
                FieldValue.Present(RawValue.BooleanValue(!rotation.value)),
                RotationAcceptanceOrigin.LOCAL_ROTATION_EDIT,
            )
        val reverted = StudioDraftPolicy.edit(changed, address, FieldValue.Present(rotation))
        assertFalse(StudioDraftPolicy.isDirty(reverted))
        val width =
            latest.shared.workspace.records
                .single { it.key.name == "hideEditorVScrollbar" }
        val after = width.copy(value = RawValue.BooleanValue(!(width.value as RawValue.BooleanValue).value))
        val work =
            current.work.copy(
                drafts = StudioList(listOf(reverted)),
                shared = StudioList(listOf(SharedIntent.Field(width, after))),
            )

        val accepted = assertIs<AcceptancePlan.Commit>(plan(latest, current.copy(work = work)))
        assertEquals(null, accepted.rotationOrigin)
        assertEquals(listOf(EffectOwner.WORKSPACE), accepted.route.owners)
    }

    @Test
    fun `pending review leaves the exact draft and staged work eligible for retry`() {
        val latest = catalogTestDocument()
        val edited = editedJournalRecord(latest)
        val selected =
            journalRecord(latest)
                .work.drafts
                .single()
                .copy(id = DraftId("current"))
        val session =
            edited.copy(
                work = edited.work.copy(drafts = StudioList(edited.work.drafts + selected), selected = selected.id),
            )

        val review = assertIs<AcceptancePlan.Review>(plan(latest, session))
        assertEquals(1, review.items.size)
        assertTrue(StudioApplyCandidate.hasPending(session.work))
        assertTrue(latest.sessions.isEmpty())
    }

    private fun plan(
        latest: StudioDocument,
        session: StudioSessionRecord,
    ): AcceptancePlan =
        StudioAcceptancePlanner.plan(
            ApplyRequest(session, 1),
            latest,
            ApplyContext(
                AcceptanceObservation(
                    AuthorityStatus.READY,
                    TargetObservation(session.target, 1, TargetPresence.ACTIVE),
                ),
                StudioList(emptyList()),
            ),
            EffectObservation(1),
            transaction,
        )

    private val transaction = UUID.fromString("9e1bbb9a-7376-451d-9f0f-4897c1142add")
}
