package dev.ayuislands.studio.session

import dev.ayuislands.studio.model.AcceptedAppearance
import dev.ayuislands.studio.model.AppearanceSnapshot
import dev.ayuislands.studio.model.ConflictChoice
import dev.ayuislands.studio.model.DraftIntent
import dev.ayuislands.studio.model.DraftRecord
import dev.ayuislands.studio.model.FieldAddress
import dev.ayuislands.studio.model.FieldPart
import dev.ayuislands.studio.model.FieldResolution
import dev.ayuislands.studio.model.StudioFields
import dev.ayuislands.studio.model.StudioList

internal object StudioDraftRebase {
    fun rebase(
        draft: DraftRecord,
        latest: AcceptedAppearance,
    ): DraftRecord {
        if (draft.tracking.intent == DraftIntent.FULL_PROFILE) return draft
        val orders = mutableSetOf<FieldAddress>()
        val appearance =
            merge(draft.base.appearance, draft.appearance, latest.appearance, draft.tracking.touched, orders)
        val baselineTouches = StudioList((draft.tracking.touched + orders).distinct())
        val initial = merge(draft.base.appearance, draft.tracking.initial, latest.appearance, baselineTouches)
        val base = merge(draft.base.appearance, draft.base.appearance, latest.appearance, baselineTouches)
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
        orders: MutableSet<FieldAddress>? = null,
    ): AppearanceSnapshot =
        AppearanceSnapshot(
            mergeFields(base.accent, mine.accent, latest.accent, touched, orders),
            mergeFields(base.font, mine.font, latest.font, touched, orders),
            mergeFields(base.glow, mine.glow, latest.glow, touched, orders),
            mergeFields(base.syntax, mine.syntax, latest.syntax, touched, orders),
            mergeFields(base.vcs, mine.vcs, latest.vcs, touched, orders),
        )

    private fun mergeFields(
        base: StudioFields,
        mine: StudioFields,
        latest: StudioFields,
        touched: StudioList<FieldAddress>,
        orders: MutableSet<FieldAddress>?,
    ): StudioFields {
        val tentative = StudioConflictPolicy.merge(base, mine, latest, touched)
        if (tentative.conflicts.isEmpty()) return tentative.fields
        orders?.addAll(tentative.conflicts.map { it.address }.filter { it.part == FieldPart.MapOrder })
        // Retain the requested draft for review; this is not overwrite authorization.
        val retain = StudioList(tentative.conflicts.map { FieldResolution(it, ConflictChoice.USE_MINE) })
        return StudioConflictPolicy.merge(base, mine, latest, touched, retain).fields
    }
}
