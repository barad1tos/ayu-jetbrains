package dev.ayuislands.studio.session

import dev.ayuislands.studio.model.AppearanceSnapshot
import dev.ayuislands.studio.model.CaptureDifference
import dev.ayuislands.studio.model.CatalogApprovals
import dev.ayuislands.studio.model.CatalogCommand
import dev.ayuislands.studio.model.CatalogIntent
import dev.ayuislands.studio.model.CatalogPlan
import dev.ayuislands.studio.model.CatalogReview
import dev.ayuislands.studio.model.CatalogStage
import dev.ayuislands.studio.model.DraftId
import dev.ayuislands.studio.model.ProfileDefinition
import dev.ayuislands.studio.model.ProfileId
import dev.ayuislands.studio.model.SessionAvailability
import dev.ayuislands.studio.model.SessionWork
import dev.ayuislands.studio.model.StudioDocument
import dev.ayuislands.studio.model.StudioList
import dev.ayuislands.studio.model.StudioSessionRecord

internal object StudioCatalogPolicy {
    fun stage(
        document: StudioDocument,
        work: SessionWork,
        command: CatalogCommand,
    ): CatalogStage =
        when (val result = StudioCatalogStaging.stage(document, work, command)) {
            is CatalogStage.Staged -> result.copy(work = rebaseWork(document, result.work))
            is CatalogStage.Rejected -> result
        }

    fun plan(
        document: StudioDocument,
        session: StudioSessionRecord,
        approvals: CatalogApprovals = CatalogApprovals(),
    ): CatalogPlan {
        val rebasedSession = session.copy(work = rebaseWork(document, session.work))
        val sessions =
            if (document.sessions.none { it.id == session.id }) {
                document.sessions + rebasedSession
            } else {
                document.sessions.map {
                    if (it.id ==
                        session.id
                    ) {
                        rebasedSession
                    } else {
                        it
                    }
                }
            }
        val withWork = document.copy(sessions = StudioList(sessions))
        val review = session.work.catalog.flatMap { validate(withWork, session.work, it, approvals) }
        if (review.isNotEmpty()) return CatalogPlan.Review(StudioList(review))
        val byId = session.work.catalog.groupBy(CatalogIntent::profileId)
        val retained =
            document.catalog.mapNotNull { profile ->
                val intents = byId[profile.id].orEmpty()
                if (intents.any { it is CatalogIntent.Delete }) return@mapNotNull null
                applyChanges(profile, intents)
            }
        val additions =
            session.work.catalog
                .filterIsInstance<CatalogIntent.Create>()
                .map { it.definition }
        var result = withWork.copy(catalog = StudioList(retained + additions))
        for ((before) in session.work.catalog.filterIsInstance<CatalogIntent.Delete>()) {
            result = StudioCatalogDeletion.detach(result, before)
        }
        val renamedIds =
            session.work.catalog
                .filterIsInstance<CatalogIntent.Rename>()
                .map { it.before.id }
                .toSet()
        val names = result.catalog.filter { it.id in renamedIds }.associate { it.id to it.name }
        return CatalogPlan.Ready(refreshDraftNames(result, names))
    }

    private fun refreshDraftNames(
        document: StudioDocument,
        names: Map<ProfileId, String>,
    ): StudioDocument {
        if (names.isEmpty()) return document
        val sessions =
            document.sessions.map { record ->
                if (record.availability == SessionAvailability.RETIRED) return@map record
                val drafts =
                    record.work.drafts.map { draft ->
                        val name = draft.source?.id?.let(names::get)
                        if (name == null || name == draft.sourceName) draft else draft.copy(sourceName = name)
                    }
                record.copy(work = record.work.copy(drafts = StudioList(drafts)))
            }
        return document.copy(sessions = StudioList(sessions))
    }

    fun rebaseWork(
        document: StudioDocument,
        work: SessionWork,
    ): SessionWork {
        val definitions =
            (
                document.catalog +
                    work.catalog.filterIsInstance<CatalogIntent.Create>().map { it.definition }
            ).associateBy { it.id }
        val acceptedIds = document.catalog.map { it.id }.toSet()
        val drafts =
            work.drafts.map { draft ->
                val definition = draft.source?.let { definitions[it.id] }
                draft.copy(
                    source = draft.source.takeIf { definition != null },
                    base = draft.base.copy(source = draft.base.source?.takeIf { it.id in acceptedIds }),
                    sourceName = definition?.name ?: draft.sourceName,
                )
            }
        return work.copy(drafts = StudioList(drafts))
    }

    private fun validate(
        document: StudioDocument,
        work: SessionWork,
        intent: CatalogIntent,
        approvals: CatalogApprovals,
    ): List<CatalogReview> {
        val profile = document.catalog.singleOrNull { it.id == intent.profileId }
        return when (intent) {
            is CatalogIntent.Create ->
                captureReview(work, intent, intent.draft, intent.definition.appearance, approvals) +
                    if (profile != null) listOf(CatalogReview.DefinitionChanged(profile.id)) else emptyList()
            is CatalogIntent.Update ->
                captureReview(work, intent, intent.draft, intent.after.appearance, approvals) +
                    if (profile !is ProfileDefinition.User || profile.appearance != intent.before.appearance) {
                        listOf(CatalogReview.DefinitionChanged(intent.before.id))
                    } else {
                        emptyList()
                    }
            is CatalogIntent.Rename ->
                if (profile !is ProfileDefinition.User ||
                    (profile.name != intent.before.name && profile.name != intent.name)
                ) {
                    listOf(CatalogReview.DefinitionChanged(intent.before.id))
                } else {
                    emptyList()
                }
            is CatalogIntent.Delete -> StudioCatalogDeletion.review(document, intent.before, approvals)
        }
    }

    private fun captureReview(
        work: SessionWork,
        intent: CatalogIntent,
        draftId: DraftId,
        captured: AppearanceSnapshot,
        approvals: CatalogApprovals,
    ): List<CatalogReview> {
        val draft = work.drafts.single { it.id == draftId }
        val difference = CaptureDifference(intent, draft.id, draft.appearance)
        if (draft.appearance == captured || difference in approvals.retained) return emptyList()
        return listOf(CatalogReview.StaleCapture(difference))
    }

    private fun applyChanges(
        profile: ProfileDefinition,
        intents: List<CatalogIntent>,
    ): ProfileDefinition {
        if (profile !is ProfileDefinition.User || intents.isEmpty()) return profile
        val update = intents.filterIsInstance<CatalogIntent.Update>().singleOrNull()
        val rename = intents.filterIsInstance<CatalogIntent.Rename>().singleOrNull()
        val name = rename?.name ?: profile.name
        val appearance = update?.after?.appearance ?: profile.appearance
        if (name == profile.name && appearance == profile.appearance) return profile
        return profile.copy(
            name = name,
            appearance = appearance,
            revision = profile.revision + 1,
        )
    }
}
