package dev.ayuislands.studio.model

internal enum class TargetPresence { ACTIVE, INACTIVE, UNAVAILABLE }

internal data class TargetObservation(
    val target: StudioTarget,
    val generation: Long,
    val presence: TargetPresence,
)
