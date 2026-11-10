package dev.ayuislands.studio.session

import dev.ayuislands.studio.model.AcceptanceChoices
import dev.ayuislands.studio.model.AcceptanceReview
import dev.ayuislands.studio.model.AppearanceSnapshot
import dev.ayuislands.studio.model.ConflictChoice
import dev.ayuislands.studio.model.DraftIntent
import dev.ayuislands.studio.model.DraftRecord
import dev.ayuislands.studio.model.ExtensionConflict
import dev.ayuislands.studio.model.FieldAddress
import dev.ayuislands.studio.model.StudioDomain
import dev.ayuislands.studio.model.StudioFields
import dev.ayuislands.studio.model.StudioList

internal object StudioAcceptanceMerge {
    fun appearance(
        draft: DraftRecord,
        latest: AppearanceSnapshot,
        choices: AcceptanceChoices,
        review: MutableList<AcceptanceReview>,
    ): AppearanceSnapshot =
        AppearanceSnapshot(
            mergeDomain(StudioDomain.ACCENT, draft, latest.accent, choices, review),
            mergeDomain(StudioDomain.FONT, draft, latest.font, choices, review),
            mergeDomain(StudioDomain.GLOW, draft, latest.glow, choices, review),
            mergeDomain(StudioDomain.SYNTAX, draft, latest.syntax, choices, review),
            mergeDomain(StudioDomain.VCS, draft, latest.vcs, choices, review),
        )

    private fun mergeDomain(
        domain: StudioDomain,
        draft: DraftRecord,
        latest: StudioFields,
        choices: AcceptanceChoices,
        review: MutableList<AcceptanceReview>,
    ): StudioFields {
        val before = fields(draft.base.appearance, domain)
        val mine = fields(draft.appearance, domain)
        val touched =
            if (draft.tracking.intent == DraftIntent.FULL_PROFILE) {
                mine.records.map { FieldAddress(it.key) }
            } else {
                draft.tracking.touched
            }
        val result = StudioConflictPolicy.merge(before, mine, latest, StudioList(touched), choices.fields)
        review.addAll(result.conflicts.map(AcceptanceReview::Field))
        if (draft.tracking.intent != DraftIntent.FULL_PROFILE) return result.fields
        val conflict = ExtensionConflict(domain, before.extensions, latest.extensions, mine.extensions)
        val extensions =
            when {
                mine.extensions == latest.extensions -> latest.extensions
                latest.extensions == before.extensions -> mine.extensions
                else ->
                    when (choices.extensions.singleOrNull { it.conflict == conflict }?.choice) {
                        ConflictChoice.KEEP_LATEST -> latest.extensions
                        ConflictChoice.USE_MINE -> mine.extensions
                        null -> {
                            review.add(AcceptanceReview.Extensions(conflict))
                            latest.extensions
                        }
                    }
            }
        return result.fields.copy(extensions = extensions)
    }

    fun fields(
        appearance: AppearanceSnapshot,
        domain: StudioDomain,
    ): StudioFields =
        when (domain) {
            StudioDomain.ACCENT -> appearance.accent
            StudioDomain.FONT -> appearance.font
            StudioDomain.GLOW -> appearance.glow
            StudioDomain.SYNTAX -> appearance.syntax
            StudioDomain.VCS -> appearance.vcs
        }

    fun changed(
        before: StudioFields,
        after: StudioFields,
    ): Boolean =
        before.records.map { it.key to it.value } != after.records.map { it.key to it.value } ||
            before.extensions != after.extensions

    fun hasAppearanceIntent(draft: DraftRecord): Boolean =
        draft.tracking.intent == DraftIntent.FULL_PROFILE || StudioDraftPolicy.isDirty(draft)

    fun reapplyOwners(draft: DraftRecord): List<StudioDomain> {
        val keys =
            draft.tracking.reapply
                .map { it.field }
                .toSet()
        return StudioDomain.entries.filter { domain -> fields(draft.appearance, domain).records.any { it.key in keys } }
    }
}
