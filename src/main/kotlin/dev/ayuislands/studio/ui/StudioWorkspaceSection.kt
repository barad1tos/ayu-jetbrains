package dev.ayuislands.studio.ui

import dev.ayuislands.settings.WorkspaceControls
import dev.ayuislands.settings.WorkspacePanelEvent
import dev.ayuislands.settings.WorkspacePanelView
import dev.ayuislands.settings.WorkspaceSettings
import dev.ayuislands.studio.model.PreviewMode
import dev.ayuislands.studio.model.SharedIntent
import dev.ayuislands.studio.model.StudioFields
import dev.ayuislands.studio.model.StudioSettingsSection
import dev.ayuislands.studio.session.StudioWorkspaceEditing

/** Routes Workspace edits to shared working fields and keeps the preview local to Settings. */
internal class StudioWorkspaceSection(
    private val stage: (List<SharedIntent.Field>) -> Unit,
    private val unlock: () -> Unit,
) {
    private val controls = WorkspaceControls(::edit)
    private var presentation: WorkspacePresentation? = null
    private var mode = PreviewMode.CURRENT
    val content = StudioSectionContent(StudioSettingsSection.WORKSPACE, controls, ::preview)

    fun render(
        fields: StudioFields,
        view: WorkspacePanelView,
        currentPreview: WorkspaceSettings,
    ) {
        require(view.settings == StudioWorkspaceEditing.settings(fields)) {
            "Workspace view belongs to another shared snapshot"
        }
        val rendered = WorkspacePresentation(fields, currentPreview, view.preview)
        presentation = rendered
        controls.render(view.copy(preview = rendered.preview(mode)))
    }

    private fun preview(selected: PreviewMode) {
        mode = selected
        val rendered = presentation ?: return
        controls.showPreview(rendered.preview(selected))
    }

    private fun edit(event: WorkspacePanelEvent) {
        if (event == WorkspacePanelEvent.Unlock) {
            unlock()
            return
        }
        val fields = presentation?.fields ?: return
        val edits = StudioWorkspaceEditing.edits(fields, event)
        if (edits.isNotEmpty()) stage(edits)
    }
}

private data class WorkspacePresentation(
    val fields: StudioFields,
    val current: WorkspaceSettings,
    val draft: WorkspaceSettings,
) {
    fun preview(mode: PreviewMode): WorkspaceSettings = if (mode == PreviewMode.CURRENT) current else draft
}
