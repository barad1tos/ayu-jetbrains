package dev.ayuislands.studio

import dev.ayuislands.studio.model.CallbackStamp
import dev.ayuislands.studio.model.FieldAddress
import dev.ayuislands.studio.model.FieldValue
import dev.ayuislands.studio.model.LegacyFieldKey
import dev.ayuislands.studio.model.LegacyStore
import dev.ayuislands.studio.model.OperationStamp
import dev.ayuislands.studio.model.RawValue
import dev.ayuislands.studio.model.SessionEvent
import dev.ayuislands.studio.model.SessionHandling
import dev.ayuislands.studio.model.SessionIntent
import dev.ayuislands.studio.model.SessionJournalEvent
import dev.ayuislands.studio.session.StudioSessionReducer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StudioSessionCoalescingTest {
    @Test
    fun `coalesced generations retain only the current acknowledgement authority`() {
        val document = sessionDocument()
        var state = sessionState(document)
        val selected = requireNotNull(state.record).work.selected
        val key = FieldAddress(LegacyFieldKey(LegacyStore.SETTINGS, "softIntensity"))
        repeat(50) { step ->
            state =
                StudioSessionReducer
                    .reduce(
                        state,
                        SessionEvent.Intent(
                            SessionIntent.EditField(selected, key, FieldValue.Present(RawValue.Integer(100 + step))),
                        ),
                        sessionInputs(document, state),
                    ).state
        }
        val generation = requireNotNull(state.record).generation
        assertEquals(listOf(generation), state.async.journals.map { it.generation })
        val old = CallbackStamp(OperationStamp(state.identity.id, 1, "journal:1"), "saved")
        val late =
            StudioSessionReducer.reduce(
                state,
                SessionEvent.JournalCompleted(old, SessionJournalEvent.Saved(1)),
                sessionInputs(document, state),
            )
        assertEquals(SessionHandling.IGNORED, late.handling)
        assertEquals(state, late.state)
    }

    @Test
    fun `completed autosave history expires when a newer semantic generation begins`() {
        val document = sessionDocument()
        var state = sessionState(document)
        val selected = requireNotNull(state.record).work.selected
        val key = FieldAddress(LegacyFieldKey(LegacyStore.SETTINGS, "softIntensity"))
        repeat(10) { step ->
            state =
                StudioSessionReducer
                    .reduce(
                        state,
                        SessionEvent.Intent(
                            SessionIntent.EditField(selected, key, FieldValue.Present(RawValue.Integer(100 + step))),
                        ),
                        sessionInputs(document, state),
                    ).state
            assertTrue(state.async.completed.isEmpty())
            val operation = state.async.journals.single()
            state =
                StudioSessionReducer
                    .reduce(
                        state,
                        SessionEvent.JournalCompleted(
                            CallbackStamp(operation, "saved"),
                            SessionJournalEvent.Saved(step.toLong()),
                        ),
                        sessionInputs(document, state),
                    ).state
            assertEquals(1, state.async.completed.size)
        }
    }
}
