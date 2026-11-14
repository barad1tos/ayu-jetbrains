package dev.ayuislands.rotation

/** Meaning of the accepted user command, independent of its UI entrypoint. */
internal enum class RotationAcceptanceOrigin {
    EXPLICIT_ROTATION_ENABLE,
    LOCAL_ROTATION_EDIT,
    LOCAL_APPEARANCE_EDIT,
    PROFILE_ACTIVATION,
    SHARED_POLICY_EDIT,
    CATALOG_ONLY,
    MIGRATION,
}
