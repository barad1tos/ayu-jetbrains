package dev.ayuislands.studio

import dev.ayuislands.studio.model.AcceptanceChoices
import dev.ayuislands.studio.model.CallbackStamp
import dev.ayuislands.studio.model.CloseRequest
import dev.ayuislands.studio.model.FontAction
import dev.ayuislands.studio.model.FontJob
import dev.ayuislands.studio.model.FontJobStatus
import dev.ayuislands.studio.model.OperationStage
import dev.ayuislands.studio.model.OperationStamp
import dev.ayuislands.studio.model.PreviewMode
import dev.ayuislands.studio.model.SessionAsync
import dev.ayuislands.studio.model.SessionEffect
import dev.ayuislands.studio.model.SessionEvent
import dev.ayuislands.studio.model.SessionHandling
import dev.ayuislands.studio.model.SessionIntent
import dev.ayuislands.studio.model.SessionJournalEvent
import dev.ayuislands.studio.model.SessionObservation
import dev.ayuislands.studio.model.SessionPhase
import dev.ayuislands.studio.model.StudioList
import dev.ayuislands.studio.session.StudioSessionReducer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StudioSessionMatrixTest {
    @Test
    fun `every session phase dispatches every sealed event family explicitly`() {
        val document = sessionDocument()
        val editing = editedSession(document)
        val record = requireNotNull(editing.record)
        val operation = OperationStamp(record.id, record.generation, "operation")
        val phases =
            buildList {
                add(SessionPhase.Loading)
                add(SessionPhase.Editing)
                add(SessionPhase.Reviewing("review", StudioList(emptyList())))
                OperationStage.entries.forEach { add(SessionPhase.Processing(it, operation)) }
                add(SessionPhase.Retired)
            }
        val handled = SessionHandling.HANDLED
        val rejected = SessionHandling.REJECTED
        val invariant = SessionHandling.INVARIANT
        val ignored = SessionHandling.IGNORED
        val expected =
            listOf(
                listOf(handled, rejected, rejected, rejected, handled, handled, invariant, handled, rejected, handled),
                listOf(invariant, handled, handled, rejected, handled, handled, handled, handled, rejected, handled),
                listOf(invariant, handled, rejected, handled, handled, handled, handled, handled, rejected, handled),
                listOf(invariant, rejected, rejected, rejected, handled, handled, handled, handled, rejected, handled),
                listOf(invariant, rejected, rejected, rejected, handled, handled, handled, handled, rejected, handled),
                listOf(invariant, rejected, rejected, rejected, handled, handled, invariant, handled, handled, handled),
                listOf(invariant, rejected, rejected, rejected, handled, handled, handled, handled, handled, handled),
                listOf(invariant, handled, handled, rejected, handled, handled, handled, handled, handled, handled),
                listOf(invariant, handled, rejected, rejected, handled, handled, handled, handled, handled, handled),
                listOf(invariant, rejected, rejected, rejected, handled, ignored, ignored, ignored, handled, handled),
            )
        assertEquals(10, phases.size)
        phases.forEachIndexed { index, phase ->
            val state =
                editing.copy(
                    phase = phase,
                    async =
                        SessionAsync(
                            jobs = StudioList(listOf(FontJob(operation, "font", FontAction.INSTALL))),
                            journals = StudioList(listOf(operation)),
                        ),
                )
            val events =
                listOf(
                    SessionEvent.Loaded(record),
                    SessionEvent.Intent(SessionIntent.Preview(PreviewMode.DRAFT)),
                    SessionEvent.AcceptRequested("accept"),
                    SessionEvent.ReviewResolved("confirmed-review", AcceptanceChoices()),
                    SessionEvent.HostClose(CloseRequest.NONE),
                    SessionEvent.Observation(SessionObservation.IntentRevision(document.header.revision)),
                    SessionEvent.EffectCompleted(CallbackStamp(operation, "effect"), effect(phase)),
                    SessionEvent.JournalCompleted(CallbackStamp(operation, "journal"), journal(phase)),
                    SessionEvent.RetryRecovery(operation),
                    SessionEvent.AttachUi,
                )
            assertEquals(10, events.size)
            events.forEachIndexed { eventIndex, event ->
                val result = StudioSessionReducer.reduce(state, event, sessionInputs(document, state))
                assertEquals(expected[index][eventIndex], result.handling, "$phase / $event")
                if (result.handling != handled) assertTrue(result.commands.isEmpty(), "$phase / $event")
            }
        }
    }

    private fun effect(phase: SessionPhase): SessionEffect =
        when (phase) {
            SessionPhase.Loading, SessionPhase.Retired -> SessionEffect.Prepared
            SessionPhase.Editing, is SessionPhase.Reviewing -> SessionEffect.ResourceFinished(FontJobStatus.INSTALLED)
            is SessionPhase.Processing ->
                when (phase.stage) {
                    OperationStage.PREPARING, OperationStage.RESOLVING_COMMIT -> SessionEffect.Prepared
                    OperationStage.APPLYING -> SessionEffect.SurfacesSettled
                    OperationStage.ROLLING_BACK, OperationStage.RECOVERY_REQUIRED -> SessionEffect.RollbackSettled
                    OperationStage.ACCEPTED_CLEANUP -> SessionEffect.CleanupSettled
                }
        }

    private fun journal(phase: SessionPhase): SessionJournalEvent =
        when (phase) {
            SessionPhase.Loading -> SessionJournalEvent.LoadProgress
            SessionPhase.Editing, is SessionPhase.Reviewing, SessionPhase.Retired -> SessionJournalEvent.Saved(1)
            is SessionPhase.Processing ->
                when (phase.stage) {
                    OperationStage.PREPARING -> SessionJournalEvent.Prepared
                    OperationStage.APPLYING -> SessionJournalEvent.ReceiptSaved
                    OperationStage.RESOLVING_COMMIT, OperationStage.RECOVERY_REQUIRED ->
                        SessionJournalEvent.OutcomeUnknown
                    OperationStage.ROLLING_BACK -> SessionJournalEvent.RecoverySaved
                    OperationStage.ACCEPTED_CLEANUP -> SessionJournalEvent.CleanupSaved
                }
        }
}
