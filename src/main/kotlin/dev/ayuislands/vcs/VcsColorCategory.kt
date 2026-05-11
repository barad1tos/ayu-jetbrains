package dev.ayuislands.vcs

/**
 * The eleven VCS color surfaces driven by Phase 40.2 user-controlled intensity.
 *
 * Grouped into four user-task sections — the same grouping that appears in the
 * `VCS` settings tab:
 *  - **Diff & File Status** (Wave 2): [DIFF_VIEWER], [PROJECT_VIEW_FILE_STATUS], [EDITOR_GUTTER]
 *  - **Merge & Conflict** (Wave 3): [CONFLICT_MARKERS], [MERGE_3WAY], [INLINE_DIFF_POPUP]
 *  - **Blame & History** (Wave 4): [BLAME_GUTTER], [LOCAL_HISTORY]
 *  - **Branch & Commit** (Wave 5 spike outcome): [BRANCH_INDICATOR], [BRANCHES_POPUP], [COMMIT_HIGHLIGHTS]
 *
 * Wave 1 declares the enum only; color-key bindings and write-API routing land
 * incrementally in waves 2–5. Adding a category here without wiring it into the
 * applier is intentionally safe — categories without a routed key contribute no
 * UIManager / EditorColorsScheme writes and therefore cannot regress live themes.
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
