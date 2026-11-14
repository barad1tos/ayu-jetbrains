package dev.ayuislands.studio.fields

import dev.ayuislands.studio.model.FieldPart
import dev.ayuislands.studio.model.FieldValue
import dev.ayuislands.studio.model.RawValue
import dev.ayuislands.studio.model.StudioField
import dev.ayuislands.studio.model.StudioList

internal object StudioFieldValues {
    fun hasExplicitOrder(
        original: RawValue.OrderedMap,
        edited: RawValue.OrderedMap,
    ): Boolean {
        val before = original.entries.map { it.key }
        val after = edited.entries.map { it.key }
        val defaultOrder = before.filter { it in after } + after.filterNot { it in before }
        return after != defaultOrder
    }

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
}
