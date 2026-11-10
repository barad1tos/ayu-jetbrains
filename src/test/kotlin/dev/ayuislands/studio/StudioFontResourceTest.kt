package dev.ayuislands.studio

import dev.ayuislands.studio.model.CallbackStamp
import dev.ayuislands.studio.model.CloseRequest
import dev.ayuislands.studio.model.DraftId
import dev.ayuislands.studio.model.FontAction
import dev.ayuislands.studio.model.FontJob
import dev.ayuislands.studio.model.FontJobStatus
import dev.ayuislands.studio.model.OperationStamp
import dev.ayuislands.studio.model.SessionCommand
import dev.ayuislands.studio.model.SessionEffect
import dev.ayuislands.studio.model.SessionEvent
import dev.ayuislands.studio.model.SessionIntent
import dev.ayuislands.studio.model.SessionNotice
import dev.ayuislands.studio.session.StudioSessionReducer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class StudioFontResourceTest {
    @Test
    fun `every explicit resource outcome retains requested appearance and permits a fresh retry`() {
        val document = sessionDocument()
        val state = sessionState(document)
        val cases =
            listOf(
                FontAction.INSTALL to FontJobStatus.INSTALLED,
                FontAction.INSTALL to FontJobStatus.DECLINED,
                FontAction.INSTALL to FontJobStatus.FAILED,
                FontAction.INSTALL to FontJobStatus.CANCELLED,
                FontAction.REINSTALL to FontJobStatus.RESTART_REQUIRED,
                FontAction.UNINSTALL to FontJobStatus.REMOVED,
                FontAction.UNINSTALL to FontJobStatus.PARTIAL,
            )
        cases.forEach { (action, status) ->
            val job = FontJob(OperationStamp("session", 0, "resource"), "curated", action)
            val started =
                StudioSessionReducer.reduce(
                    state,
                    SessionEvent.Intent(SessionIntent.Resource(job)),
                    sessionInputs(document, state),
                )
            assertEquals(job, assertIs<SessionCommand.StartFontJob>(started.commands.single()).job)
            val completion =
                SessionEvent.EffectCompleted(
                    CallbackStamp(job.operation, "finished"),
                    SessionEffect.ResourceFinished(status),
                )
            val finished =
                StudioSessionReducer.reduce(
                    started.state,
                    completion,
                    sessionInputs(document, started.state),
                )
            assertEquals(state.record, finished.state.record)
            assertEquals(SessionNotice.Resource("curated", status), finished.state.notice)
            assertTrue(
                finished.state.async.jobs
                    .isEmpty(),
            )
            val duplicate =
                StudioSessionReducer.reduce(
                    finished.state,
                    completion,
                    sessionInputs(document, finished.state),
                )
            assertEquals(finished.state, duplicate.state)
            val retry =
                StudioSessionReducer.reduce(
                    finished.state,
                    SessionEvent.Intent(SessionIntent.Resource(job.copy(operation = job.operation.copy(id = "retry")))),
                    sessionInputs(document, finished.state),
                )
            assertIs<SessionCommand.StartFontJob>(retry.commands.single())
        }
    }

    @Test
    fun `completion after browsing another profile or Cancel never changes a draft`() {
        val document = sessionDocument()
        val state = sessionState(document)
        val job = FontJob(OperationStamp("session", 0, "install"), "curated", FontAction.INSTALL)
        val started =
            StudioSessionReducer
                .reduce(
                    state,
                    SessionEvent.Intent(SessionIntent.Resource(job)),
                    sessionInputs(document, state),
                ).state
        val browsing =
            StudioSessionReducer
                .reduce(
                    started,
                    SessionEvent.Intent(SessionIntent.Browse(document.catalog.first().id, DraftId("profile"))),
                    sessionInputs(document, started),
                ).state
        val completion =
            SessionEvent.EffectCompleted(
                CallbackStamp(job.operation, "finished"),
                SessionEffect.ResourceFinished(FontJobStatus.INSTALLED),
            )
        val finished = StudioSessionReducer.reduce(browsing, completion, sessionInputs(document, browsing))
        assertEquals(browsing.record, finished.state.record)
        val retired =
            StudioSessionReducer
                .reduce(
                    browsing,
                    SessionEvent.HostClose(CloseRequest.CANCEL),
                    sessionInputs(document, browsing),
                ).state
        val late = StudioSessionReducer.reduce(retired, completion, sessionInputs(document, retired))
        assertEquals(retired, late.state)
        assertTrue(late.commands.isEmpty())
    }
}
