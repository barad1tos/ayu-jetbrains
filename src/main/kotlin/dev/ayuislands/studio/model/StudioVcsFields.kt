package dev.ayuislands.studio.model

import dev.ayuislands.vcs.VcsColorCategory

/** Canonical raw intensity fields, including the six categories without a settings slider. */
internal val studioVcsFields =
    mapOf(
        "vcsDiffIntensity" to VcsColorCategory.DIFF_VIEWER,
        "vcsProjectViewIntensity" to VcsColorCategory.PROJECT_VIEW_FILE_STATUS,
        "vcsGutterIntensity" to VcsColorCategory.EDITOR_GUTTER,
        "vcsConflictMarkerIntensity" to VcsColorCategory.CONFLICT_MARKERS,
        "vcsMerge3WayIntensity" to VcsColorCategory.MERGE_3WAY,
        "vcsInlineDiffIntensity" to VcsColorCategory.INLINE_DIFF_POPUP,
        "vcsBlameIntensity" to VcsColorCategory.BLAME_GUTTER,
        "vcsLocalHistoryIntensity" to VcsColorCategory.LOCAL_HISTORY,
        "vcsBranchIndicatorIntensity" to VcsColorCategory.BRANCH_INDICATOR,
        "vcsBranchesPopupIntensity" to VcsColorCategory.BRANCHES_POPUP,
        "vcsCommitHighlightIntensity" to VcsColorCategory.COMMIT_HIGHLIGHTS,
    )
