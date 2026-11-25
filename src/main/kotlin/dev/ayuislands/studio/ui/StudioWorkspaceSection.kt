package dev.ayuislands.studio.ui

import dev.ayuislands.settings.WorkspacePanelEvent
import dev.ayuislands.settings.WorkspacePanelView
import dev.ayuislands.settings.WorkspaceSettings
import dev.ayuislands.studio.model.PreviewMode
import dev.ayuislands.studio.model.SharedIntent
import dev.ayuislands.studio.model.StudioFields
import dev.ayuislands.studio.model.StudioSettingsSection
import dev.ayuislands.studio.session.StudioWorkspaceEditing

/** The host supplies its latest working fields when it evaluates each shared edit. */
internal class StudioWorkspaceSection(
    private val stage: ((StudioFields) -> List<SharedIntent.Field>) -> Unit,
    private val unlock: () -> Unit,
) {
    private val controls = StudioWorkspaceControls(::edit)
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
        val rendered = WorkspacePresentation(currentPreview, view.preview)
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
        stage { working -> StudioWorkspaceEditing.edits(working, event) }
    }
}

private data class WorkspacePresentation(
    val current: WorkspaceSettings,
    val draft: WorkspaceSettings,
) {
    fun preview(mode: PreviewMode): WorkspaceSettings = if (mode == PreviewMode.CURRENT) current else draft
}
