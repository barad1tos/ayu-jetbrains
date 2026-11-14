package dev.ayuislands.studio.model

internal data class PolicyConflict(
    val base: SystemAccentPolicy,
    val latest: SystemAccentPolicy,
    val mine: SystemAccentPolicy,
)

internal data class PolicyResolution(
    val conflict: PolicyConflict,
    val choice: ConflictChoice,
)

internal data class ExtensionConflict(
    val domain: StudioDomain,
    val base: StudioList<OpaqueRecord>,
    val latest: StudioList<OpaqueRecord>,
    val mine: StudioList<OpaqueRecord>,
)

internal data class ExtensionResolution(
    val conflict: ExtensionConflict,
    val choice: ConflictChoice,
)

internal data class AcceptanceChoices(
    val fields: StudioList<FieldResolution> = StudioList(emptyList()),
    val policy: PolicyResolution? = null,
    val extensions: StudioList<ExtensionResolution> = StudioList(emptyList()),
    val catalog: CatalogApprovals = CatalogApprovals(),
    val native: StudioList<NativeResolution> = StudioList(emptyList()),
)

internal sealed interface AcceptanceReview {
    data class LiveSession(
        val session: String,
    ) : AcceptanceReview

    data class HiddenDraft(
        val draft: DraftId,
    ) : AcceptanceReview

    data class Catalog(
        val item: CatalogReview,
    ) : AcceptanceReview

    data class Field(
        val conflict: FieldConflict,
    ) : AcceptanceReview

    data class Policy(
        val conflict: PolicyConflict,
    ) : AcceptanceReview

    data class Extensions(
        val conflict: ExtensionConflict,
    ) : AcceptanceReview

    data class Native(
        val change: NativeSurfaceChange,
    ) : AcceptanceReview
}
