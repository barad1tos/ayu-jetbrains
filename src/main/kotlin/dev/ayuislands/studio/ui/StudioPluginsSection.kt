package dev.ayuislands.studio.ui

import dev.ayuislands.accent.AccentHex
import dev.ayuislands.studio.model.SharedIntent
import dev.ayuislands.studio.model.StudioFields
import dev.ayuislands.studio.model.StudioSettingsSection
import dev.ayuislands.studio.session.StudioPluginsEditing

internal data class StudioPluginsAvailability(
    val isLicensed: Boolean,
    val hasCodeGlance: Boolean,
    val hasIndentRainbow: Boolean,
)

internal enum class StudioPluginTarget(
    val title: String,
    val url: String,
) {
    CODE_GLANCE("CodeGlance Pro", "https://plugins.jetbrains.com/plugin/18824-codeglance-pro"),
    INDENT_RAINBOW("Indent Rainbow", "https://plugins.jetbrains.com/plugin/13308-indent-rainbow"),
}

/** Route native Plugins controls through the host's latest shared draft. */
internal class StudioPluginsSection(
    stage: ((StudioFields) -> List<SharedIntent.Field>) -> Unit,
    chooseCustom: (AccentHex?) -> AccentHex?,
    openPlugin: (StudioPluginTarget) -> Unit,
    unlock: () -> Unit,
) {
    private val controls =
        StudioPluginsControls(
            onEdit = { edit -> stage { fields -> StudioPluginsEditing.edits(fields, edit) } },
            chooseCustom = chooseCustom,
            openPlugin = openPlugin,
            unlock = unlock,
        )
    val content = StudioSectionContent(StudioSettingsSection.PLUGINS, controls) {}

    fun render(
        fields: StudioFields,
        availability: StudioPluginsAvailability,
        isEditable: Boolean,
        chromeBadgePending: Boolean = false,
    ) {
        controls.render(StudioPluginsEditing.settings(fields), availability, isEditable, chromeBadgePending)
    }
}
