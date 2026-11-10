package dev.ayuislands.studio.model

internal data class ApplyFailure(
    val code: String,
    val owner: EffectOwner? = null,
)

/** User-visible differences that remain after a qualified interrupted-operation recovery. */
internal enum class RecoveryNotice { PANEL_GEOMETRY_KEPT, }

internal sealed interface AcceptanceResult {
    data object NoChanges : AcceptanceResult

    data class ReviewRequired(
        val items: StudioList<AcceptanceReview>,
    ) : AcceptanceResult

    data class Blocked(
        val reasons: StudioList<AcceptanceBlock>,
    ) : AcceptanceResult

    data class Accepted(
        val revision: Long,
        val limitations: StudioList<EffectLimitation>,
    ) : AcceptanceResult

    data class AcceptedCleanupPending(
        val revision: Long,
        val operationId: String,
        val failure: ApplyFailure,
    ) : AcceptanceResult

    data class NotAccepted(
        val failure: ApplyFailure,
        val notices: StudioList<RecoveryNotice> = StudioList(emptyList()),
    ) : AcceptanceResult

    data class RecoveryRequired(
        val operationId: String,
        val failure: ApplyFailure,
    ) : AcceptanceResult
}
