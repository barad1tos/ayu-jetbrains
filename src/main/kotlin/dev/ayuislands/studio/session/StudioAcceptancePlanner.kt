package dev.ayuislands.studio.session

import dev.ayuislands.studio.model.AcceptancePlan
import dev.ayuislands.studio.model.AcceptedAppearance
import dev.ayuislands.studio.model.ApplyContext
import dev.ayuislands.studio.model.ApplyPreflight
import dev.ayuislands.studio.model.ApplyRequest
import dev.ayuislands.studio.model.DraftRecord
import dev.ayuislands.studio.model.DraftTracking
import dev.ayuislands.studio.model.EffectObservation
import dev.ayuislands.studio.model.StudioDocument
import dev.ayuislands.studio.model.StudioList
import java.util.UUID

internal object StudioAcceptancePlanner {
    fun plan(
        request: ApplyRequest,
        latest: StudioDocument,
        context: ApplyContext,
        effects: EffectObservation,
        operation: UUID,
    ): AcceptancePlan =
        when (val result = StudioApplyPreflight.plan(request, latest, context, effects)) {
            is ApplyPreflight.Blocked -> AcceptancePlan.Blocked(result.reason)
            is ApplyPreflight.Review -> AcceptancePlan.Review(result.items)
            is ApplyPreflight.Ready -> {
                if (!result.candidate.hasWork) AcceptancePlan.NoChanges else prepare(result, request, operation)
            }
        }

    private fun prepare(
        ready: ApplyPreflight.Ready,
        request: ApplyRequest,
        operation: UUID,
    ): AcceptancePlan.Commit {
        val candidate = ready.candidate.document
        val target = request.session.target
        val current = requireNotNull(StudioTargets.current(candidate, target))
        val revision = ready.lease.documentRevision + 1
        val appliesAppearance = StudioAcceptanceMerge.hasAppearanceIntent(ready.candidate.draft)
        val sessions =
            candidate.sessions.map { record ->
                if (record.id != request.session.id) return@map record
                val drafts =
                    record.work.drafts.map { draft ->
                        when {
                            draft.id == record.work.selected ->
                                if (appliesAppearance) {
                                    acceptedDraft(
                                        draft,
                                        current,
                                    )
                                } else {
                                    draft
                                }
                            StudioDraftPolicy.isDirty(draft) ->
                                draft.copy(tracking = DraftTracking(draft.appearance, intent = draft.tracking.intent))
                            else -> draft
                        }
                    }
                record.copy(
                    generation = record.generation + 1,
                    baseDocumentRevision = revision,
                    work =
                        record.work.copy(
                            drafts = StudioList(drafts),
                            catalog = StudioList(emptyList()),
                            shared = StudioList(emptyList()),
                        ),
                )
            }
        return AcceptancePlan.Commit(
            target = target,
            document =
                candidate.copy(
                    header = candidate.header.copy(revision = revision, transactionId = operation.toString()),
                    sessions = StudioList(sessions),
                ),
            route = ready.route,
            limitations = ready.limitations,
            native = ready.native,
            lease = ready.lease,
            rotationOrigin = if (appliesAppearance) ready.candidate.draft.tracking.rotationOrigin else null,
        )
    }

    private fun acceptedDraft(
        draft: DraftRecord,
        current: AcceptedAppearance,
    ): DraftRecord =
        draft.copy(base = current, appearance = current.appearance, tracking = DraftTracking(current.appearance))
}
