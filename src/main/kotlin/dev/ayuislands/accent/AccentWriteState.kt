package dev.ayuislands.accent

/** Native ownership outcome; outer acceptance still determines when rollback may be released. */
internal enum class AccentWriteState { PREPARED, OWNED, RELINQUISHED, RESTORED }
