package dev.ayuislands.studio.session

import dev.ayuislands.studio.model.FieldAddress
import dev.ayuislands.studio.model.FieldConflict
import dev.ayuislands.studio.model.FieldPart
import dev.ayuislands.studio.model.FieldResolution
import dev.ayuislands.studio.model.FieldValue
import dev.ayuislands.studio.model.FieldVersions
import dev.ayuislands.studio.model.RawValue
import dev.ayuislands.studio.model.StudioList

internal object StudioMapMerge {
    fun merge(
        versions: FieldVersions,
        addresses: List<FieldAddress>,
        resolutions: List<FieldResolution>,
        conflicts: MutableList<FieldConflict>,
    ): RawValue.OrderedMap {
        val (base, mine, latest) = versions
        val old = (base.value as RawValue.OrderedMap).entries
        val requested = (mine.value as RawValue.OrderedMap).entries
        val current = (latest.value as RawValue.OrderedMap).entries
        val values = current.associateTo(linkedMapOf()) { it.key to it.value }
        val keys = addresses.mapNotNull { (it.part as? FieldPart.MapEntry)?.key }.toSet()
        val inOrder =
            requested.map { it.key }.filter { it in keys } + keys.filter { key -> requested.none { it.key == key } }
        for (key in inOrder) {
            val result =
                StudioConflictPolicy.choose(
                    FieldConflict(
                        FieldAddress(base.key, FieldPart.MapEntry(key)),
                        value(old, key),
                        value(current, key),
                        value(requested, key),
                    ),
                    resolutions,
                    conflicts,
                )
            when (result) {
                FieldValue.Absent -> values.remove(key)
                is FieldValue.Present -> values[key] = result.value
            }
        }
        val order = mergeOrder(versions, addresses, resolutions, conflicts)
        val mergedKeys = order.filter { it in values } + values.keys.filter { it !in order }
        return RawValue.OrderedMap(StudioList(mergedKeys.map { RawValue.Entry(it, values.getValue(it)) }))
    }

    private fun mergeOrder(
        versions: FieldVersions,
        addresses: List<FieldAddress>,
        resolutions: List<FieldResolution>,
        conflicts: MutableList<FieldConflict>,
    ): List<String> {
        val (base, mine, latest) = versions
        val old = (base.value as RawValue.OrderedMap).entries.map { it.key }
        val requested = (mine.value as RawValue.OrderedMap).entries.map { it.key }
        val current = (latest.value as RawValue.OrderedMap).entries.map { it.key }
        if (requested == current) return current
        val localOrder = addresses.any { it.part == FieldPart.MapOrder }
        val externalOrder = old.filter { it in current } != current.filter { it in old }
        val concurrentOrder = (externalOrder && mine.value != base.value) || (localOrder && current != old)
        if (!concurrentOrder) return if (localOrder) requested else current
        val conflict =
            FieldConflict(
                FieldAddress(base.key, FieldPart.MapOrder),
                ordered(old),
                ordered(current),
                ordered(requested),
            )
        val chosen = StudioConflictPolicy.confirmedChoice(conflict, resolutions, conflicts) as FieldValue.Present
        return (chosen.value as RawValue.OrderedSet).values
    }

    private fun ordered(keys: List<String>): FieldValue = FieldValue.Present(RawValue.OrderedSet(StudioList(keys)))

    private fun value(
        entries: List<RawValue.Entry>,
        key: String,
    ): FieldValue = entries.singleOrNull { it.key == key }?.let { FieldValue.Present(it.value) } ?: FieldValue.Absent
}
