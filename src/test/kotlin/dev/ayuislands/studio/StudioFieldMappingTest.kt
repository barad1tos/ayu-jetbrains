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

    private fun field(
        store: LegacyStore,
        name: String,
        value: RawValue,
    ): StudioField = StudioField(LegacyFieldKey(store, name), value, FieldOrigin(SourcePresence.VALUE))
}
