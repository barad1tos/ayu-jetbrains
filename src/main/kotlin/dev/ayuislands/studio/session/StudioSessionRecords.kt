package dev.ayuislands.studio.session

import dev.ayuislands.studio.model.DraftId
import dev.ayuislands.studio.model.DraftRecord
import dev.ayuislands.studio.model.SessionAvailability
import dev.ayuislands.studio.model.SessionWork
import dev.ayuislands.studio.model.StudioDocument
import dev.ayuislands.studio.model.StudioList
import dev.ayuislands.studio.model.StudioSessionRecord
import dev.ayuislands.studio.model.StudioTarget

/** Creates an independent clean record from accepted intent without registering a view or writing a journal. */
internal object StudioSessionRecords {
    fun current(
        document: StudioDocument,
        target: StudioTarget,
        sessionId: String,
        draftId: DraftId,
    ): StudioSessionRecord? {
        val current = StudioTargets.current(document, target) ?: return null
        val name = document.catalog.singleOrNull { it.id == current.source?.id }?.name
        val draft = DraftRecord(draftId, current.source, current, current.appearance, sourceName = name)
        return StudioSessionRecord(
            sessionId,
            0,
            target,
            document.header.revision,
            SessionWork(StudioList(listOf(draft)), draft.id, StudioList(emptyList()), StudioList(emptyList())),
            SessionAvailability.LIVE,
        )
    }
}
