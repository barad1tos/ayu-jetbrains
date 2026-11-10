package dev.ayuislands.glow

/** Native progress is retained independently of the outer journal acknowledgement. */
internal enum class GlowCheckpointPhase { PREPARED, APPLYING, APPLIED, RESTORING, RESTORED, ACCEPTED }

/** A foreign field remains terminal even if its value later resembles an owned write. */
internal enum class GlowFieldState { PREPARED, WRITING, RESTORED, FOREIGN }
