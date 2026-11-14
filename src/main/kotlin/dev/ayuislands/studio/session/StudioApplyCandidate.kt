package dev.ayuislands.studio.session

import dev.ayuislands.studio.model.AcceptanceReview
import dev.ayuislands.studio.model.ApplyCandidate
import dev.ayuislands.studio.model.ApplyContext
import dev.ayuislands.studio.model.ApplyOrigin
import dev.ayuislands.studio.model.ApplyRequest
import dev.ayuislands.studio.model.CatalogIntent
import dev.ayuislands.studio.model.CatalogPlan
import dev.ayuislands.studio.model.DraftIntent
import dev.ayuislands.studio.model.DraftRecord
import dev.ayuislands.studio.model.SessionAvailability
import dev.ayuislands.studio.model.SessionWork
import dev.ayuislands.studio.model.StudioDocument
import dev.ayuislands.studio.model.StudioList

/** Resolves persisted intent before capability checks, preparation or native effects. */
internal object StudioApplyCandidate {
    fun plan(
        request: ApplyRequest,
        latest: StudioDocument,
        context: ApplyContext,
    ): ApplyCandidate {
        val session = request.session
        StudioAcceptanceAdmission.block(session, request.targetGeneration, latest, context.observation)?.let {
            return ApplyCandidate.Blocked(it)
        }
        val work = session.work
        val draft = work.drafts.single { it.id == work.selected }
        val review = reviewDrafts(request, context)
        if (!hasPending(work)) {
            return if (review.isEmpty()) ApplyCandidate.Ready(latest, draft, false) else reviewResult(review)
        }

        val catalog = catalogCandidate(request, latest, review)
        val current = requireNotNull(StudioTargets.current(latest, session.target))
        val appearance = StudioAcceptanceMerge.appearance(draft, current.appearance, request.choices, review)
        val shared = StudioSharedMerge.merge(latest.shared, work.shared, request.choices, review)
        if (review.isNotEmpty()) return reviewResult(review)

        val proposed = catalog.copy(shared = shared)
        if (!StudioAcceptanceMerge.hasAppearanceIntent(draft)) return ApplyCandidate.Ready(proposed, draft, true)
        val retained = requireNotNull(StudioTargets.current(proposed, session.target))
        val source =
            if (draft.tracking.intent == DraftIntent.FULL_PROFILE) {
                draft.source?.takeIf { reference -> proposed.catalog.any { it.id == reference.id } }
            } else {
                if (appearance == current.appearance) return ApplyCandidate.Ready(proposed, draft, true)
                retained.source
            }
        val accepted = current.copy(appearance = appearance, revision = current.revision + 1, source = source)
        return ApplyCandidate.Ready(StudioTargets.replace(proposed, session.target, accepted), draft, true)
    }

    private fun reviewDrafts(
        request: ApplyRequest,
        context: ApplyContext,
    ): MutableList<AcceptanceReview> {
        val session = request.session
        val work = session.work
        val review = mutableListOf<AcceptanceReview>()
        if (request.origin == ApplyOrigin.QUICK_SWITCHER) {
            context.liveSessions
                .filter { it.id != session.id && it.availability == SessionAvailability.LIVE && hasPending(it.work) }
                .mapTo(review) { AcceptanceReview.LiveSession(it.id) }
        }
        StudioDraftPolicy
            .hiddenDirty(work)
            .filterNot { captured(it, work.catalog) }
            .mapTo(review) { AcceptanceReview.HiddenDraft(it.id) }
        return review
    }

    fun hasPending(work: SessionWork): Boolean =
        work.catalog.isNotEmpty() ||
            work.shared.isNotEmpty() ||
            work.drafts.any {
                if (it.id ==
                    work.selected
                ) {
                    StudioAcceptanceMerge.hasAppearanceIntent(it)
                } else {
                    StudioDraftPolicy.isDirty(it)
                }
            }

    private fun reviewResult(items: List<AcceptanceReview>): ApplyCandidate.Review =
        ApplyCandidate.Review(StudioList(items))

    private fun catalogCandidate(
        request: ApplyRequest,
        latest: StudioDocument,
        review: MutableList<AcceptanceReview>,
    ): StudioDocument =
        when (val result = StudioCatalogPolicy.plan(latest, request.session, request.choices.catalog)) {
            is CatalogPlan.Ready -> result.document
            is CatalogPlan.Review -> {
                result.items.mapTo(review, AcceptanceReview::Catalog)
                latest
            }
        }

    private fun captured(
        draft: DraftRecord,
        intents: List<CatalogIntent>,
    ): Boolean =
        draft.tracking.reapply.isEmpty() &&
            intents.any { intent ->
                when (intent) {
                    is CatalogIntent.Create ->
                        intent.draft == draft.id &&
                            intent.definition.appearance == draft.appearance
                    is CatalogIntent.Update -> intent.draft == draft.id && intent.after.appearance == draft.appearance
                    is CatalogIntent.Delete, is CatalogIntent.Rename -> false
                }
            }
}
