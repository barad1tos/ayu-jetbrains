package dev.ayuislands.studio

import dev.ayuislands.studio.model.AcceptanceBlock
import dev.ayuislands.studio.model.AcceptanceChoices
import dev.ayuislands.studio.model.AcceptancePlan
import dev.ayuislands.studio.model.AcceptancePlanResult
import dev.ayuislands.studio.model.AcceptanceReview
import dev.ayuislands.studio.model.CallbackStamp
import dev.ayuislands.studio.model.DraftId
import dev.ayuislands.studio.model.OperationStage
import dev.ayuislands.studio.model.OperationStamp
import dev.ayuislands.studio.model.SessionCommand
import dev.ayuislands.studio.model.SessionEffect
import dev.ayuislands.studio.model.SessionEvent
import dev.ayuislands.studio.model.SessionJournalEvent
import dev.ayuislands.studio.model.SessionNotice
import dev.ayuislands.studio.model.SessionPhase
import dev.ayuislands.studio.model.StudioList
import dev.ayuislands.studio.session.StudioSessionReducer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class StudioSessionPreparationTest {
    @Test
    fun `confirmed review uses a fresh acceptance identity instead of replaying the reviewed request`() {
        val document = sessionDocument()
        val editing = editedSession(document)
        val reviewing = editing.copy(phase = SessionPhase.Reviewing("first", StudioList(emptyList())))
        val transition =
            StudioSessionReducer.reduce(
                reviewing,
                SessionEvent.ReviewResolved("confirmed", AcceptanceChoices()),
                sessionInputs(document, reviewing),
            )
        assertEquals("confirmed", assertIs<SessionCommand.Submit>(transition.commands.single()).request.transactionId)
    }

    @Test
    fun `authoritative preflight can return structured review or blocker after preparation began`() {
        val document = sessionDocument()
        val editing = editedSession(document)
        val operation = OperationStamp("session", requireNotNull(editing.record).generation, "apply")
        val preparing = editing.copy(phase = SessionPhase.Processing(OperationStage.PREPARING, operation))
        val review =
            AcceptancePlanResult.ReviewRequired(
                StudioList(listOf(AcceptanceReview.HiddenDraft(DraftId("hidden")))),
            )
        val blocked = AcceptancePlanResult.Blocked(StudioList(listOf(AcceptanceBlock.READ_ONLY)))
        for (result in listOf(review, blocked, AcceptancePlanResult.Ready(AcceptancePlan.Unchanged))) {
            val transition =
                StudioSessionReducer.reduce(
                    preparing,
                    SessionEvent.EffectCompleted(
                        CallbackStamp(operation, "preflight"),
                        SessionEffect.PreflightCompleted(result),
                    ),
                    sessionInputs(document, preparing),
                )
            assertEquals(preparing.record, transition.state.record)
            assertTrue(transition.commands.isEmpty())
            when (result) {
                is AcceptancePlanResult.ReviewRequired ->
                    assertEquals(SessionPhase.Reviewing("apply", review.items), transition.state.phase)
                is AcceptancePlanResult.Blocked -> {
                    assertEquals(SessionPhase.Editing, transition.state.phase)
                    assertEquals(SessionNotice.Blocked(blocked.reasons), transition.state.notice)
                }
                is AcceptancePlanResult.Ready -> {
                    assertEquals(SessionPhase.Editing, transition.state.phase)
                    assertEquals(SessionNotice.NoChanges, transition.state.notice)
                }
            }
        }
    }

    @Test
    fun `lost prepare acknowledgement enters recovery without pretending that effects started`() {
        val document = sessionDocument()
        val editing = editedSession(document)
        val operation = OperationStamp("session", requireNotNull(editing.record).generation, "apply")
        val preparing = editing.copy(phase = SessionPhase.Processing(OperationStage.PREPARING, operation))
        val transition =
            StudioSessionReducer.reduce(
                preparing,
                SessionEvent.JournalCompleted(CallbackStamp(operation, "unknown"), SessionJournalEvent.OutcomeUnknown),
                sessionInputs(document, preparing),
            )
        assertEquals(OperationStage.RECOVERY_REQUIRED, assertIs<SessionPhase.Processing>(transition.state.phase).stage)
        assertEquals(preparing.record, transition.state.record)
    }
}
