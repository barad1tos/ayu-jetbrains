package dev.ayuislands.studio.model

/** Acknowledged application facts; the session reducer remains the phase authority. */
internal sealed interface StudioApplyUpdate {
    val operationId: String
    val step: String

    data class Effect(
        override val operationId: String,
        override val step: String,
        val result: SessionEffect,
    ) : StudioApplyUpdate

    data class Journal(
        override val operationId: String,
        override val step: String,
        val result: SessionJournalEvent,
    ) : StudioApplyUpdate

    data class Committed(
        override val operationId: String,
        val document: StudioDocument,
    ) : StudioApplyUpdate {
        override val step: String get() = "committed"
    }
}
