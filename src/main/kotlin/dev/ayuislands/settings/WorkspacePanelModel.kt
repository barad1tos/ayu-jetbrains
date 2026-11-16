package dev.ayuislands.settings

internal enum class WorkspaceSurface(
    val modeField: String,
    val minimumField: String,
    val maximumField: String,
    val fixedField: String,
) {
    PROJECT(
        "projectPanelWidthMode",
        "projectPanelAutoFitMinWidth",
        "autoFitMaxWidth",
        "projectPanelFixedWidth",
    ),
    COMMIT(
        "commitPanelWidthMode",
        "commitPanelAutoFitMinWidth",
        "autoFitCommitMaxWidth",
        "commitPanelFixedWidth",
    ),
    GIT(
        "gitPanelWidthMode",
        "gitPanelAutoFitMinWidth",
        "gitPanelAutoFitMaxWidth",
        "gitPanelFixedWidth",
    ),
}

internal enum class WorkspaceToggle(
    val field: String,
) {
    EDITOR_VERTICAL_SCROLLBAR(
        "hideEditorVScrollbar",
    ),
    EDITOR_HORIZONTAL_SCROLLBAR(
        "hideEditorHScrollbar",
    ),
    PROJECT_ROOT_PATH(
        "hideProjectRootPath",
    ),
    PROJECT_HORIZONTAL_SCROLLBAR(
        "hideProjectViewHScrollbar",
    ),
}

internal enum class WorkspacePathField(
    val field: String,
) {
    MODE("commitPanelPathDisplayMode"),
    MINIMUM("commitPanelPathMinHiddenLevels"),
    MAXIMUM("commitPanelPathMaxHiddenLevels"),
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
