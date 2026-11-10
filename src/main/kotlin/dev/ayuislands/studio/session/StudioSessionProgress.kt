package dev.ayuislands.studio.session

import dev.ayuislands.studio.model.CallbackStamp
import dev.ayuislands.studio.model.SessionEvent
import dev.ayuislands.studio.model.SessionJournalEvent
import dev.ayuislands.studio.model.SessionPhase
import dev.ayuislands.studio.model.StudioApplyUpdate
import dev.ayuislands.studio.model.StudioDocument

/** Routes application facts to the current operation without reviving detached or retired sessions. */
internal class StudioSessionProgress(
    private val controller: StudioSessionController,
    private val accepted: (StudioDocument) -> Unit,
) {
    fun receive(update: StudioApplyUpdate) {
        if (update is StudioApplyUpdate.Committed) accepted(update.document)
        val phase = controller.state.phase as? SessionPhase.Processing ?: return
        if (phase.operation.id != update.operationId) return
        val stamp = CallbackStamp(phase.operation, update.step)
        val event =
            when (update) {
                is StudioApplyUpdate.Effect -> SessionEvent.EffectCompleted(stamp, update.result)
                is StudioApplyUpdate.Journal -> SessionEvent.JournalCompleted(stamp, update.result)
                is StudioApplyUpdate.Committed -> {
                    val record = update.document.sessions.single { it.id == phase.operation.session }
                    SessionEvent.JournalCompleted(stamp, SessionJournalEvent.Committed(record))
                }
            }
        controller.dispatch(event)
    }
}
