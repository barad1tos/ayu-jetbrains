package dev.ayuislands.studio.persistence

import dev.ayuislands.studio.model.StudioBytes
import dev.ayuislands.studio.model.StudioDocument
import dev.ayuislands.studio.model.StudioList

internal data class PendingJournal(
    val token: PreparedToken,
    val base: StudioBytes?,
    val candidate: StudioDocument,
    val recovery: RecoveryPlan,
    val receipts: StudioList<PortableReceipt>,
)
