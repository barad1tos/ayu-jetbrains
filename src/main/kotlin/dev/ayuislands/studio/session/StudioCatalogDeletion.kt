package dev.ayuislands.studio.session

import dev.ayuislands.studio.model.AcceptedAppearance
import dev.ayuislands.studio.model.CatalogApprovals
import dev.ayuislands.studio.model.CatalogIntent
import dev.ayuislands.studio.model.CatalogReview
import dev.ayuislands.studio.model.DeleteImpact
import dev.ayuislands.studio.model.DraftLink
import dev.ayuislands.studio.model.DraftRecord
import dev.ayuislands.studio.model.ProfileDefinition
import dev.ayuislands.studio.model.ProfileId
import dev.ayuislands.studio.model.SessionAvailability
import dev.ayuislands.studio.model.StudioDocument
import dev.ayuislands.studio.model.StudioList
import dev.ayuislands.studio.model.StudioTarget

internal object StudioCatalogDeletion {
    fun review(
        document: StudioDocument,
        before: ProfileDefinition.User,
        approvals: CatalogApprovals,
    ): List<CatalogReview> {
        val review = mutableListOf<CatalogReview>()
        if (document.catalog.singleOrNull { it.id == before.id } !=
            before
        ) {
            review.add(CatalogReview.DefinitionChanged(before.id))
        }
        val conflicts =
            document.sessions
                .filter { it.availability != SessionAvailability.RETIRED }
                .flatMap { it.work.catalog }
                .any { intent ->
                    when (intent) {
                        is CatalogIntent.Update -> intent.before.id == before.id
                        is CatalogIntent.Rename -> intent.before.id == before.id
                        is CatalogIntent.Create, is CatalogIntent.Delete -> false
                    }
                }
        if (conflicts) review.add(CatalogReview.ConflictingIntents(before.id))
        val impact = impact(document, before)
        if (impact !in approvals.deletions) review.add(CatalogReview.ConfirmDelete(impact))
        return review
    }

    fun detach(
        document: StudioDocument,
        profile: ProfileDefinition.User,
    ): StudioDocument =
        document.copy(
            currents =
                document.currents.copy(
                    global = detachCurrent(document.currents.global, profile.id),
                    projects =
                        StudioList(
                            document.currents.projects.map { it.copy(current = detachCurrent(it.current, profile.id)) },
                        ),
                ),
            sessions =
                StudioList(
                    document.sessions.map { session ->
                        if (session.availability == SessionAvailability.RETIRED) return@map session
                        session.copy(
                            work =
                                session.work.copy(
                                    drafts = StudioList(session.work.drafts.map { detachDraft(it, profile) }),
                                ),
                        )
                    },
                ),
        )

    private fun impact(
        document: StudioDocument,
        before: ProfileDefinition.User,
    ): DeleteImpact {
        val targets = mutableListOf<StudioTarget>()
        if (document.currents.global.source
                ?.id == before.id
        ) {
            targets.add(StudioTarget.Global)
        }
        targets.addAll(
            document.currents.projects
                .filter {
                    it.current.source?.id == before.id
                }.map { StudioTarget.Project(it.key) },
        )
        val drafts =
            document.sessions.filter { it.availability != SessionAvailability.RETIRED }.flatMap { session ->
                session.work.drafts
                    .filter {
                        it.source?.id == before.id || it.base.source?.id == before.id
                    }.map { DraftLink(session.id, it.id) }
            }
        return DeleteImpact(before, StudioList(targets), StudioList(drafts))
    }

    private fun detachCurrent(
        current: AcceptedAppearance,
        id: ProfileId,
    ): AcceptedAppearance = if (current.source?.id == id) current.copy(source = null) else current

    private fun detachDraft(
        draft: DraftRecord,
        profile: ProfileDefinition.User,
    ): DraftRecord =
        draft.copy(
            source = draft.source.takeUnless { it?.id == profile.id },
            base = detachCurrent(draft.base, profile.id),
            sourceName = if (draft.source?.id == profile.id) profile.name else draft.sourceName,
        )
}
