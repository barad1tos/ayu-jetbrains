package dev.ayuislands.studio.session

import dev.ayuislands.studio.model.ConflictChoice
import dev.ayuislands.studio.model.FieldAddress
import dev.ayuislands.studio.model.FieldConflict
import dev.ayuislands.studio.model.FieldOrigin
import dev.ayuislands.studio.model.FieldPart
import dev.ayuislands.studio.model.FieldResolution
import dev.ayuislands.studio.model.FieldValue
import dev.ayuislands.studio.model.FieldVersions
import dev.ayuislands.studio.model.FieldsMerge
import dev.ayuislands.studio.model.RawValue
import dev.ayuislands.studio.model.SourcePresence
import dev.ayuislands.studio.model.StudioFields
import dev.ayuislands.studio.model.StudioList

internal object StudioConflictPolicy {
    fun merge(
        base: StudioFields,
        mine: StudioFields,
        latest: StudioFields,
        touched: StudioList<FieldAddress>,
        resolutions: StudioList<FieldResolution> = StudioList(emptyList()),
    ): FieldsMerge {
        require(base.records.map { it.key }.toSet() == mine.records.map { it.key }.toSet())
        require(base.records.map { it.key }.toSet() == latest.records.map { it.key }.toSet())
        val conflicts = mutableListOf<FieldConflict>()
        val records =
            latest.records.map { current ->
                val addresses = touched.filter { it.field == current.key }
                if (addresses.isEmpty()) return@map current
                val old = base.records.single { it.key == current.key }
                val requested = mine.records.single { it.key == current.key }
                val value = mergeValue(FieldVersions(old, requested, current), addresses, resolutions, conflicts)
                if (value == current.value) current else current.copy(value = value, origin = editedOrigin(value))
            }
        return FieldsMerge(latest.copy(records = StudioList(records)), StudioList(conflicts))
    }

    private fun mergeValue(
        versions: FieldVersions,
        addresses: List<FieldAddress>,
        resolutions: List<FieldResolution>,
        conflicts: MutableList<FieldConflict>,
    ): RawValue {
        val (base, mine, latest) = versions
        val allMaps =
            base.value is RawValue.OrderedMap &&
                mine.value is RawValue.OrderedMap &&
                latest.value is RawValue.OrderedMap
        if (addresses.any { it.part == FieldPart.Value } || !allMaps) {
            val result =
                choose(
                    FieldConflict(
                        FieldAddress(base.key),
                        present(base.value),
                        present(latest.value),
                        present(mine.value),
                    ),
                    resolutions,
                    conflicts,
                )
            return (result as FieldValue.Present).value
        }
        return StudioMapMerge.merge(versions, addresses, resolutions, conflicts)
    }

    fun choose(
        conflict: FieldConflict,
        resolutions: List<FieldResolution>,
        conflicts: MutableList<FieldConflict>,
    ): FieldValue =
        when {
            conflict.mine == conflict.latest -> conflict.latest
            conflict.latest == conflict.base -> conflict.mine
            else -> confirmedChoice(conflict, resolutions, conflicts)
        }

    fun confirmedChoice(
        conflict: FieldConflict,
        resolutions: List<FieldResolution>,
        conflicts: MutableList<FieldConflict>,
    ): FieldValue {
        val resolution = resolutions.singleOrNull { it.conflict == conflict }
        return when (resolution?.choice) {
            ConflictChoice.USE_MINE -> conflict.mine
            ConflictChoice.KEEP_LATEST -> conflict.latest
            null -> {
                conflicts.add(conflict)
                conflict.latest
            }
        }
    }

    private fun present(value: RawValue): FieldValue = FieldValue.Present(value)

    private fun editedOrigin(value: RawValue): FieldOrigin =
        FieldOrigin(if (value == RawValue.Null) SourcePresence.NULL else SourcePresence.VALUE)
}
