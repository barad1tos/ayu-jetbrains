package dev.ayuislands.studio.model

internal sealed interface FieldPart {
    data object Value : FieldPart

    data class MapEntry(
        val key: String,
    ) : FieldPart

    data object MapOrder : FieldPart
}

internal data class FieldAddress(
    val field: LegacyFieldKey,
    val part: FieldPart = FieldPart.Value,
)

/** Final owner output, checked against the exact operation baseline before durable acceptance. */
internal data class RuntimeFieldChange(
    val address: FieldAddress,
    val before: FieldValue,
    val after: FieldValue,
)

internal sealed interface FieldValue {
    data object Absent : FieldValue

    data class Present(
        val value: RawValue,
    ) : FieldValue
}

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
