package dev.ayuislands.studio.ui

import dev.ayuislands.settings.WorkspaceControls
import dev.ayuislands.settings.WorkspacePanelEvent
import dev.ayuislands.settings.WorkspacePanelView
import dev.ayuislands.settings.WorkspaceSettings
import dev.ayuislands.studio.model.PreviewMode
import dev.ayuislands.studio.model.SessionIntent
import dev.ayuislands.studio.model.StudioFields
import dev.ayuislands.studio.model.StudioSettingsSection
import dev.ayuislands.studio.session.StudioWorkspaceEditing

/** Bind shared Workspace controls to explicit working fields and an isolated Current/Draft sample. */
internal class StudioWorkspaceSection(
    private val stage: (List<SessionIntent>) -> Unit,
    private val action: (WorkspacePanelEvent) -> Unit,
) {
    private val controls = WorkspaceControls(::handle)
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
        presentation = WorkspacePresentation(fields, view, currentPreview)
        preview(mode)
    }

    private fun preview(selected: PreviewMode) {
        mode = selected
        val latest = presentation ?: return
        controls.render(
            latest.view.copy(
                preview =
                    if (selected ==
                        PreviewMode.CURRENT
                    ) {
                        latest.current
                    } else {
                        latest.view.preview
                    },
            ),
        )
    }

    private fun handle(event: WorkspacePanelEvent) {
        val latest = presentation ?: return
        if (event == WorkspacePanelEvent.Unlock) {
            action(event)
            return
        }
        val edits = StudioWorkspaceEditing.edits(latest.fields, event)
        if (edits.isNotEmpty()) stage(edits)
    }
}

private data class WorkspacePresentation(
    val fields: StudioFields,
    val view: WorkspacePanelView,
    val current: WorkspaceSettings,
)
