package dev.ayuislands.accent

/** Native ownership outcome; outer acceptance still determines when rollback may be released. */
internal enum class AccentWriteState { PREPARED, OWNED, RELINQUISHED, RESTORED }

/** A prepared native cell supports acknowledgement before its one-shot setter. */
internal interface AccentWriteCheckpoint : AccentCheckpoint {
    val state: AccentWriteState

    fun apply(): Boolean
}
