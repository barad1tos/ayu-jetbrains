package dev.ayuislands.studio.model

internal sealed interface FieldValue {
    data object Absent : FieldValue

    data class Present(
        val value: RawValue,
    ) : FieldValue
}
