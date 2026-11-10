package dev.ayuislands.studio

import dev.ayuislands.studio.model.CloseRequest
import dev.ayuislands.studio.model.SessionAvailability
import dev.ayuislands.studio.model.SessionCommand
import dev.ayuislands.studio.model.SessionEvent
import dev.ayuislands.studio.model.SessionIntent
import dev.ayuislands.studio.model.SessionObservation
import dev.ayuislands.studio.model.SessionPhase
import dev.ayuislands.studio.model.StudioList
import dev.ayuislands.studio.model.StudioProjectKey
import dev.ayuislands.studio.model.StudioTarget
import dev.ayuislands.studio.session.StudioSessionReducer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class StudioSessionBoundaryTest {
    @Test
    fun `an observation can invalidate review but can never confirm it`() {
        val document = sessionDocument()
        val editing = editedSession(document)
        val reviewing = editing.copy(phase = SessionPhase.Reviewing("review", StudioList(emptyList())))
        val result =
            StudioSessionReducer.reduce(
                reviewing,
                SessionEvent.Observation(SessionObservation.IntentRevision(document.header.revision)),
                sessionInputs(document, reviewing),
            )
        assertTrue(result.commands.none { it is SessionCommand.Submit })
        assertTrue(result.state.phase == SessionPhase.Editing || result.state.phase is SessionPhase.Reviewing)
        assertEquals(
            editing.record
                ?.work
                ?.drafts
                ?.single()
                ?.appearance,
            result.state.record
                ?.work
                ?.drafts
                ?.single()
                ?.appearance,
        )
    }

    @Test
    fun `Cancel remains safe when the captured target is no longer in the latest document`() {
        val document = sessionDocument()
        val editing = editedSession(document)
        val target = StudioTarget.Project(StudioProjectKey("missing"))
        val state =
            editing.copy(
                identity = editing.identity.copy(target = target),
                record = requireNotNull(editing.record).copy(target = target),
            )
        val result =
            StudioSessionReducer.reduce(
                state,
                SessionEvent.HostClose(CloseRequest.CANCEL),
                sessionInputs(document, state),
            )
        assertEquals(SessionPhase.Retired, result.state.phase)
        val retired = assertIs<SessionCommand.Retire>(result.commands.single()).record
        assertEquals(target, retired.target)
        assertEquals(
            document.currents.global.appearance,
            retired.work.drafts
                .single()
                .appearance,
        )
        assertEquals(SessionAvailability.RETIRED, retired.availability)
    }

    @Test
    fun `shutdown quarantines dirty work while explicit Reset reloads accepted data`() {
        val document = sessionDocument()
        val editing = editedSession(document)
        val closed =
            StudioSessionReducer.reduce(
                editing,
                SessionEvent.HostClose(CloseRequest.SHUTDOWN),
                sessionInputs(document, editing),
            )
        val recovered = assertIs<SessionCommand.Quarantine>(closed.commands.single()).record
        assertEquals(SessionAvailability.RECOVERED, recovered.availability)
        assertEquals(editing.record?.work, recovered.work)
        val reset =
            StudioSessionReducer.reduce(
                editing,
                SessionEvent.Intent(SessionIntent.Reset),
                sessionInputs(document, editing),
            )
        assertEquals(
            document.currents.global.appearance,
            reset.state.record
                ?.work
                ?.drafts
                ?.single()
                ?.appearance,
        )
        assertTrue(requireNotNull(reset.state.record).work.catalog.isEmpty())
        assertIs<SessionCommand.SaveDraft>(reset.commands.single())
    }
}
