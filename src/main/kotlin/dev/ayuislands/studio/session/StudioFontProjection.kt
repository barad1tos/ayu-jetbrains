package dev.ayuislands.studio.session

import dev.ayuislands.font.FontPreset
import dev.ayuislands.font.FontSettings
import dev.ayuislands.studio.fields.StudioAppearanceFields
import dev.ayuislands.studio.model.AppearanceSnapshot
import dev.ayuislands.studio.model.LegacyFieldKey
import dev.ayuislands.studio.model.LegacyStore
import dev.ayuislands.studio.model.RawValue

internal data class StudioFontSettings(
    val enabled: Boolean,
    val presetName: String?,
    val resolved: FontSettings?,
)

internal object StudioFontProjection {
    fun settings(appearance: AppearanceSnapshot): StudioFontSettings {
        val enabled = (read(appearance, StudioFontFields.enabled) as RawValue.BooleanValue).value
        val name = (read(appearance, StudioFontFields.presetName) as? RawValue.Text)?.value
        val preset = FontPreset.findByName(name ?: FontPreset.AMBIENT.name)
        val resolved =
            preset?.let {
                val records = customizations(appearance)
                val console = (read(appearance, StudioFontFields.console) as RawValue.BooleanValue).value
                FontSettings.decode(records[it.customizationKey(records.keys)], it).copy(applyToConsole = console)
            }
        return StudioFontSettings(enabled, name, resolved)
    }

    fun customizations(appearance: AppearanceSnapshot): Map<String, String> =
        (read(appearance, StudioFontFields.customizations) as RawValue.OrderedMap)
            .entries
            .associate { it.key to (it.value as RawValue.Text).value }

    private fun read(
        appearance: AppearanceSnapshot,
        field: LegacyFieldKey,
    ): RawValue = StudioAppearanceFields.field(appearance, field).value
}

internal object StudioFontFields {
    val enabled = LegacyFieldKey(LegacyStore.SETTINGS, "fontPresetEnabled")
    val presetName = LegacyFieldKey(LegacyStore.SETTINGS, "fontPresetName")
    val console = LegacyFieldKey(LegacyStore.SETTINGS, "fontApplyToConsole")
    val customizations = LegacyFieldKey(LegacyStore.SETTINGS, "fontPresetCustomizations")
}
