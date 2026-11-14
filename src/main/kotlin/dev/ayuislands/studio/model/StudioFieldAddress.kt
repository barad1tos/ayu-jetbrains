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
