package dev.ayuislands.studio.model

internal sealed interface CatalogCommand {
    data class Create(
        val draft: DraftId,
        val id: ProfileId,
        val name: String,
    ) : CatalogCommand

    data class Update(
        val draft: DraftId,
        val id: ProfileId,
    ) : CatalogCommand

    data class Rename(
        val id: ProfileId,
        val name: String,
    ) : CatalogCommand

    data class Delete(
        val id: ProfileId,
    ) : CatalogCommand

    data class Unstage(
        val index: Int,
    ) : CatalogCommand
}

internal enum class CatalogError {
    INVALID_NAME,
    FACTORY_LOCKED,
    MISSING_PROFILE,
    MISSING_DRAFT,
    DUPLICATE_ID,
    MISSING_INTENT,
}

internal sealed interface CatalogStage {
    data class Staged(
        val work: SessionWork,
    ) : CatalogStage

    data class Rejected(
        val error: CatalogError,
    ) : CatalogStage
}

internal data class DraftLink(
    val session: String,
    val draft: DraftId,
)

internal data class DeleteImpact(
    val definition: ProfileDefinition.User,
    val targets: StudioList<StudioTarget>,
    val drafts: StudioList<DraftLink>,
)

internal data class CaptureDifference(
    val intent: CatalogIntent,
    val draft: DraftId,
    val latest: AppearanceSnapshot,
)

internal sealed interface CatalogReview {
    data class MissingDraft(
        val id: DraftId,
    ) : CatalogReview

    data class StaleCapture(
        val difference: CaptureDifference,
    ) : CatalogReview

    data class DefinitionChanged(
        val id: ProfileId,
    ) : CatalogReview

    data class ConflictingIntents(
        val id: ProfileId,
    ) : CatalogReview

    data class ConfirmDelete(
        val impact: DeleteImpact,
    ) : CatalogReview
}

internal data class CatalogApprovals(
    val deletions: StudioList<DeleteImpact> = StudioList(emptyList()),
    val retained: StudioList<CaptureDifference> = StudioList(emptyList()),
)

internal sealed interface CatalogPlan {
    data class Ready(
        val document: StudioDocument,
    ) : CatalogPlan

    data class Review(
        val items: StudioList<CatalogReview>,
    ) : CatalogPlan
}
