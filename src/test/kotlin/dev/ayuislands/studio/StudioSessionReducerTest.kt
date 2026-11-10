package dev.ayuislands.studio

import dev.ayuislands.studio.model.AcceptanceContext
import dev.ayuislands.studio.model.AuthorityStatus
import dev.ayuislands.studio.model.CallbackStamp
import dev.ayuislands.studio.model.CapabilitySnapshot
import dev.ayuislands.studio.model.CloseRequest
import dev.ayuislands.studio.model.DraftId
import dev.ayuislands.studio.model.FieldAddress
import dev.ayuislands.studio.model.FieldValue
import dev.ayuislands.studio.model.FontAvailability
import dev.ayuislands.studio.model.FontObservation
import dev.ayuislands.studio.model.LegacyFieldKey
import dev.ayuislands.studio.model.LegacyStore
import dev.ayuislands.studio.model.OperationStage
import dev.ayuislands.studio.model.OperationStamp
import dev.ayuislands.studio.model.PreviewMode
import dev.ayuislands.studio.model.RawValue
import dev.ayuislands.studio.model.SessionCommand
import dev.ayuislands.studio.model.SessionEffect
import dev.ayuislands.studio.model.SessionEvent
import dev.ayuislands.studio.model.SessionHandling
import dev.ayuislands.studio.model.SessionIdentity
import dev.ayuislands.studio.model.SessionInputs
import dev.ayuislands.studio.model.SessionIntent
import dev.ayuislands.studio.model.SessionJournalEvent
import dev.ayuislands.studio.model.SessionJournalStatus
import dev.ayuislands.studio.model.SessionPhase
import dev.ayuislands.studio.model.StudioDocument
import dev.ayuislands.studio.model.StudioList
import dev.ayuislands.studio.model.StudioSessionState
import dev.ayuislands.studio.model.TargetObservation
import dev.ayuislands.studio.model.TargetPresence
import dev.ayuislands.studio.model.ViewAttachment
import dev.ayuislands.studio.session.StudioSessionReducer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class StudioSessionReducerTest {
    @Test
    fun `field edit changes the local draft immediately and queues only its durable journal`() {
        val document = sessionDocument()
        val state = state(document)
        val record = requireNotNull(state.record)
        val draft = record.work.drafts.single()
        val key = FieldAddress(LegacyFieldKey(LegacyStore.SETTINGS, "glowEnabled"))
        val value = draft.appearance.glow.value("glowEnabled") as RawValue.BooleanValue
        val event =
            SessionEvent.Intent(
                SessionIntent.EditField(draft.id, key, FieldValue.Present(RawValue.BooleanValue(!value.value))),
            )
        val transition = StudioSessionReducer.reduce(state, event, inputs(document, state))
        val changed = requireNotNull(transition.state.record)
        assertEquals(record.generation + 1, changed.generation)
        assertEquals(
            RawValue.BooleanValue(!value.value),
            changed.work.drafts
                .single()
                .appearance.glow
                .value("glowEnabled"),
        )
        assertEquals(PreviewMode.DRAFT, transition.state.view.preview)
        assertEquals(listOf<SessionCommand>(SessionCommand.SaveDraft(changed)), transition.commands)
        assertEquals(SessionJournalStatus.Pending(changed.generation), transition.state.journal)
    }

    @Test
    fun `profile selection is visible before Apply and preview toggle is presentation only`() {
        val document = sessionDocument()
        val state = state(document)
        val selected =
            StudioSessionReducer.reduce(
                state,
                SessionEvent.Intent(SessionIntent.Browse(document.catalog.first().id, DraftId("profile"))),
                inputs(document, state),
            )
        assertEquals(
            document.catalog.first().appearance,
            requireNotNull(selected.state.record)
                .work.drafts
                .last()
                .appearance,
        )
        assertTrue(selected.commands.all { it is SessionCommand.SaveDraft })
        val current =
            StudioSessionReducer.reduce(
                selected.state,
                SessionEvent.Intent(SessionIntent.Preview(PreviewMode.CURRENT)),
                inputs(document, selected.state),
            )
        assertEquals(PreviewMode.CURRENT, current.state.view.preview)
        assertEquals(selected.state.record, current.state.record)
        assertTrue(current.commands.isEmpty())
    }

    @Test
    fun `dispose detaches view while confirmed Cancel retires only unaccepted work`() {
        val document = sessionDocument()
        val state = state(document)
        val detached =
            StudioSessionReducer.reduce(
                state,
                SessionEvent.HostClose(CloseRequest.NONE),
                inputs(document, state),
            )
        assertEquals(SessionPhase.Editing, detached.state.phase)
        assertEquals(ViewAttachment.DETACHED, detached.state.view.attachment)
        assertTrue(detached.commands.isEmpty())
        val cancelled =
            StudioSessionReducer.reduce(
                state,
                SessionEvent.HostClose(CloseRequest.CANCEL),
                inputs(document, state),
            )
        assertEquals(SessionPhase.Retired, cancelled.state.phase)
        assertIs<SessionCommand.Retire>(cancelled.commands.single())
        val late =
            SessionEvent.JournalCompleted(
                CallbackStamp(OperationStamp(state.identity.id, 0, "save"), "done"),
                SessionJournalEvent.Saved(1),
            )
        assertEquals(
            cancelled.state,
            StudioSessionReducer.reduce(cancelled.state, late, inputs(document, cancelled.state)).state,
        )
    }

    @Test
    fun `accepted cleanup allows a new draft but defers save and Cancel never aborts accepted operation`() {
        val document = sessionDocument()
        val state =
            state(
                document,
            ).copy(
                phase =
                    SessionPhase.Processing(
                        OperationStage.ACCEPTED_CLEANUP,
                        OperationStamp("session", 0, "accepted"),
                    ),
            )
        val record = requireNotNull(state.record)
        val key = FieldAddress(LegacyFieldKey(LegacyStore.SETTINGS, "glowEnabled"))
        val value =
            record.work.drafts
                .single()
                .appearance.glow
                .value("glowEnabled") as RawValue.BooleanValue
        val event =
            SessionEvent.Intent(
                SessionIntent.EditField(
                    record.work.selected,
                    key,
                    FieldValue.Present(RawValue.BooleanValue(!value.value)),
                ),
            )
        val edited = StudioSessionReducer.reduce(state, event, inputs(document, state))
        assertTrue(edited.commands.isEmpty())
        assertIs<SessionJournalStatus.Pending>(edited.state.journal)
        val cancelled =
            StudioSessionReducer.reduce(
                edited.state,
                SessionEvent.HostClose(CloseRequest.CANCEL),
                inputs(document, edited.state),
            )
        assertEquals(SessionPhase.Retired, cancelled.state.phase)
        assertTrue(cancelled.commands.none { it is SessionCommand.Abort })
        assertEquals(
            document.currents.global.appearance,
            requireNotNull(cancelled.state.record)
                .work.drafts
                .single()
                .appearance,
        )
    }

    @Test
    fun `unknown matching callback is an invariant while recognized duplicates and stale callbacks are inert`() {
        val document = sessionDocument()
        val state = state(document)
        val callback = CallbackStamp(OperationStamp("session", 0, "unknown"), "prepared")
        val event = SessionEvent.EffectCompleted(callback, SessionEffect.Prepared)
        assertEquals(
            SessionHandling.INVARIANT,
            StudioSessionReducer.reduce(state, event, inputs(document, state)).handling,
        )
        val duplicate = state.copy(async = state.async.copy(completed = StudioList(listOf(callback))))
        assertEquals(duplicate, StudioSessionReducer.reduce(duplicate, event, inputs(document, duplicate)).state)
        val newer = state.copy(record = requireNotNull(state.record).copy(generation = 2))
        assertEquals(newer, StudioSessionReducer.reduce(newer, event, inputs(document, newer)).state)
    }

    private fun state(document: StudioDocument): StudioSessionState {
        val record = sessionRecord(document)
        return StudioSessionState(SessionIdentity(record.id, record.target, 1), record, SessionPhase.Editing)
    }

    private fun inputs(
        document: StudioDocument,
        state: StudioSessionState,
    ): SessionInputs =
        SessionInputs(
            document,
            AcceptanceContext(
                AuthorityStatus.READY,
                TargetObservation(state.identity.target, 1, TargetPresence.ACTIVE),
                CapabilitySnapshot(1, StudioList(emptyList())),
                FontObservation(
                    requireNotNull(state.record)
                        .work.drafts
                        .first()
                        .appearance.font,
                    FontAvailability.AVAILABLE,
                ),
            ),
        )
}
