package dev.ayuislands.studio

import dev.ayuislands.studio.model.CloseRequest
import dev.ayuislands.studio.model.DraftWriteResult
import dev.ayuislands.studio.model.FieldAddress
import dev.ayuislands.studio.model.FieldValue
import dev.ayuislands.studio.model.LegacyFieldKey
import dev.ayuislands.studio.model.LegacyStore
import dev.ayuislands.studio.model.RawValue
import dev.ayuislands.studio.model.SessionCommand
import dev.ayuislands.studio.model.SessionEvent
import dev.ayuislands.studio.model.SessionIntent
import dev.ayuislands.studio.model.SessionJournalStatus
import dev.ayuislands.studio.model.SessionObservation
import dev.ayuislands.studio.model.SessionPhase
import dev.ayuislands.studio.model.StudioSessionState
import dev.ayuislands.studio.session.StudioSessionController
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class StudioSessionControllerTest {
    @Test
    fun `memory and attached preview update immediately while persistence is deferred`() {
        val fixture = controllerFixture()
        val rendered = mutableListOf<StudioSessionState>()
        fixture.controller.attach(rendered::add)
        fixture.controller.dispatch(edit(fixture.controller.state))
        val record = requireNotNull(fixture.controller.state.record)
        assertEquals(1, record.generation)
        assertEquals(record, rendered.last().record)
        assertTrue(fixture.writer.requests.isEmpty())
        assertTrue(fixture.commands.isEmpty())
        fixture.scheduler.advance(250)
        fixture.writer.complete(0, DraftWriteResult.Saved(5))
        assertEquals(SessionJournalStatus.Saved(1, 5), fixture.controller.state.journal)
    }

    @Test
    fun `retry of a failed exact generation can become saved without editing it again`() {
        val fixture = controllerFixture()
        fixture.controller.dispatch(edit(fixture.controller.state))
        fixture.scheduler.advance(250)
        fixture.writer.complete(0, DraftWriteResult.Failed("io"))
        assertIs<SessionJournalStatus.Failed>(fixture.controller.state.journal)
        val record = requireNotNull(fixture.controller.state.record)
        val results = mutableListOf<DraftWriteResult>()
        fixture.controller.flush(record, results::add)
        fixture.writer.complete(1, DraftWriteResult.Saved(6))
        assertEquals(SessionJournalStatus.Saved(record.generation, 6), fixture.controller.state.journal)
        assertEquals(record, fixture.controller.state.record)
        assertEquals(listOf<DraftWriteResult>(DraftWriteResult.Saved(6)), results)
    }

    @Test
    fun `detached view receives no callbacks while its app owned journal completes`() {
        val fixture = controllerFixture()
        val rendered = mutableListOf<StudioSessionState>()
        fixture.controller.attach(rendered::add)
        fixture.controller.dispatch(edit(fixture.controller.state))
        fixture.controller.detach()
        val count = rendered.size
        fixture.scheduler.advance(250)
        fixture.writer.complete(0, DraftWriteResult.Saved(7))
        assertEquals(count, rendered.size)
        assertEquals(SessionPhase.Editing, fixture.controller.state.phase)
        assertIs<SessionJournalStatus.Saved>(fixture.controller.state.journal)
        fixture.controller.attach(rendered::add)
        assertEquals(fixture.controller.state, rendered.last())
    }

    @Test
    fun `confirmed Cancel retires memory before an in flight save can complete`() {
        val fixture = controllerFixture()
        fixture.controller.dispatch(edit(fixture.controller.state))
        fixture.scheduler.advance(250)
        fixture.controller.dispatch(SessionEvent.HostClose(CloseRequest.CANCEL))
        val retired = fixture.controller.state
        assertEquals(SessionPhase.Retired, retired.phase)
        fixture.writer.complete(0, DraftWriteResult.Saved(2))
        fixture.writer.complete(1, DraftWriteResult.Failed("discard-unconfirmed"))
        assertEquals(retired, fixture.controller.state)
        assertEquals("discard-unconfirmed", assertIs<SessionCommand.DraftWriteFailed>(fixture.commands.single()).code)
    }

    @Test
    fun `Apply can flush a loaded draft without inventing an additional edit`() {
        val document = sessionDocument()
        val baseline = sessionState(document)
        val loaded = requireNotNull(editedSession(document).record)
        val writer = ManualSessionWriter()
        val commands = mutableListOf<SessionCommand>()
        val controller =
            StudioSessionController(
                baseline.copy(record = null, phase = SessionPhase.Loading),
                { sessionInputs(document, baseline) },
                ManualSessionScheduler(),
                writer,
                commands::add,
            )
        controller.dispatch(SessionEvent.Loaded(loaded))
        controller.dispatch(SessionEvent.AcceptRequested("apply-loaded"))
        assertIs<SessionCommand.Submit>(commands.single())
        val results = mutableListOf<DraftWriteResult>()
        controller.flush(loaded, results::add)
        writer.complete(0, DraftWriteResult.Saved(3))
        assertEquals(SessionJournalStatus.Saved(loaded.generation, 3), controller.state.journal)
        assertEquals(loaded, controller.state.record)
    }

    @Test
    fun `saved dirty session can flush after another accepted revision without losing its local work`() {
        var latest = sessionDocument()
        val initial = sessionState(latest)
        val scheduler = ManualSessionScheduler()
        val writer = ManualSessionWriter()
        val controller =
            StudioSessionController(
                initial,
                { state -> sessionInputs(latest, state) },
                scheduler,
                writer,
                {},
            )
        controller.dispatch(edit(controller.state))
        scheduler.advance(250)
        writer.complete(0, DraftWriteResult.Saved(1))
        val saved = requireNotNull(controller.state.record)
        latest = latest.copy(header = latest.header.copy(revision = 2, transactionId = "other-session"))

        controller.dispatch(SessionEvent.Observation(SessionObservation.IntentRevision(2)))
        val rebased = requireNotNull(controller.state.record)
        val results = mutableListOf<DraftWriteResult>()
        controller.flush(rebased, results::add)

        assertTrue(results.isEmpty(), "A rebase must not become a conflicting-generation flush failure")
        assertEquals(2, writer.requests.size)
        assertEquals(rebased, writer.requests.last().record)
        assertEquals(saved.generation + 1, rebased.generation)
        assertEquals(saved.work, rebased.work)
        assertEquals(2, rebased.baseDocumentRevision)
        writer.complete(1, DraftWriteResult.Saved(3))
        assertEquals(listOf<DraftWriteResult>(DraftWriteResult.Saved(3)), results)

        controller.dispatch(SessionEvent.Observation(SessionObservation.IntentRevision(2)))
        scheduler.advance(250)
        writer.complete(0, DraftWriteResult.Saved(1))
        assertEquals(rebased, controller.state.record)
        assertEquals(2, writer.requests.size)
        assertEquals(SessionJournalStatus.Saved(rebased.generation, 3), controller.state.journal)
    }

    private fun edit(state: StudioSessionState): SessionEvent {
        val record = requireNotNull(state.record)
        val address = FieldAddress(LegacyFieldKey(LegacyStore.SETTINGS, "glowEnabled"))
        val previous =
            record.work.drafts
                .single()
                .appearance.glow
                .value("glowEnabled") as RawValue.BooleanValue
        return SessionEvent.Intent(
            SessionIntent.EditField(
                record.work.selected,
                address,
                FieldValue.Present(RawValue.BooleanValue(!previous.value)),
            ),
        )
    }

    private fun controllerFixture(): ControllerFixture {
        val document = sessionDocument()
        val state = sessionState(document)
        val scheduler = ManualSessionScheduler()
        val writer = ManualSessionWriter()
        val commands = mutableListOf<SessionCommand>()
        val controller =
            StudioSessionController(state, { sessionInputs(document, state) }, scheduler, writer, commands::add)
        return ControllerFixture(controller, scheduler, writer, commands)
    }

    private data class ControllerFixture(
        val controller: StudioSessionController,
        val scheduler: ManualSessionScheduler,
        val writer: ManualSessionWriter,
        val commands: MutableList<SessionCommand>,
    )
}
