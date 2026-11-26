package dev.ayuislands.studio

import dev.ayuislands.accent.AccentHex
import dev.ayuislands.accent.ExternalAccentSource
import dev.ayuislands.indent.IndentPreset
import dev.ayuislands.studio.model.AcceptanceChoices
import dev.ayuislands.studio.model.AcceptanceReview
import dev.ayuislands.studio.model.FieldOrigin
import dev.ayuislands.studio.model.LegacyFieldKey
import dev.ayuislands.studio.model.LegacyStore
import dev.ayuislands.studio.model.OpaqueRecord
import dev.ayuislands.studio.model.RawValue
import dev.ayuislands.studio.model.SharedPreferences
import dev.ayuislands.studio.model.SourcePresence
import dev.ayuislands.studio.model.StudioBytes
import dev.ayuislands.studio.model.StudioField
import dev.ayuislands.studio.model.StudioFieldCatalog
import dev.ayuislands.studio.model.StudioFieldOwner
import dev.ayuislands.studio.model.StudioFields
import dev.ayuislands.studio.model.StudioList
import dev.ayuislands.studio.model.StudioPluginsEdit
import dev.ayuislands.studio.model.StudioPluginsToggle
import dev.ayuislands.studio.model.SystemAccentPolicy
import dev.ayuislands.studio.session.StudioPluginsEditing
import dev.ayuislands.studio.session.StudioSharedMerge
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StudioPluginsEditingTest {
    @Test
    fun `each Plugins toggle stages just its own field and preserves disabled preferences`() {
        val original = fields()
        assertEquals(StudioPluginsToggle.entries.toSet(), toggles.keys)

        for ((toggle, name) in toggles) {
            val initial = (original.records.single { it.key.name == name }.value as RawValue.BooleanValue).value
            assertEquals(initial, StudioPluginsEditing.settings(original).isEnabled(toggle))
            val changed = assertChanges(original, StudioPluginsEdit.Toggle(toggle, !initial), name to boolean(!initial))
            assertEquals(!initial, StudioPluginsEditing.settings(changed).isEnabled(toggle))
            val restored = assertChanges(changed, StudioPluginsEdit.Toggle(toggle, initial), name to boolean(initial))
            assertEquals(original.records.map { it.value }, restored.records.map { it.value })
        }
    }

    @Test
    fun `mode selection preserves raw fallback and a swatch explicitly chooses Manual`() {
        val original = fields()
        val automatic =
            assertChanges(
                original,
                StudioPluginsEdit.AccentSource(ExternalAccentSource.AUTOMATIC),
                "externalThemeAccentSource" to text("AUTOMATIC"),
            )
        val accent = AccentHex.require("#34AbCd")
        val manual =
            assertChanges(
                automatic,
                StudioPluginsEdit.Accent(accent),
                "externalThemeAccentSource" to text("MANUAL"),
                "externalThemeAccent" to text("#34AbCd"),
            )
        assertChanges(manual, StudioPluginsEdit.Accent(accent))
        assertChanges(
            manual,
            StudioPluginsEdit.Accent(AccentHex.require("#FFCD66")),
            "externalThemeAccent" to text("#FFCD66"),
        )
        assertChanges(
            fields("externalThemeAccent" to text("#34AbCd")),
            StudioPluginsEdit.Accent(accent),
            "externalThemeAccentSource" to text("MANUAL"),
        )
    }

    @Test
    fun `preset and custom alpha are independent explicit edits`() {
        val original = fields()
        val preset =
            assertChanges(
                original,
                StudioPluginsEdit.IndentPresetChoice(IndentPreset.NEON),
                "indentPresetName" to text("NEON"),
            )
        val alpha =
            assertChanges(preset, StudioPluginsEdit.IndentAlpha(117), "indentCustomAlpha" to RawValue.Integer(117))
        assertChanges(alpha, StudioPluginsEdit.IndentPresetChoice(IndentPreset.NEON))
        assertChanges(alpha, StudioPluginsEdit.IndentAlpha(117))
        assertChanges(
            alpha,
            StudioPluginsEdit.IndentPresetChoice(IndentPreset.CUSTOM),
            "indentPresetName" to text("CUSTOM"),
        )
    }

    @Test
    fun `projection reads all owned settings without normalizing unknown null or historical values`() {
        val original = fields()
        val catalog = StudioFieldCatalog.fields.filter { it.owner == StudioFieldOwner.PLUGINS }
        val expectedKeys = catalog.map { LegacyFieldKey(it.source, it.name) }.toSet()
        assertEquals(expectedKeys, original.records.map { it.key }.toSet())

        val view = StudioPluginsEditing.settings(original)
        assertEquals("FUTURE_SOURCE", view.externalAccentSource)
        assertEquals("  #a1B2c3  ", view.externalAccent)
        assertEquals(null, view.indentPreset)
        assertEquals(310, view.indentAlpha)

        val future = StudioPluginsEditing.settings(fields("indentPresetName" to text("FUTURE_PRESET")))
        assertEquals("FUTURE_PRESET", future.indentPreset)
        val missing =
            StudioPluginsEditing.settings(
                fields("externalThemeAccentSource" to RawValue.Null, "externalThemeAccent" to RawValue.Null),
            )
        assertEquals(null, missing.externalAccentSource)
        assertEquals(null, missing.externalAccent)
    }

    @Test
    fun `same toggle and source choices retain the original lexemes`() {
        val original = fields("externalThemeAccentSource" to text("AUTOMATIC"))
        assertChanges(original, StudioPluginsEdit.Toggle(StudioPluginsToggle.EXTERNAL_GLOW, true))
        assertChanges(original, StudioPluginsEdit.AccentSource(ExternalAccentSource.AUTOMATIC))
    }

    private fun assertChanges(
        original: StudioFields,
        edit: StudioPluginsEdit,
        vararg expectedChanges: Pair<String, RawValue>,
    ): StudioFields {
        val expected = expectedChanges.toMap()
        val edits = StudioPluginsEditing.edits(original, edit)
        assertEquals(expected, edits.associate { it.after.key.name to it.after.value })
        assertEquals(expected.size, edits.size)
        for ((before, after) in edits) {
            assertEquals(original.records.single { it.key == before.key }, before)
            assertEquals(before.key, after.key)
        }
        val empty = StudioFields(StudioList(emptyList()))
        val shared = SharedPreferences(original, empty, empty, empty, SystemAccentPolicy.BY_PROFILE)
        val review = mutableListOf<AcceptanceReview>()
        val accepted = StudioSharedMerge.merge(shared, edits, AcceptanceChoices(), review)
        val expectedRecords =
            original.records.map { record ->
                val value = expected[record.key.name] ?: return@map record
                record.copy(value = value, origin = FieldOrigin(SourcePresence.VALUE))
            }
        assertTrue(review.isEmpty())
        assertEquals(shared.copy(plugins = original.copy(records = StudioList(expectedRecords))), accepted)
        return accepted.plugins
    }

    private fun fields(vararg overrides: Pair<String, RawValue>): StudioFields {
        val values =
            linkedMapOf(
                "externalThemeEnhancementsEnabled" to boolean(false),
                "externalThemeQuickSwitcherEnabled" to boolean(true),
                "externalThemeGlowEnabled" to boolean(true),
                "externalThemeCodeGlanceProEnabled" to boolean(true),
                "externalThemeIndentRainbowEnabled" to boolean(false),
                "externalThemeChromeTintEnabled" to boolean(false),
                "externalThemeAccentOverrideEnabled" to boolean(true),
                "cgpIntegrationEnabled" to boolean(false),
                "irIntegrationEnabled" to boolean(true),
                "irErrorHighlightEnabled" to boolean(false),
                "ignorePluginSyntaxColorsEnabled" to boolean(true),
                "externalThemeAccentSource" to text("FUTURE_SOURCE"),
                "externalThemeAccent" to text("  #a1B2c3  "),
                "indentPresetName" to RawValue.Null,
                "indentCustomAlpha" to RawValue.Integer(310),
            ).apply { putAll(overrides) }
        val records =
            values.map { (name, value) ->
                val origin = if (value == RawValue.Null) SourcePresence.NULL else SourcePresence.VALUE
                StudioField(
                    LegacyFieldKey(LegacyStore.SETTINGS, name),
                    value,
                    FieldOrigin(origin, StudioBytes("legacy bytes for $name".encodeToByteArray())),
                )
            }
        return StudioFields(
            StudioList(records),
            StudioList(listOf(OpaqueRecord("future-plugin", StudioBytes(byteArrayOf(0, 7, -1, 4))))),
        )
    }

    private fun boolean(value: Boolean): RawValue = RawValue.BooleanValue(value)

    private fun text(value: String): RawValue = RawValue.Text(value)

    private companion object {
        val toggles =
            mapOf(
                StudioPluginsToggle.EXTERNAL_ENHANCEMENTS to "externalThemeEnhancementsEnabled",
                StudioPluginsToggle.EXTERNAL_QUICK_SWITCHER to "externalThemeQuickSwitcherEnabled",
                StudioPluginsToggle.EXTERNAL_GLOW to "externalThemeGlowEnabled",
                StudioPluginsToggle.EXTERNAL_CODE_GLANCE to "externalThemeCodeGlanceProEnabled",
                StudioPluginsToggle.EXTERNAL_INDENT_RAINBOW to "externalThemeIndentRainbowEnabled",
                StudioPluginsToggle.EXTERNAL_CHROME_TINT to "externalThemeChromeTintEnabled",
                StudioPluginsToggle.OVERRIDE_NATIVE_ACCENT to "externalThemeAccentOverrideEnabled",
                StudioPluginsToggle.CODE_GLANCE_INTEGRATION to "cgpIntegrationEnabled",
                StudioPluginsToggle.INDENT_RAINBOW_INTEGRATION to "irIntegrationEnabled",
                StudioPluginsToggle.INDENT_ERRORS to "irErrorHighlightEnabled",
                StudioPluginsToggle.IGNORE_SYNTAX_COLORS to "ignorePluginSyntaxColorsEnabled",
            )
    }
}
