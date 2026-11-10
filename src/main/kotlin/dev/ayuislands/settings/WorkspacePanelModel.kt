package dev.ayuislands.settings

import dev.ayuislands.toolwindow.AutoFitCalculator

internal enum class WorkspaceSurface(
    val title: String,
    val modeField: String,
    val minimumField: String,
    val maximumField: String,
    val fixedField: String,
    val legacyField: String?,
    val maximumLowerBound: Int,
) {
    PROJECT(
        "Project View",
        "projectPanelWidthMode",
        "projectPanelAutoFitMinWidth",
        "autoFitMaxWidth",
        "projectPanelFixedWidth",
        "autoFitProjectPanelWidth",
        AutoFitCalculator.MIN_PROJECT_AUTOFIT_WIDTH,
    ),
    COMMIT(
        "Commit Panel",
        "commitPanelWidthMode",
        "commitPanelAutoFitMinWidth",
        "autoFitCommitMaxWidth",
        "commitPanelFixedWidth",
        "autoFitCommitPanelWidth",
        AutoFitCalculator.MIN_COMMIT_AUTOFIT_WIDTH,
    ),
    GIT(
        "Git Panel",
        "gitPanelWidthMode",
        "gitPanelAutoFitMinWidth",
        "gitPanelAutoFitMaxWidth",
        "gitPanelFixedWidth",
        null,
        AutoFitCalculator.MIN_GIT_AUTOFIT_WIDTH,
    ),
}

internal enum class WorkspaceToggle(
    val field: String,
    val title: String,
    val description: String,
) {
    EDITOR_VERTICAL_SCROLLBAR(
        "hideEditorVScrollbar",
        "Hide vertical scrollbar",
        "Remove the vertical scrollbar from the editor gutter",
    ),
    EDITOR_HORIZONTAL_SCROLLBAR(
        "hideEditorHScrollbar",
        "Hide horizontal scrollbar",
        "Remove the bottom scrollbar from the editor",
    ),
    PROJECT_ROOT_PATH(
        "hideProjectRootPath",
        "Hide filesystem path",
        "Remove the directory path shown next to the project name",
    ),
    PROJECT_HORIZONTAL_SCROLLBAR(
        "hideProjectViewHScrollbar",
        "Hide horizontal scrollbar",
        "Remove the bottom scrollbar from the Project tool window",
    ),
}

/** Preserve raw mode names and dormant dimensions independently of their current availability. */
internal data class WorkspaceWidth(
    val mode: String?,
    val minimum: Int,
    val maximum: Int,
    val fixed: Int,
)

internal data class WorkspacePaths(
    val mode: String?,
    val minimum: Int,
    val maximum: Int,
)

internal data class WorkspaceSettings(
    val hideEditorVertical: Boolean,
    val hideEditorHorizontal: Boolean,
    val hideProjectRootPath: Boolean,
    val hideProjectHorizontal: Boolean,
    val project: WorkspaceWidth,
    val commit: WorkspaceWidth,
    val git: WorkspaceWidth,
    val paths: WorkspacePaths,
) {
    fun width(surface: WorkspaceSurface): WorkspaceWidth =
        when (surface) {
            WorkspaceSurface.PROJECT -> project
            WorkspaceSurface.COMMIT -> commit
            WorkspaceSurface.GIT -> git
        }

    fun isHidden(target: WorkspaceToggle): Boolean =
        when (target) {
            WorkspaceToggle.EDITOR_VERTICAL_SCROLLBAR -> hideEditorVertical
            WorkspaceToggle.EDITOR_HORIZONTAL_SCROLLBAR -> hideEditorHorizontal
            WorkspaceToggle.PROJECT_ROOT_PATH -> hideProjectRootPath
            WorkspaceToggle.PROJECT_HORIZONTAL_SCROLLBAR -> hideProjectHorizontal
        }
}

internal data class WorkspacePanelView(
    val settings: WorkspaceSettings,
    val preview: WorkspaceSettings,
    val isEditable: Boolean,
    val isLicensed: Boolean,
)

internal sealed interface WorkspacePanelEvent {
    data class Toggle(
        val target: WorkspaceToggle,
        val value: Boolean,
    ) : WorkspacePanelEvent

    data class Mode(
        val surface: WorkspaceSurface,
        val value: PanelWidthMode,
    ) : WorkspacePanelEvent

    data class Minimum(
        val surface: WorkspaceSurface,
        val value: Int,
    ) : WorkspacePanelEvent

    data class Maximum(
        val surface: WorkspaceSurface,
        val value: Int,
    ) : WorkspacePanelEvent

    data class Fixed(
        val surface: WorkspaceSurface,
        val value: Int,
    ) : WorkspacePanelEvent

    data class PathMode(
        val value: CommitPathDisplayMode,
    ) : WorkspacePanelEvent

    data class PathMinimum(
        val value: Int,
    ) : WorkspacePanelEvent

    data class PathMaximum(
        val value: Int,
    ) : WorkspacePanelEvent

    data object Unlock : WorkspacePanelEvent
}
