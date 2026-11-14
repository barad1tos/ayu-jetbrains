package dev.ayuislands.studio.fields

import dev.ayuislands.studio.model.FieldOrigin
import dev.ayuislands.studio.model.FieldPart
import dev.ayuislands.studio.model.FieldValue
import dev.ayuislands.studio.model.RawValue
import dev.ayuislands.studio.model.SourcePresence
import dev.ayuislands.studio.model.StudioField
import dev.ayuislands.studio.model.StudioList
import dev.ayuislands.studio.model.requireKnownType

internal object StudioFieldEdits {
    fun edit(
        field: StudioField,
        part: FieldPart,
        requested: FieldValue,
    ): StudioField {
        val raw =
            when (part) {
                FieldPart.Value ->
                    requireNotNull(
                        requested as? FieldValue.Present,
                    ) { "A scalar value cannot be removed" }.value
                is FieldPart.MapEntry ->
                    editEntry(
                        requireNotNull(field.value as? RawValue.OrderedMap) {
                            "Map address on scalar field"
                        },
                        part.key,
                        requested,
                    )
                FieldPart.MapOrder ->
                    reorder(
                        requireNotNull(field.value as? RawValue.OrderedMap) { "Map address on scalar field" },
                        requireNotNull((requested as? FieldValue.Present)?.value as? RawValue.OrderedSet) {
                            "Map order requires a key sequence"
                        },
                    )
            }
        val next =
            field.copy(
                value = raw,
                origin =
                    FieldOrigin(
                        if (raw ==
                            RawValue.Null
                        ) {
                            SourcePresence.NULL
                        } else {
                            SourcePresence.VALUE
                        },
                    ),
            )
        next.requireKnownType()
        return if (field.value == raw) field else next
    }

    private fun editEntry(
        map: RawValue.OrderedMap,
        key: String,
        requested: FieldValue,
    ): RawValue.OrderedMap {
        val entries =
            when (requested) {
                FieldValue.Absent -> map.entries.filterNot { it.key == key }
                is FieldValue.Present ->
                    if (map.entries.any { it.key == key }) {
                        map.entries.map { if (it.key == key) it.copy(value = requested.value) else it }
                    } else {
                        map.entries + RawValue.Entry(key, requested.value)
                    }
            }
        return RawValue.OrderedMap(StudioList(entries))
    }

    private fun reorder(
        map: RawValue.OrderedMap,
        order: RawValue.OrderedSet,
    ): RawValue.OrderedMap {
        require(order.values.size == map.entries.size && order.values.toSet() == map.entries.map { it.key }.toSet())
        return RawValue.OrderedMap(StudioList(order.values.map { key -> map.entries.single { it.key == key } }))
    }
}
