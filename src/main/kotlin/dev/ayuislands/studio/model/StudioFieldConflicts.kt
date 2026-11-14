package dev.ayuislands.studio.model

internal data class FieldConflict(
    val address: FieldAddress,
    val base: FieldValue,
    val latest: FieldValue,
    val mine: FieldValue,
)

internal enum class ConflictChoice { KEEP_LATEST, USE_MINE }

/** A choice authorizes only the exact values reviewed, not later changes at the same address. */
internal data class FieldResolution(
    val conflict: FieldConflict,
    val choice: ConflictChoice,
)

internal data class FieldsMerge(
    val fields: StudioFields,
    val conflicts: StudioList<FieldConflict>,
)

internal data class FieldVersions(
    val base: StudioField,
    val mine: StudioField,
    val latest: StudioField,
)
