package dev.ayuislands.studio.persistence

import dev.ayuislands.studio.fields.StudioAppearanceFields
import dev.ayuislands.studio.fields.StudioFieldValues
import dev.ayuislands.studio.model.LegacyStore
import dev.ayuislands.studio.model.RawValue
import dev.ayuislands.studio.model.StudioDocument
import dev.ayuislands.studio.model.StudioField
import dev.ayuislands.studio.model.StudioFieldCatalog
import dev.ayuislands.studio.model.StudioTarget
import dev.ayuislands.studio.session.StudioTargets
import java.util.Collections

/** Immutable legacy-shaped reads for one explicitly selected Current and accepted document generation. */
internal class StudioLegacyView(
    val settings: AyuSettingsView,
    val syntax: SyntaxStateView,
    val mappings: AccentMappingsView,
)

/** Pure projection only. Publishing accepted authority remains the outer durable transaction's responsibility. */
internal object StudioLegacyProjection {
    fun project(
        document: StudioDocument,
        target: StudioTarget,
    ): StudioLegacyView? {
        val appearance = StudioTargets.current(document, target)?.appearance ?: return null
        val records =
            StudioAppearanceFields.fields(appearance) +
                listOf(
                    document.shared.plugins,
                    document.shared.workspace,
                    document.shared.system,
                    document.shared.mappings,
                    document.auxiliary.ui,
                    document.auxiliary.resources,
                    document.auxiliary.legacyMarkers,
                    document.runtime.legacy,
                ).flatMap { it.records }
        return StudioLegacyView(
            AyuSettingsView(
                LegacyValues(LegacyStore.SETTINGS, records),
                appearance,
                document.shared.systemAccentPolicy,
            ),
            SyntaxStateView(LegacyValues(LegacyStore.SYNTAX, records), appearance),
            AccentMappingsView(LegacyValues(LegacyStore.MAPPINGS, records)),
        )
    }
}

/** Typed reads reuse the canonical legacy field catalog; no defaults or normalization occur here. */
internal class LegacyValues(
    source: LegacyStore,
    records: List<StudioField>,
) {
    private val fields = records.filter { it.key.source == source }
    private val values = fields.associate { it.key.name to it.value }

    init {
        val expected =
            StudioFieldCatalog.fields
                .filter { it.source == source }
                .map { it.name }
                .toSet()
        require(values.size == fields.size && values.keys == expected) { "Incomplete or duplicate legacy read fields" }
        fields.forEach(StudioFieldValues::validateField)
    }

    fun text(name: String): String? =
        when (val value = values.getValue(name)) {
            RawValue.Null -> null
            else -> requireNotNull(value as? RawValue.Text).value
        }

    fun boolean(name: String): Boolean = requireNotNull(values.getValue(name) as? RawValue.BooleanValue).value

    fun integer(name: String): Int = requireNotNull(values.getValue(name) as? RawValue.Integer).value

    fun long(name: String): Long = requireNotNull(values.getValue(name) as? RawValue.LongValue).value

    fun float(name: String): Float = Float.fromBits(requireNotNull(values.getValue(name) as? RawValue.FloatBits).bits)

    fun stringMap(name: String): Map<String, String> {
        val entries = requireNotNull(values.getValue(name) as? RawValue.OrderedMap).entries
        return Collections.unmodifiableMap(
            entries.associate {
                it.key to
                    requireNotNull(it.value as? RawValue.Text).value
            },
        )
    }

    fun stringSet(name: String): Set<String> =
        Collections.unmodifiableSet(LinkedHashSet(requireNotNull(values.getValue(name) as? RawValue.OrderedSet).values))
}
