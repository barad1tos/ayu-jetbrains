package dev.ayuislands.studio.model

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
