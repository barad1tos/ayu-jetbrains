package dev.ayuislands.studio.fields

import dev.ayuislands.studio.model.FieldPart
import dev.ayuislands.studio.model.LegacyValueType
import dev.ayuislands.studio.model.RuntimeFieldChange
import dev.ayuislands.studio.model.StudioFieldCatalog
import dev.ayuislands.studio.model.StudioFieldOwner
import dev.ayuislands.studio.model.StudioList
import dev.ayuislands.studio.model.StudioRuntimeState

/** Applies exact native-owner output without normalizing unrelated runtime or legacy data. */
internal object StudioRuntimeFields {
    fun merge(
        runtime: StudioRuntimeState,
        changes: List<RuntimeFieldChange>,
    ): StudioRuntimeState {
        require(changes.map { it.address }.distinct().size == changes.size) { "Overlapping runtime output" }
        var records = runtime.legacy.records
        for ((address, before, after) in changes) {
            val definition =
                StudioFieldCatalog.fields.singleOrNull {
                    it.source == address.field.source && it.name == address.field.name
                }
            require(definition?.owner == StudioFieldOwner.RUNTIME) { "Output must name a known runtime field" }
            val allowed =
                when (address.part) {
                    FieldPart.Value -> definition.type != LegacyValueType.STRING_MAP
                    is FieldPart.MapEntry -> definition.type == LegacyValueType.STRING_MAP
                    FieldPart.MapOrder -> false
                }
            require(allowed) { "Runtime maps require individual key edits" }
            val field = requireNotNull(records.singleOrNull { it.key == address.field })
            require(StudioFieldValues.value(field, address.part) == before) { "Runtime output has a stale baseline" }
            val updated = StudioFieldEdits.edit(field, address.part, after)
            records = StudioList(records.map { if (it.key == address.field) updated else it })
        }
        return runtime.copy(legacy = runtime.legacy.copy(records = records))
    }
}
