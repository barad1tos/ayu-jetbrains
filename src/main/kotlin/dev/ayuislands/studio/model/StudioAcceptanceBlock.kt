package dev.ayuislands.studio.model

internal enum class AuthorityStatus { READY, READ_ONLY, RECOVERY }

internal data class AcceptanceObservation(
    val authority: AuthorityStatus,
    val target: TargetObservation,
)

internal enum class AcceptanceBlock {
    INVALID_REQUEST,
    READ_ONLY,
    RECOVERY,
    TARGET_UNAVAILABLE,
    TARGET_REPLACED,
    ACTIVATE_TARGET,
    STALE_SESSION,
}
