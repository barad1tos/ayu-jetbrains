package dev.ayuislands.studio.persistence

import dev.ayuislands.font.FontIdentityRules
import dev.ayuislands.font.FontOwnership
import dev.ayuislands.font.FontOwnershipCodec
import dev.ayuislands.font.FontOwnershipRecord
import dev.ayuislands.font.FontPreferencePolicy
import dev.ayuislands.studio.fields.StudioFieldValues
import dev.ayuislands.studio.model.FieldAddress
import dev.ayuislands.studio.model.FieldPart
import dev.ayuislands.studio.model.FieldValue
import dev.ayuislands.studio.model.LegacyFieldKey
import dev.ayuislands.studio.model.LegacyStore
import dev.ayuislands.studio.model.RawValue
import dev.ayuislands.studio.model.RuntimeFieldChange
import dev.ayuislands.studio.model.StudioList
import dev.ayuislands.studio.model.StudioRuntimeState

/** Adapts managed Font serialization to the immutable Studio runtime fields. */
internal class StudioFontOwnership(
    runtime: StudioRuntimeState,
) {
    private val versionField =
        runtime.legacy.records.single {
            it.key == LegacyFieldKey(LegacyStore.SETTINGS, "fontOwnershipVersion")
        }
    private val snapshotField =
        runtime.legacy.records.single {
            it.key == LegacyFieldKey(LegacyStore.SETTINGS, "fontOwnershipSnapshots")
        }
    private val records =
        requireNotNull(snapshotField.value as? RawValue.OrderedMap).entries.associate {
            it.key to requireNotNull(it.value as? RawValue.Text).value
        }
    val version: Int = requireNotNull(versionField.value as? RawValue.Integer).value
    val keys: Set<String> = java.util.Set.copyOf(records.keys)

    fun read(key: String): FontOwnershipRecord? = records[key]?.let(FontOwnershipCodec::decode)

    /** Validates all prospective managed records before the operation may perform a native write. */
    fun changes(
        outcomes: Map<String, FontOwnershipRecord?>,
        hasWrites: Boolean,
    ): StudioList<RuntimeFieldChange> {
        if (outcomes.isEmpty() && !hasWrites) return StudioList(emptyList())
        check(version in 0..FontOwnership.VERSION) { "Unsupported Font ownership version" }
        val changed = mutableListOf<RuntimeFieldChange>()
        if (hasWrites && version != FontOwnership.VERSION) {
            changed.add(
                RuntimeFieldChange(
                    FieldAddress(versionField.key),
                    FieldValue.Present(versionField.value),
                    FieldValue.Present(RawValue.Integer(FontOwnership.VERSION)),
                ),
            )
        }
        val ordered = records.keys.filter { it in outcomes } + outcomes.keys.filter { it !in records }
        for (key in ordered) {
            require(FontIdentityRules.recordId(key) != null) { "Invalid managed Font key" }
            val previous = read(key)
            check(key !in records || previous != null) { "Unrecognized Font ownership must be preserved" }
            val requested = outcomes[key]
            if (matches(previous, requested)) continue
            val part = FieldPart.MapEntry(key)
            val after = requested?.let { FieldValue.Present(RawValue.Text(encodeExact(it))) } ?: FieldValue.Absent
            changed.add(
                RuntimeFieldChange(
                    FieldAddress(snapshotField.key, part),
                    StudioFieldValues.value(snapshotField, part),
                    after,
                ),
            )
        }
        return StudioList(changed)
    }

    private fun encodeExact(record: FontOwnershipRecord): String {
        val encoded = FontOwnershipCodec.encode(record)
        check(matches(record, FontOwnershipCodec.decode(encoded))) { "Managed Font record cannot round-trip exactly" }
        return encoded
    }

    private fun matches(
        first: FontOwnershipRecord?,
        second: FontOwnershipRecord?,
    ): Boolean =
        if (first == null || second == null) {
            first == second
        } else {
            first.status == second.status &&
                FontPreferencePolicy.matches(first.baseline, second.baseline) &&
                FontPreferencePolicy.matches(first.applied, second.applied)
        }
}
