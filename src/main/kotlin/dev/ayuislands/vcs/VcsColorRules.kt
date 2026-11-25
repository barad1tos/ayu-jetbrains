package dev.ayuislands.vcs

/** Interprets the same VCS choices for the legacy state and Studio drafts. */
internal object VcsColorRules {
    fun preset(
        category: VcsColorCategory,
        diff: String?,
        merge: String?,
        blame: String?,
    ): VcsColorPreset =
        VcsColorPreset.byName(
            when (category) {
                VcsColorCategory.DIFF_VIEWER,
                VcsColorCategory.PROJECT_VIEW_FILE_STATUS,
                VcsColorCategory.EDITOR_GUTTER,
                -> diff

                VcsColorCategory.CONFLICT_MARKERS,
                VcsColorCategory.MERGE_3WAY,
                VcsColorCategory.INLINE_DIFF_POPUP,
                -> merge

                VcsColorCategory.BLAME_GUTTER,
                VcsColorCategory.LOCAL_HISTORY,
                -> blame

                VcsColorCategory.BRANCH_INDICATOR,
                VcsColorCategory.BRANCHES_POPUP,
                VcsColorCategory.COMMIT_HIGHLIGHTS,
                -> null
            },
        )

    fun intensity(
        enabled: Boolean,
        category: VcsColorCategory,
        preset: VcsColorPreset,
        customValue: () -> Int,
    ): VcsIntensity {
        if (!enabled) return VcsIntensity.of(0)
        return VcsIntensity.of(if (preset == VcsColorPreset.CUSTOM) customValue() else preset.intensityFor(category))
    }
}
