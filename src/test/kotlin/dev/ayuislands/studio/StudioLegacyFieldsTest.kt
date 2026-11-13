package dev.ayuislands.studio

import dev.ayuislands.settings.AyuIslandsState
import dev.ayuislands.settings.mappings.AccentMappingsState
import dev.ayuislands.studio.model.FieldOrigin
import dev.ayuislands.studio.model.LegacyFieldKey
import dev.ayuislands.studio.model.LegacyStore
import dev.ayuislands.studio.model.RawValue
import dev.ayuislands.studio.model.SourcePresence
import dev.ayuislands.studio.model.StudioBytes
import dev.ayuislands.studio.model.StudioField
import dev.ayuislands.studio.persistence.StudioLegacyFields
import dev.ayuislands.syntax.SyntaxIntensityBaseState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class StudioLegacyFieldsTest {
    @Test
    fun `legacy capture keeps source defaults distinct from source presence`() {
        val settings = AyuIslandsState().apply { lightAccent = "#AABBCC" }
        val key = LegacyFieldKey(LegacyStore.MAPPINGS, "languageFallbackAccent")
        val origin = FieldOrigin(SourcePresence.NULL, StudioBytes(byteArrayOf(32, 9)))
        val accentKey = LegacyFieldKey(LegacyStore.SETTINGS, "lightAccent")
        val accentOrigin = FieldOrigin(SourcePresence.VALUE, StudioBytes(byteArrayOf(35, 65)))
        val origins = mapOf(key to origin, accentKey to accentOrigin)
        val records =
            StudioLegacyFields
                .capture(settings, SyntaxIntensityBaseState(), AccentMappingsState(), origins)
                .records

        val counts = mapOf(LegacyStore.SETTINGS to 177, LegacyStore.SYNTAX to 10, LegacyStore.MAPPINGS to 10)
        assertEquals(counts, records.groupingBy { it.key.source }.eachCount())
        assertEquals(StudioField(key, RawValue.Null, origin), records.single { it.key == key })
        val accent = records.single { it.key == accentKey }
        assertEquals(StudioField(accentKey, RawValue.Text("#AABBCC"), accentOrigin), accent)
        val loop = records.single { it.key.name == "waveformLoopSeconds" }
        assertEquals(RawValue.FloatBits(settings.waveformLoopSeconds.toRawBits()), loop.value)
        assertEquals(FieldOrigin(SourcePresence.ABSENT), loop.origin)
    }

    @Test
    fun `captured maps sets and floats survive later source edits`() {
        val settings = AyuIslandsState().apply { forceOverrides = linkedSetOf("z", "a") }
        val syntax = SyntaxIntensityBaseState().apply { customOverrides = linkedMapOf("z" to "  raw  ", "a" to "") }
        val mappings = AccentMappingsState().apply { dominanceThreshold = -0.0f }
        val overrideOrder = syntax.customOverrides.keys.toList()
        val forceOrder = settings.forceOverrides.toList()

        val captured = StudioLegacyFields.capture(settings, syntax, mappings, origins = emptyMap())
        settings.forceOverrides.clear()
        syntax.customOverrides.clear()
        mappings.dominanceThreshold = 0.0f

        val values = captured.records.associate { it.key.name to it.value }
        val overrides = assertIs<RawValue.OrderedMap>(values.getValue("customOverrides"))
        assertEquals(overrideOrder, overrides.entries.map { it.key })
        val expected = mapOf("z" to RawValue.Text("  raw  "), "a" to RawValue.Text(""))
        assertEquals(expected, overrides.entries.associate { it.key to it.value })
        assertEquals(forceOrder, assertIs<RawValue.OrderedSet>(values.getValue("forceOverrides")).values)
        assertEquals(RawValue.FloatBits((-0.0f).toRawBits()), values.getValue("dominanceThreshold"))
    }
}
