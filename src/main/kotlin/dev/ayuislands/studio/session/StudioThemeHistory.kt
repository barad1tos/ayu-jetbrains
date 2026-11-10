package dev.ayuislands.studio.session

import dev.ayuislands.accent.AyuVariant
import dev.ayuislands.studio.fields.StudioFieldEdits
import dev.ayuislands.studio.model.FieldPart
import dev.ayuislands.studio.model.FieldValue
import dev.ayuislands.studio.model.RawValue
import dev.ayuislands.studio.model.SharedPreferences
import dev.ayuislands.studio.model.StudioList
import dev.ayuislands.studio.model.StudioThemeChange

/** A manual Ayu choice updates its remembered system appearance slot in the same accepted candidate. */
internal object StudioThemeHistory {
    fun record(
        shared: SharedPreferences,
        selection: StudioThemeChange?,
    ): SharedPreferences {
        val manual = selection as? StudioThemeChange.Explicit ?: return shared
        val fields = shared.system.records
        if (fields.single { it.key.name == "followSystemAppearance" }.value !=
            RawValue.BooleanValue(true)
        ) {
            return shared
        }
        val slot =
            when (AyuVariant.fromThemeName(manual.name)) {
                AyuVariant.DARK, AyuVariant.MIRAGE -> "lastDarkAppearanceTheme"
                AyuVariant.LIGHT -> "lastLightAppearanceTheme"
                null -> return shared
            }
        val updated =
            fields.map { field ->
                if (field.key.name == slot) {
                    StudioFieldEdits.edit(field, FieldPart.Value, FieldValue.Present(RawValue.Text(manual.name)))
                } else {
                    field
                }
            }
        return shared.copy(system = shared.system.copy(records = StudioList(updated)))
    }
}
