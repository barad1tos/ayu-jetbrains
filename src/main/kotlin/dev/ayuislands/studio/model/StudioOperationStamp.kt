package dev.ayuislands.studio.model

internal data class OperationStamp(
    val session: String,
    val generation: Long,
    val id: String,
)

internal data class CallbackStamp(
    val operation: OperationStamp,
    val step: String,
)
