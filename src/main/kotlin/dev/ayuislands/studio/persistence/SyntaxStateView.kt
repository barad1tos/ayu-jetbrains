package dev.ayuislands.studio.persistence

import dev.ayuislands.studio.model.AppearanceSnapshot
import dev.ayuislands.studio.session.StudioSyntaxProjection
import dev.ayuislands.syntax.SyntaxPresetConfig

/** Raw syntax choices remain readable even when an unavailable cell is omitted from the effective config. */
internal class SyntaxStateView(
    values: LegacyValues,
    private val appearance: AppearanceSnapshot,
) {
    val selectedPreset: String? = values.text("selectedPreset")
    val subordinatePreset: String? = values.text("subordinatePreset")
    val customOverrides: Map<String, String> = values.stringMap("customOverrides")
    val customStyles: Map<String, String> = values.stringMap("customStyles")
    val customEmphasis: Map<String, String> = values.stringMap("customEmphasis")
    val dimComments: Boolean = values.boolean("dimComments")
    val softenDocumentation: Boolean = values.boolean("softenDocumentation")
    val quietOperators: Boolean = values.boolean("quietOperators")
    val emphasizeDeclarations: Boolean = values.boolean("emphasizeDeclarations")
    val schemaVersion: Int = values.integer("schemaVersion")

    fun toPresetConfig(): SyntaxPresetConfig = StudioSyntaxProjection.config(appearance)
}
