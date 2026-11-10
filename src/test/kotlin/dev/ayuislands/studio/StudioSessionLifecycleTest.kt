package dev.ayuislands.studio

import dev.ayuislands.studio.fields.StudioAppearanceFields
import dev.ayuislands.studio.fields.StudioFieldEdits
import dev.ayuislands.studio.model.CallbackStamp
import dev.ayuislands.studio.model.CloseRequest
import dev.ayuislands.studio.model.EffectOwner
import dev.ayuislands.studio.model.FieldAddress
import dev.ayuislands.studio.model.FieldPart
import dev.ayuislands.studio.model.FieldValue
import dev.ayuislands.studio.model.LegacyFieldKey
import dev.ayuislands.studio.model.LegacyStore
import dev.ayuislands.studio.model.NativeSurfaceChange
import dev.ayuislands.studio.model.OperationStage
import dev.ayuislands.studio.model.OperationStamp
import dev.ayuislands.studio.model.RawValue
import dev.ayuislands.studio.model.SessionCommand
import dev.ayuislands.studio.model.SessionEffect
import dev.ayuislands.studio.model.SessionEvent
import dev.ayuislands.studio.model.SessionJournalEvent
import dev.ayuislands.studio.model.SessionJournalStatus
import dev.ayuislands.studio.model.SessionObservation
import dev.ayuislands.studio.model.SessionPhase
import dev.ayuislands.studio.model.StudioList
import dev.ayuislands.studio.session.StudioSessionReducer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class StudioSessionLifecycleTest {
    @Test
    fun `unknown durable decision never infers rollback and explicit resolution selects cleanup or restore`() {
        val document = sessionDocument()
        val editing = editedSession(document)
        val operation = OperationStamp("session", requireNotNull(editing.record).generation, "apply")
        val resolving = editing.copy(phase = SessionPhase.Processing(OperationStage.RESOLVING_COMMIT, operation))
        val unknown =
            StudioSessionReducer.reduce(
                resolving,
                SessionEvent.JournalCompleted(
                    CallbackStamp(operation, "resolve-1"),
                    SessionJournalEvent.OutcomeUnknown,
                ),
                sessionInputs(document, resolving),
            )
        assertEquals(OperationStage.RECOVERY_REQUIRED, assertIs<SessionPhase.Processing>(unknown.state.phase).stage)
        assertTrue(unknown.commands.isEmpty())
        val committed =
            StudioSessionReducer.reduce(
                unknown.state,
                SessionEvent.JournalCompleted(
                    CallbackStamp(operation, "resolve-2"),
                    SessionJournalEvent.Committed(requireNotNull(editing.record)),
                ),
                sessionInputs(document, unknown.state),
            )
        assertEquals(OperationStage.ACCEPTED_CLEANUP, assertIs<SessionPhase.Processing>(committed.state.phase).stage)
        val old =
            StudioSessionReducer.reduce(
                unknown.state,
                SessionEvent.JournalCompleted(
                    CallbackStamp(operation, "resolve-2"),
                    SessionJournalEvent.NotCommitted("cancelled"),
                ),
                sessionInputs(document, unknown.state),
            )
        assertEquals(OperationStage.ROLLING_BACK, assertIs<SessionPhase.Processing>(old.state.phase).stage)
    }

    @Test
    fun `cleanup completion belongs to the accepted operation despite newer local generation`() {
        val document = sessionDocument()
        val editing = editedSession(document)
        val operation = OperationStamp("session", 0, "accepted")
        val cleanup = editing.copy(phase = SessionPhase.Processing(OperationStage.ACCEPTED_CLEANUP, operation))
        val result =
            StudioSessionReducer.reduce(
                cleanup,
                SessionEvent.EffectCompleted(CallbackStamp(operation, "cleanup"), SessionEffect.CleanupSettled),
                sessionInputs(document, cleanup),
            )
        assertEquals(SessionPhase.Editing, result.state.phase)
        assertEquals(editing.record, result.state.record)
        assertEquals(
            requireNotNull(editing.record),
            assertIs<SessionCommand.SaveDraft>(result.commands.single()).record,
        )
    }

    @Test
    fun `an earlier autosave acknowledgement cannot mark a later edit saved`() {
        val document = sessionDocument()
        val editing = editedSession(document)
        val record = requireNotNull(editing.record)
        val older = OperationStamp(record.id, record.generation - 1, "old-save")
        val state = editing.copy(async = editing.async.copy(journals = StudioList(editing.async.journals + older)))
        val result =
            StudioSessionReducer.reduce(
                state,
                SessionEvent.JournalCompleted(CallbackStamp(older, "saved"), SessionJournalEvent.Saved(9)),
                sessionInputs(document, state),
            )
        assertEquals(editing.record, result.state.record)
        assertEquals(SessionJournalStatus.Pending(record.generation), result.state.journal)
    }

    @Test
    fun `native surface observation changes provenance only and cannot rewrite profile intent`() {
        val document = sessionDocument()
        val state = editedSession(document)
        val change =
            NativeSurfaceChange(
                EffectOwner.FONT,
                "editor.size",
                FieldValue.Present(RawValue.Integer(12)),
                FieldValue.Present(RawValue.Integer(14)),
                FieldValue.Present(RawValue.Integer(16)),
            )
        val result =
            StudioSessionReducer.reduce(
                state,
                SessionEvent.Observation(SessionObservation.NativeSurface(change)),
                sessionInputs(document, state),
            )
        assertEquals(state.record, result.state.record)
        assertEquals(listOf(change), result.state.view.native)
        assertTrue(result.commands.isEmpty())
    }

    @Test
    fun `external accepted fields rebase untouched preferences but retain a conflicting local request`() {
        val document = sessionDocument()
        val editing = sessionState(document)
        val record = requireNotNull(editing.record)
        val localKey = FieldAddress(LegacyFieldKey(LegacyStore.SETTINGS, "softIntensity"))
        val changed =
            StudioSessionReducer
                .reduce(
                    editing,
                    SessionEvent.Intent(
                        dev.ayuislands.studio.model.SessionIntent.EditField(
                            record.work.selected,
                            localKey,
                            FieldValue.Present(RawValue.Integer(37)),
                        ),
                    ),
                    sessionInputs(document, editing),
                ).state
        var external = document.currents.global.appearance
        for ((name, value) in listOf(
            "softIntensity" to RawValue.Integer(62),
            "glowEnabled" to RawValue.BooleanValue(false),
        )) {
            val field = StudioAppearanceFields.field(external, LegacyFieldKey(LegacyStore.SETTINGS, name))
            external =
                StudioAppearanceFields.replace(
                    external,
                    StudioFieldEdits.edit(field, FieldPart.Value, FieldValue.Present(value)),
                )
        }
        val latest =
            document.copy(
                header = document.header.copy(revision = document.header.revision + 1),
                currents = document.currents.copy(global = document.currents.global.copy(appearance = external)),
            )
        val result =
            StudioSessionReducer.reduce(
                changed,
                SessionEvent.Observation(SessionObservation.IntentRevision(latest.header.revision)),
                sessionInputs(latest, changed),
            )
        val draft = requireNotNull(result.state.record).work.drafts.single()
        assertEquals(RawValue.Integer(37), draft.appearance.glow.value("softIntensity"))
        assertEquals(RawValue.BooleanValue(false), draft.appearance.glow.value("glowEnabled"))
        assertEquals(
            record.work.drafts
                .single()
                .base.appearance.glow
                .value("softIntensity"),
            draft.base.appearance.glow
                .value("softIntensity"),
        )
        val requested =
            StudioSessionReducer.reduce(
                result.state,
                SessionEvent.AcceptRequested("apply"),
                sessionInputs(latest, result.state),
            )
        assertIs<SessionPhase.Reviewing>(requested.state.phase)
    }

    @Test
    fun `Cancel after uncertain close preserves recovery payload until outcome is known`() {
        val document = sessionDocument()
        val editing = editedSession(document)
        val operation = OperationStamp("session", requireNotNull(editing.record).generation, "apply")
        val state = editing.copy(phase = SessionPhase.Processing(OperationStage.RESOLVING_COMMIT, operation))
        val closed =
            StudioSessionReducer.reduce(
                state,
                SessionEvent.HostClose(CloseRequest.CANCEL),
                sessionInputs(document, state),
            )
        assertEquals(OperationStage.RESOLVING_COMMIT, assertIs<SessionPhase.Processing>(closed.state.phase).stage)
        assertTrue(closed.commands.isEmpty())
        assertEquals(editing.record, closed.state.record)
        val restored =
            StudioSessionReducer.reduce(
                closed.state.copy(
                    phase = SessionPhase.Processing(OperationStage.ROLLING_BACK, operation, CloseRequest.CANCEL),
                ),
                SessionEvent.EffectCompleted(CallbackStamp(operation, "restored"), SessionEffect.RollbackSettled),
                sessionInputs(document, closed.state),
            )
        assertEquals(SessionPhase.Retired, restored.state.phase)
        assertIs<SessionCommand.Retire>(restored.commands.single())
    }
}
