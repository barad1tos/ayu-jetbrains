package dev.ayuislands.studio.fields

import dev.ayuislands.studio.model.FieldPart
import dev.ayuislands.studio.model.FieldValue
import dev.ayuislands.studio.model.LegacyFieldKey
import dev.ayuislands.studio.model.LegacyValueType
import dev.ayuislands.studio.model.RawValue
import dev.ayuislands.studio.model.StudioField
import dev.ayuislands.studio.model.StudioFieldCatalog
import dev.ayuislands.studio.model.StudioList

internal object StudioFieldValues {
    private val fieldTypes = StudioFieldCatalog.fields.associate { LegacyFieldKey(it.source, it.name) to it.type }

    fun value(
        field: StudioField,
        part: FieldPart,
    ): FieldValue {
        val raw = field.value
        return when (part) {
            FieldPart.Value -> FieldValue.Present(raw)
            is FieldPart.MapEntry ->
                (raw as RawValue.OrderedMap).entries.singleOrNull { it.key == part.key }?.let {
                    FieldValue.Present(it.value)
                } ?: FieldValue.Absent
            FieldPart.MapOrder ->
                FieldValue.Present(RawValue.OrderedSet(StudioList((raw as RawValue.OrderedMap).entries.map { it.key })))
        }
    }

    fun validateField(field: StudioField) {
        val type = requireNotNull(fieldTypes[field.key]) { "Unknown field requires an opaque extension" }
        val value = field.value
        val valid =
            when (type) {
                LegacyValueType.TEXT -> value is RawValue.Text || value == RawValue.Null
                LegacyValueType.BOOLEAN -> value is RawValue.BooleanValue
                LegacyValueType.INTEGER -> value is RawValue.Integer
                LegacyValueType.LONG -> value is RawValue.LongValue
                LegacyValueType.FLOAT -> value is RawValue.FloatBits
                LegacyValueType.STRING_MAP ->
                    value is RawValue.OrderedMap &&
                        value.entries.all { it.value is RawValue.Text }
                LegacyValueType.STRING_SET -> value is RawValue.OrderedSet
            }
        require(valid) { "Incorrect field value type" }
    }
}
