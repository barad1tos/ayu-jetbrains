package dev.ayuislands.studio.session

import dev.ayuislands.accent.ExternalAccentSource
import dev.ayuislands.studio.fields.StudioFieldEdits
import dev.ayuislands.studio.model.FieldPart
import dev.ayuislands.studio.model.FieldValue
import dev.ayuislands.studio.model.LegacyFieldKey
import dev.ayuislands.studio.model.LegacyStore
import dev.ayuislands.studio.model.RawValue
import dev.ayuislands.studio.model.SharedIntent
import dev.ayuislands.studio.model.StudioField
import dev.ayuislands.studio.model.StudioFields
import dev.ayuislands.studio.model.StudioList
import dev.ayuislands.studio.model.StudioPluginsEdit
import dev.ayuislands.studio.model.StudioPluginsToggle

internal data class StudioPluginsSettings(
    private val enabledToggles: StudioList<StudioPluginsToggle>,
    val externalAccentSource: String?,
    val externalAccent: String?,
    val indentPreset: String?,
    val indentAlpha: Int,
) {
    fun isEnabled(toggle: StudioPluginsToggle): Boolean = toggle in enabledToggles
}

/** Project shared Plugins preferences and stage fields addressed by explicit edits. */
internal object StudioPluginsEditing {
    fun settings(fields: StudioFields): StudioPluginsSettings =
        StudioPluginsSettings(
            enabledToggles =
                StudioList(
                    StudioPluginsToggle.entries.filter { toggle ->
                        (fields.field(toggle.field).value as RawValue.BooleanValue).value
                    },
                ),
            externalAccentSource = fields.text(EXTERNAL_ACCENT_SOURCE),
            externalAccent = fields.text(EXTERNAL_ACCENT),
            indentPreset = fields.text(INDENT_PRESET),
            indentAlpha = (fields.field(INDENT_ALPHA).value as RawValue.Integer).value,
        )

    fun edits(
        fields: StudioFields,
        edit: StudioPluginsEdit,
    ): StudioList<SharedIntent.Field> =
        StudioList(
            values(edit).mapNotNull { (name, value) ->
                val before = fields.field(name)
                val after = StudioFieldEdits.edit(before, FieldPart.Value, FieldValue.Present(value))
                if (before == after) null else SharedIntent.Field(before, after)
            },
        )

    private fun values(edit: StudioPluginsEdit): List<Pair<String, RawValue>> =
        when (edit) {
            is StudioPluginsEdit.Toggle -> listOf(edit.target.field to RawValue.BooleanValue(edit.enabled))
            is StudioPluginsEdit.AccentSource -> listOf(EXTERNAL_ACCENT_SOURCE to RawValue.Text(edit.value.name))
            is StudioPluginsEdit.Accent ->
                listOf(
                    EXTERNAL_ACCENT_SOURCE to RawValue.Text(ExternalAccentSource.MANUAL.name),
                    EXTERNAL_ACCENT to RawValue.Text(edit.value.value),
                )
            is StudioPluginsEdit.IndentPresetChoice -> listOf(INDENT_PRESET to RawValue.Text(edit.value.name))
            is StudioPluginsEdit.IndentAlpha -> listOf(INDENT_ALPHA to RawValue.Integer(edit.value))
        }

    private fun StudioFields.field(name: String): StudioField {
        val key = LegacyFieldKey(LegacyStore.SETTINGS, name)
        return records.single { it.key == key }
    }

    private fun StudioFields.text(name: String): String? =
        when (val value = field(name).value) {
            RawValue.Null -> null
            else -> (value as RawValue.Text).value
        }

    private const val EXTERNAL_ACCENT_SOURCE = "externalThemeAccentSource"
    private const val EXTERNAL_ACCENT = "externalThemeAccent"
    private const val INDENT_PRESET = "indentPresetName"
    private const val INDENT_ALPHA = "indentCustomAlpha"
}
