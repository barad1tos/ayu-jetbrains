package dev.ayuislands.studio.session

import dev.ayuislands.studio.model.AcceptanceContext
import dev.ayuislands.studio.model.AcceptanceRequest
import dev.ayuislands.studio.model.DraftWriteResult
import dev.ayuislands.studio.model.StudioApplyUpdate
import dev.ayuislands.studio.model.StudioDocument
import dev.ayuislands.studio.model.StudioSessionRecord

/** Session delivery and draft persistence retain their existing dispatcher and store ownership. */
internal interface StudioSessionIo {
    suspend fun publish(update: StudioApplyUpdate)

    suspend fun flush(record: StudioSessionRecord): DraftWriteResult

    suspend fun observe(
        request: AcceptanceRequest,
        document: StudioDocument,
    ): AcceptanceContext
}
