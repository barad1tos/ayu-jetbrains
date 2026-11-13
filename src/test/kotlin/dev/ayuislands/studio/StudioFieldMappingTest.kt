package dev.ayuislands.studio

import dev.ayuislands.studio.migration.StudioFieldMapping
import dev.ayuislands.studio.model.FieldOrigin
import dev.ayuislands.studio.model.LegacyFieldKey
import dev.ayuislands.studio.model.LegacyStore
import dev.ayuislands.studio.model.LegacyValueType
import dev.ayuislands.studio.model.OpaqueRecord
import dev.ayuislands.studio.model.RawValue
import dev.ayuislands.studio.model.SourcePresence
import dev.ayuislands.studio.model.StudioBytes
import dev.ayuislands.studio.model.StudioField
import dev.ayuislands.studio.model.StudioFieldCatalog
import dev.ayuislands.studio.model.StudioFieldOwner
import dev.ayuislands.studio.model.StudioFields
import dev.ayuislands.studio.model.StudioList
import dev.ayuislands.studio.model.matchesLegacyType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class StudioFieldMappingTest {
    @Test
    fun `field identity combines store and name`() {
        val settings = field(LegacyStore.SETTINGS, "sameName", RawValue.Text("settings"))
        val syntax = field(LegacyStore.SYNTAX, "sameName", RawValue.Integer(7))

        assertEquals(listOf(settings, syntax), StudioFields(StudioList(listOf(settings, syntax))).records)
        assertFailsWith<IllegalArgumentException> {
            StudioFields(StudioList(listOf(settings, settings.copy(value = RawValue.Text("different")))))
        }
    }

    @Test
    fun `selection preserves record order values and source evidence`() {
        val light =
            field(LegacyStore.SETTINGS, "lightAccent", RawValue.Text("#AABBCC"))
                .copy(origin = FieldOrigin(SourcePresence.VALUE, StudioBytes(byteArrayOf(32, 35, 65))))
        val shared = field(LegacyStore.SETTINGS, "followSystemAppearance", RawValue.BooleanValue(true))
        val unknown = field(LegacyStore.SETTINGS, "futureAccent", RawValue.Text("  untouched  "))
        val mirage =
            field(LegacyStore.SETTINGS, "mirageAccent", RawValue.Text("#112233"))
                .copy(origin = FieldOrigin(SourcePresence.ABSENT))
        val opaque = OpaqueRecord("future", StudioBytes(byteArrayOf(-1)))
        val captured = StudioFields(StudioList(listOf(light, shared, unknown, mirage)), StudioList(listOf(opaque)))

        val accent = StudioFieldMapping.select(captured, StudioFieldOwner.ACCENT)

        assertEquals(listOf(light, mirage), accent.records)
        assertEquals(emptyList(), accent.extensions)
        assertEquals(listOf(shared), StudioFieldMapping.select(captured, StudioFieldOwner.SYSTEM).records)
    }

    @Test
    fun `appearance rejects incomplete accent and misplaced font fields`() {
        val captured = capturedFields()
        val appearance = StudioFieldMapping.appearance(captured)
        val remaining = captured.records.filterNot { it.key.name == "lightAccent" }
        val incomplete = captured.copy(records = StudioList(remaining))

        assertFailsWith<IllegalArgumentException> { StudioFieldMapping.appearance(incomplete) }
        assertFailsWith<IllegalArgumentException> { appearance.copy(font = appearance.glow) }
    }

    @Test
    fun `appearance rejects wrong scalar and map entry types for known fields`() {
        val captured = capturedFields()
        val wrongScalar = replaceValue(captured, "followSystemAccent", RawValue.Integer(1))
        val wrongMap = RawValue.OrderedMap(StudioList(listOf(RawValue.Entry("future", RawValue.Integer(1)))))
        val wrongSyntax = replaceValue(captured, "customOverrides", wrongMap)

        assertFailsWith<IllegalArgumentException> { StudioFieldMapping.appearance(wrongScalar) }
        assertFailsWith<IllegalArgumentException> { StudioFieldMapping.appearance(wrongSyntax) }
    }

    @Test
    fun `appearance preserves nullable text and original source evidence`() {
        val origin = FieldOrigin(SourcePresence.NULL, StudioBytes(byteArrayOf(32, 9)))
        val nullableAccent = StudioField(LegacyFieldKey(LegacyStore.SETTINGS, "lightAccent"), RawValue.Null, origin)
        val captured = capturedFields()
        val records = captured.records.map { if (it.key == nullableAccent.key) nullableAccent else it }

        val appearance = StudioFieldMapping.appearance(captured.copy(records = StudioList(records)))

        assertEquals(nullableAccent, appearance.accent.records.single { it.key == nullableAccent.key })
    }

    @Test
    fun `known field types accept only their compatible raw shapes`() {
        val mapEntries = StudioList(listOf(RawValue.Entry("future", RawValue.Text("  opaque  "))))
        val shapes =
            listOf(
                RawValue.Null to LegacyValueType.TEXT,
                RawValue.Text("  unknown  ") to LegacyValueType.TEXT,
                RawValue.BooleanValue(false) to LegacyValueType.BOOLEAN,
                RawValue.Integer(-1) to LegacyValueType.INTEGER,
                RawValue.LongValue(0L) to LegacyValueType.LONG,
                RawValue.FloatBits((-0.0f).toRawBits()) to LegacyValueType.FLOAT,
                RawValue.OrderedMap(mapEntries) to LegacyValueType.STRING_MAP,
                RawValue.OrderedSet(StudioList(listOf("future"))) to LegacyValueType.STRING_SET,
                RawValue.OrderedList(StudioList(listOf(RawValue.Text("future")))) to null,
                RawValue.Bytes(StudioBytes(byteArrayOf(-1))) to null,
            )
        for ((value, expected) in shapes) {
            assertEquals(listOfNotNull(expected), LegacyValueType.entries.filter(value::matchesLegacyType))
        }
    }

    @Test
    fun `appearance copy preserves all domains and opaque syntax data`() {
        val appearance = StudioFieldMapping.appearance(capturedFields())
        val unknown =
            StudioList(
                listOf(
                    OpaqueRecord("z", StudioBytes(byteArrayOf(-1, 0))),
                    OpaqueRecord("a", StudioBytes(byteArrayOf(32, 9))),
                ),
            )
        val accepted = appearance.copy(syntax = appearance.syntax.copy(extensions = unknown))

        val copied = StudioFieldMapping.copyAppearance(accepted)
        val syntaxVersion = copied.syntax.records.single { it.key.name == "schemaVersion" }

        assertEquals(accepted, copied)
        assertEquals(RawValue.Integer(99), syntaxVersion.value)
        assertEquals(unknown, copied.syntax.extensions)
        assertEquals(27, copied.accent.records.size)
        assertEquals(4, copied.font.records.size)
        assertEquals(28, copied.glow.records.size)
        assertEquals(10, copied.syntax.records.size)
        assertEquals(15, copied.vcs.records.size)
    }

    private fun capturedFields(): StudioFields =
        StudioFields(
            StudioList(
                StudioFieldCatalog.fields.map { definition ->
                    field(definition.source, definition.name, sampleValue(definition.type))
                },
            ),
        )

    private fun sampleValue(type: LegacyValueType): RawValue =
        when (type) {
            LegacyValueType.TEXT -> RawValue.Text("  raw value  ")
            LegacyValueType.BOOLEAN -> RawValue.BooleanValue(true)
            LegacyValueType.INTEGER -> RawValue.Integer(99)
            LegacyValueType.LONG -> RawValue.LongValue(123L)
            LegacyValueType.FLOAT -> RawValue.FloatBits((-0.0f).toRawBits())
            LegacyValueType.STRING_MAP ->
                RawValue.OrderedMap(StudioList(listOf(RawValue.Entry("future", RawValue.Text("")))))
            LegacyValueType.STRING_SET -> RawValue.OrderedSet(StudioList(listOf("z", "a")))
        }

    private fun replaceValue(
        fields: StudioFields,
        name: String,
        value: RawValue,
    ): StudioFields {
        val records = fields.records.map { if (it.key.name == name) it.copy(value = value) else it }
        return fields.copy(records = StudioList(records))
    }

    private fun field(
        store: LegacyStore,
        name: String,
        value: RawValue,
    ): StudioField = StudioField(LegacyFieldKey(store, name), value, FieldOrigin(SourcePresence.VALUE))
}
