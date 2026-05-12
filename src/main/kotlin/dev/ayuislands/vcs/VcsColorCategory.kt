package dev.ayuislands.vcs

/**
 * The eleven VCS color surfaces driven by user-controlled intensity.
 *
 * Grouped into four user-task sections — the same grouping that appears in the
 * `VCS` settings tab:
 *  - **Diff & File Status**: [DIFF_VIEWER], [PROJECT_VIEW_FILE_STATUS], [EDITOR_GUTTER]
 *  - **Merge & Conflict**: [CONFLICT_MARKERS], [MERGE_3WAY], [INLINE_DIFF_POPUP]
 *  - **Blame & History**: [BLAME_GUTTER], [LOCAL_HISTORY]
 *  - **Branch & Commit**: [BRANCH_INDICATOR], [BRANCHES_POPUP], [COMMIT_HIGHLIGHTS]
 *
 * The Branch & Commit categories are declared but not yet routed by the
 * applier — their entry list in [VcsColorPalette] is empty. Adding a category
 * here without wiring it into the applier is intentionally safe: categories
 * without a routed key contribute no UIManager / EditorColorsScheme writes
 * and therefore cannot regress live themes.
 */
enum class VcsColorCategory {
    DIFF_VIEWER,
    PROJECT_VIEW_FILE_STATUS,
    EDITOR_GUTTER,
    CONFLICT_MARKERS,
    MERGE_3WAY,
    INLINE_DIFF_POPUP,
    BLAME_GUTTER,
    LOCAL_HISTORY,
    BRANCH_INDICATOR,
    BRANCHES_POPUP,
    COMMIT_HIGHLIGHTS,
}
