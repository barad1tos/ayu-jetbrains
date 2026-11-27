package dev.ayuislands.studio.session

import dev.ayuislands.studio.fields.StudioFieldEdits
import dev.ayuislands.studio.model.FieldPart
import dev.ayuislands.studio.model.FieldValue
import dev.ayuislands.studio.model.LegacyFieldKey
import dev.ayuislands.studio.model.LegacyStore
import dev.ayuislands.studio.model.RawValue
import dev.ayuislands.studio.model.SharedIntent
import dev.ayuislands.studio.model.StudioField
import dev.ayuislands.studio.model.StudioFields

internal enum class StudioFontTerminal(
    val displayName: String,
) {
    BUILTIN("Built-in Terminal"),
    SYSTEM("System Terminal"),
}

internal object StudioFontTerminalPolicy {
    private val key = LegacyFieldKey(LegacyStore.SETTINGS, "fontInstallTerminal")

    fun selected(system: StudioFields): StudioFontTerminal {
        val name = (field(system).value as? RawValue.Text)?.value
        return StudioFontTerminal.entries.firstOrNull { it.name == name } ?: StudioFontTerminal.BUILTIN
    }

    fun edit(
        system: StudioFields,
        terminal: StudioFontTerminal,
    ): List<SharedIntent.Field> {
        val before = field(system)
        val after = StudioFieldEdits.edit(before, FieldPart.Value, FieldValue.Present(RawValue.Text(terminal.name)))
        return if (before == after) emptyList() else listOf(SharedIntent.Field(before, after))
    }

    private fun field(system: StudioFields): StudioField = system.records.single { it.key == key }
}
