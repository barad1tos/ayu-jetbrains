package dev.ayuislands.studio.session

import dev.ayuislands.studio.model.AcceptedAppearance
import dev.ayuislands.studio.model.AppearanceSnapshot
import dev.ayuislands.studio.model.ConflictChoice
import dev.ayuislands.studio.model.DraftIntent
import dev.ayuislands.studio.model.DraftRecord
import dev.ayuislands.studio.model.FieldAddress
import dev.ayuislands.studio.model.FieldResolution
import dev.ayuislands.studio.model.StudioFields
import dev.ayuislands.studio.model.StudioList

internal object StudioDraftRebase {
    fun rebase(
        draft: DraftRecord,
        latest: AcceptedAppearance,
    ): DraftRecord {
        if (draft.tracking.intent == DraftIntent.FULL_PROFILE) return draft
        val appearance = merge(draft.base.appearance, draft.appearance, latest.appearance, draft.tracking.touched)
        val initial = merge(draft.base.appearance, draft.tracking.initial, latest.appearance, draft.tracking.touched)
        val base = merge(draft.base.appearance, draft.base.appearance, latest.appearance, draft.tracking.touched)
        return draft.copy(
            appearance = appearance,
            base = draft.base.copy(appearance = base),
            tracking = draft.tracking.copy(initial = initial),
        )
    }

    private fun merge(
        base: AppearanceSnapshot,
        mine: AppearanceSnapshot,
        latest: AppearanceSnapshot,
        touched: StudioList<FieldAddress>,
    ): AppearanceSnapshot =
        AppearanceSnapshot(
            mergeFields(base.accent, mine.accent, latest.accent, touched),
            mergeFields(base.font, mine.font, latest.font, touched),
            mergeFields(base.glow, mine.glow, latest.glow, touched),
            mergeFields(base.syntax, mine.syntax, latest.syntax, touched),
            mergeFields(base.vcs, mine.vcs, latest.vcs, touched),
        )

    private fun mergeFields(
        base: StudioFields,
        mine: StudioFields,
        latest: StudioFields,
        touched: StudioList<FieldAddress>,
    ): StudioFields {
        val tentative = StudioConflictPolicy.merge(base, mine, latest, touched)
        if (tentative.conflicts.isEmpty()) return tentative.fields
        // Retain the requested draft for review; this is not overwrite authorization.
        val retain = StudioList(tentative.conflicts.map { FieldResolution(it, ConflictChoice.USE_MINE) })
        return StudioConflictPolicy.merge(base, mine, latest, touched, retain).fields
    }
}
