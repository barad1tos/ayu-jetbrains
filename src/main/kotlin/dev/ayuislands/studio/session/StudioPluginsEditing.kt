package dev.ayuislands.studio.session

import dev.ayuislands.settings.ExternalFeatures
import dev.ayuislands.settings.ExternalThemeSettings
import dev.ayuislands.settings.IntegrationSettings
import dev.ayuislands.settings.PluginsEditing
import dev.ayuislands.settings.PluginsPanelEvent
import dev.ayuislands.settings.PluginsSettings
import dev.ayuislands.studio.fields.StudioFieldEdits
import dev.ayuislands.studio.model.FieldPart
import dev.ayuislands.studio.model.FieldValue
import dev.ayuislands.studio.model.RawValue
import dev.ayuislands.studio.model.SessionIntent
import dev.ayuislands.studio.model.SharedIntent
import dev.ayuislands.studio.model.StudioFields

/** Project raw shared fields and retain complete source evidence for every unchanged preference. */
internal object StudioPluginsEditing {
    fun settings(fields: StudioFields): PluginsSettings =
        PluginsSettings(
            ExternalThemeSettings(
                fields.boolean("externalThemeEnhancementsEnabled"),
                fields.boolean("externalThemeAccentOverrideEnabled"),
                ExternalFeatures(
                    fields.boolean("externalThemeQuickSwitcherEnabled"),
                    fields.boolean("externalThemeGlowEnabled"),
                    fields.boolean("externalThemeCodeGlanceProEnabled"),
                    fields.boolean("externalThemeIndentRainbowEnabled"),
                    fields.boolean("externalThemeChromeTintEnabled"),
                ),
                fields.text("externalThemeAccentSource"),
                fields.text("externalThemeAccent"),
            ),
            IntegrationSettings(
                fields.boolean("cgpIntegrationEnabled"),
                fields.boolean("irIntegrationEnabled"),
                fields.text("indentPresetName"),
                (fields.value("indentCustomAlpha") as RawValue.Integer).value,
                fields.boolean("irErrorHighlightEnabled"),
                fields.boolean("ignorePluginSyntaxColorsEnabled"),
            ),
        )

    fun edits(
        fields: StudioFields,
        event: PluginsPanelEvent,
    ): List<SessionIntent> {
        val requested = PluginsEditing.update(settings(fields), event)
        return values(requested).mapNotNull { (name, value) ->
            val before = fields.records.single { it.key.name == name }
            val after = StudioFieldEdits.edit(before, FieldPart.Value, FieldValue.Present(value))
            if (before == after) null else SessionIntent.Shared(SharedIntent.Field(before, after))
        }
    }

    private fun values(settings: PluginsSettings): Map<String, RawValue> {
        val external = settings.external
        val features = external.features
        val integrations = settings.integrations
        return linkedMapOf(
            "externalThemeEnhancementsEnabled" to RawValue.BooleanValue(external.enabled),
            "externalThemeAccentOverrideEnabled" to RawValue.BooleanValue(external.overrideAccent),
            "externalThemeQuickSwitcherEnabled" to RawValue.BooleanValue(features.quickSwitcher),
            "externalThemeGlowEnabled" to RawValue.BooleanValue(features.glow),
            "externalThemeCodeGlanceProEnabled" to RawValue.BooleanValue(features.codeGlance),
            "externalThemeIndentRainbowEnabled" to RawValue.BooleanValue(features.indentRainbow),
            "externalThemeChromeTintEnabled" to RawValue.BooleanValue(features.chromeTint),
            "externalThemeAccentSource" to text(external.accentSource),
            "externalThemeAccent" to text(external.accent),
            "cgpIntegrationEnabled" to RawValue.BooleanValue(integrations.codeGlanceEnabled),
            "irIntegrationEnabled" to RawValue.BooleanValue(integrations.indentEnabled),
            "indentPresetName" to text(integrations.indentPreset),
            "indentCustomAlpha" to RawValue.Integer(integrations.indentAlpha),
            "irErrorHighlightEnabled" to RawValue.BooleanValue(integrations.highlightErrors),
            "ignorePluginSyntaxColorsEnabled" to RawValue.BooleanValue(integrations.ignoreColorsEnabled),
        )
    }

    private fun text(value: String?): RawValue = value?.let(RawValue::Text) ?: RawValue.Null

    private fun StudioFields.value(name: String): RawValue = records.single { it.key.name == name }.value

    private fun StudioFields.boolean(name: String): Boolean = (value(name) as RawValue.BooleanValue).value

    private fun StudioFields.text(name: String): String? = (value(name) as? RawValue.Text)?.value
}
