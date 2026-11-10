package dev.ayuislands.studio.session

import dev.ayuislands.font.FontPanelSelection
import dev.ayuislands.font.FontPreset
import dev.ayuislands.font.FontSettings
import dev.ayuislands.studio.fields.StudioAppearanceFields
import dev.ayuislands.studio.model.AppearanceSnapshot
import dev.ayuislands.studio.model.LegacyFieldKey
import dev.ayuislands.studio.model.LegacyStore
import dev.ayuislands.studio.model.RawValue
import dev.ayuislands.studio.model.StudioFontRequest

/** Resolves the existing legacy font format without replacing raw intent or reading native services. */
internal object StudioFontProjection {
    fun request(appearance: AppearanceSnapshot): StudioFontRequest {
        val isEnabled = requireNotNull(read(appearance, "fontPresetEnabled") as? RawValue.BooleanValue).value
        if (!isEnabled) return StudioFontRequest.Disabled
        val selected = selection(appearance)
        return selected.settings?.let(StudioFontRequest::Enabled)
            ?: StudioFontRequest.Unavailable(requireNotNull(selected.rawName))
    }

    /** Decodes visible settings even while disabled, without changing the requested raw selection. */
    fun selection(appearance: AppearanceSnapshot): FontPanelSelection {
        val rawName = read(appearance, "fontPresetName")
        val name = if (rawName == RawValue.Null) null else requireNotNull(rawName as? RawValue.Text).value
        val preset = FontPreset.findByName(name ?: FontPreset.AMBIENT.name) ?: return FontPanelSelection(name, null)
        val customizations = requireNotNull(read(appearance, "fontPresetCustomizations") as? RawValue.OrderedMap)
        val decoded =
            customizations.entries.associateTo(linkedMapOf()) {
                it.key to requireNotNull(it.value as? RawValue.Text).value
            }
        FontPreset.migrateCustomizations(decoded)
        val encoded = decoded[preset.name]
        val console = requireNotNull(read(appearance, "fontApplyToConsole") as? RawValue.BooleanValue).value
        return FontPanelSelection(name, FontSettings.decode(encoded, preset).copy(applyToConsole = console))
    }

    private fun read(
        appearance: AppearanceSnapshot,
        name: String,
    ): RawValue = StudioAppearanceFields.field(appearance, LegacyFieldKey(LegacyStore.SETTINGS, name)).value
}
