package dev.ayuislands.studio.session

import dev.ayuislands.settings.mappings.MappingRow
import dev.ayuislands.settings.mappings.MappingsEdit
import dev.ayuislands.settings.mappings.MappingsPanelSettings
import dev.ayuislands.settings.mappings.MappingsTable
import dev.ayuislands.studio.fields.StudioFieldEdits
import dev.ayuislands.studio.model.FieldPart
import dev.ayuislands.studio.model.FieldValue
import dev.ayuislands.studio.model.RawValue
import dev.ayuislands.studio.model.SessionIntent
import dev.ayuislands.studio.model.SharedIntent
import dev.ayuislands.studio.model.StudioFields

/** Projects shared mapping rows and edits exact entries without rebuilding the user's ordered maps. */
internal object StudioMappingsEditing {
    fun settings(fields: StudioFields): MappingsPanelSettings {
        val names = entries(fields, "projectDisplayNames").associate { it.key to text(it.value) }
        return MappingsPanelSettings(
            projects =
                entries(fields, accentField(MappingsTable.PROJECTS)).map { entry ->
                    MappingRow(entry.key, names[entry.key], text(entry.value))
                },
            languages =
                entries(fields, accentField(MappingsTable.LANGUAGES)).map { entry ->
                    MappingRow(entry.key, null, text(entry.value))
                },
        )
    }

    fun edits(
        fields: StudioFields,
        edit: MappingsEdit,
    ): List<SessionIntent> =
        when (edit) {
            is MappingsEdit.AddProject -> {
                val mapping = edit.mapping
                listOfNotNull(
                    change(fields, accentField(MappingsTable.PROJECTS), mapping.canonicalPath, mapping.hex),
                    change(fields, "projectDisplayNames", mapping.canonicalPath, mapping.displayName),
                )
            }

            is MappingsEdit.AddLanguage -> {
                val mapping = edit.mapping
                listOfNotNull(change(fields, accentField(MappingsTable.LANGUAGES), mapping.languageId, mapping.hex))
            }

            is MappingsEdit.Color -> {
                listOfNotNull(change(fields, accentField(edit.table), edit.key, edit.hex))
            }

            is MappingsEdit.Remove -> {
                listOfNotNull(
                    change(fields, accentField(edit.table), edit.key, null),
                    if (edit.table ==
                        MappingsTable.PROJECTS
                    ) {
                        change(fields, "projectDisplayNames", edit.key, null)
                    } else {
                        null
                    },
                )
            }

            is MappingsEdit.Fallback -> {
                listOfNotNull(change(fields, "projectFallbackAccents", edit.projectKey, edit.hex))
            }

            is MappingsEdit.ForcedLanguage -> {
                listOfNotNull(change(fields, "forcedProjectLanguages", edit.projectKey, edit.languageId))
            }
        }

    private fun change(
        fields: StudioFields,
        name: String,
        key: String,
        value: String?,
    ): SessionIntent.Shared? {
        val before = fields.records.single { it.key.name == name }
        val requested = value?.let { FieldValue.Present(RawValue.Text(it)) } ?: FieldValue.Absent
        val after = StudioFieldEdits.edit(before, FieldPart.MapEntry(key), requested)
        return if (before == after) null else SessionIntent.Shared(SharedIntent.Field(before, after))
    }

    private fun accentField(table: MappingsTable): String =
        when (table) {
            MappingsTable.PROJECTS -> "projectAccents"
            MappingsTable.LANGUAGES -> "languageAccents"
        }

    private fun entries(
        fields: StudioFields,
        name: String,
    ): List<RawValue.Entry> = (fields.records.single { it.key.name == name }.value as RawValue.OrderedMap).entries

    private fun text(value: RawValue): String = (value as RawValue.Text).value
}
