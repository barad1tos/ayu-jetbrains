package dev.ayuislands.rotation

internal data class RotationIntent(
    val enabled: Boolean,
    val modeRaw: String?,
    val intervalHoursRaw: Int,
)
