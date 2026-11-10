package dev.ayuislands.studio.ui

import dev.ayuislands.settings.PluginsControls
import dev.ayuislands.settings.PluginsPanelEvent
import dev.ayuislands.settings.PluginsPanelView
import dev.ayuislands.settings.PluginsPreviewView
import dev.ayuislands.studio.model.PreviewMode
import dev.ayuislands.studio.model.SessionIntent
import dev.ayuislands.studio.model.StudioFields
import dev.ayuislands.studio.model.StudioSettingsSection
import dev.ayuislands.studio.session.StudioPluginsEditing

/** Bind shared Plugins edits and prepared local samples without accepting preferences or applying effects. */
internal class StudioPluginsSection(
    private val stage: (List<SessionIntent>) -> Unit,
    private val action: (PluginsPanelEvent) -> Unit,
) {
    private val controls = PluginsControls(::handle)
    private var presentation: PluginsPresentation? = null
    private var mode = PreviewMode.CURRENT
    val content = StudioSectionContent(StudioSettingsSection.PLUGINS, controls, ::preview)

    fun render(
        fields: StudioFields,
        view: PluginsPanelView,
        currentPreview: PluginsPreviewView,
        draftPreview: PluginsPreviewView,
    ) {
        require(
            view.settings == StudioPluginsEditing.settings(fields),
        ) { "Plugins view belongs to another shared snapshot" }
        presentation = PluginsPresentation(fields, view, currentPreview, draftPreview)
        preview(mode)
    }

    private fun preview(selected: PreviewMode) {
        mode = selected
        val latest = presentation ?: return
        controls.render(latest.view, if (selected == PreviewMode.CURRENT) latest.current else latest.draft)
    }

    private fun handle(event: PluginsPanelEvent) {
        val latest = presentation ?: return
        when (event) {
            PluginsPanelEvent.Unlock, PluginsPanelEvent.CustomAccent, is PluginsPanelEvent.PluginPage -> {
                action(event)
            }

            else -> {
                val edits = StudioPluginsEditing.edits(latest.fields, event)
                if (edits.isNotEmpty()) stage(edits)
            }
        }
    }
}

private data class PluginsPresentation(
    val fields: StudioFields,
    val view: PluginsPanelView,
    val current: PluginsPreviewView,
    val draft: PluginsPreviewView,
)
