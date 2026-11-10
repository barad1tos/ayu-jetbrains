package dev.ayuislands.font

/** Only an absent identity may be adopted; this record never carries unrelated scheme metadata. */
internal data class FontIdentityRecord(
    val id: String,
    val applyPhase: FontWritePhase,
    val restoreState: FontRestoreState,
)
