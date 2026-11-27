package dev.ayuislands.studio.ui

import dev.ayuislands.accent.AccentHex
import dev.ayuislands.studio.model.PreviewMode
import dev.ayuislands.studio.model.SharedIntent
import dev.ayuislands.studio.model.StudioFields
import dev.ayuislands.studio.model.StudioSettingsSection
import dev.ayuislands.studio.session.StudioPluginsEditing

internal data class StudioPluginsAvailability(
    val isLicensed: Boolean,
    val hasCodeGlance: Boolean,
    val hasIndentRainbow: Boolean,
)

internal data class StudioPluginsComparison(
    val currentFields: StudioFields,
    val current: StudioPluginsPreviewFacts,
    val draft: StudioPluginsPreviewFacts,
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
    private var previews: PluginsPreviews? = null
    private var mode = PreviewMode.CURRENT
    val content = StudioSectionContent(StudioSettingsSection.PLUGINS, controls, ::selectPreview)

    fun render(
        fields: StudioFields,
        availability: StudioPluginsAvailability,
        isEditable: Boolean,
        chromeBadgePending: Boolean = false,
        comparison: StudioPluginsComparison? = null,
    ) {
        val draft = StudioPluginsEditing.settings(fields)
        previews =
            comparison?.let {
                val current = StudioPluginsEditing.settings(it.currentFields)
                PluginsPreviews(
                    StudioPluginsPreview.prepare(current, it.current),
                    StudioPluginsPreview.prepare(draft, it.draft),
                )
            }
        controls.render(draft, availability, isEditable, chromeBadgePending)
        selectPreview(mode)
    }

    private fun selectPreview(selected: PreviewMode) {
        mode = selected
        val next = if (selected == PreviewMode.CURRENT) previews?.current else previews?.draft
        controls.showPreview(next)
    }
}

private data class PluginsPreviews(
    val current: StudioPluginsPreviewView,
    val draft: StudioPluginsPreviewView,
)
