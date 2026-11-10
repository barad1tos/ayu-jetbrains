package dev.ayuislands.studio.session

import dev.ayuislands.settings.PanelWidthMode
import dev.ayuislands.settings.WorkspaceSettings
import dev.ayuislands.settings.WorkspaceSurface
import dev.ayuislands.settings.WorkspaceToggle
import dev.ayuislands.studio.model.FieldAddress
import dev.ayuislands.studio.model.FieldPart
import dev.ayuislands.studio.model.StudioFields

/** Selected native inputs are independent of the complete, unchanged Workspace preference record. */
internal data class StudioWorkspaceSelection(
    val settings: WorkspaceSettings,
    val toggles: Set<WorkspaceToggle>,
    val widths: Set<WorkspaceSurface>,
    val isCommitRendererSelected: Boolean,
)

/** Resolves only explicit field edits; dormant legacy flags do not replay native effects. */
internal object StudioWorkspaceProjection {
    fun resolve(
        fields: StudioFields,
        edited: List<FieldAddress>,
        isLicensed: Boolean,
    ): StudioWorkspaceSelection {
        val keys = fields.records.map { it.key }.toSet()
        require(edited.all { it.field in keys && it.part == FieldPart.Value }) {
            "Workspace selection must identify complete Workspace fields"
        }
        val names = edited.map { it.field.name }.toSet()
        val requested = StudioWorkspaceEditing.settings(fields)
        return StudioWorkspaceSelection(
            if (isLicensed) requested else inactive(requested),
            WorkspaceToggle.entries.filter { it.field in names }.toSet(),
            WorkspaceSurface.entries
                .filter { surface ->
                    listOf(surface.modeField, surface.minimumField, surface.maximumField, surface.fixedField).any {
                        it in
                            names
                    }
                }.toSet(),
            WorkspaceSurface.COMMIT.modeField in names ||
                names.any {
                    it == "commitPanelPathDisplayMode" ||
                        it == "commitPanelPathMinHiddenLevels" ||
                        it == "commitPanelPathMaxHiddenLevels"
                },
        )
    }

    private fun inactive(settings: WorkspaceSettings): WorkspaceSettings =
        settings.copy(
            hideEditorVertical = false,
            hideEditorHorizontal = false,
            hideProjectRootPath = false,
            hideProjectHorizontal = false,
            project = settings.project.copy(mode = PanelWidthMode.DEFAULT.name),
            commit = settings.commit.copy(mode = PanelWidthMode.DEFAULT.name),
            git = settings.git.copy(mode = PanelWidthMode.DEFAULT.name),
        )
}
