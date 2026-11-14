package dev.ayuislands.studio.model

import dev.ayuislands.rotation.RotationAcceptanceOrigin

internal sealed interface AcceptancePlan {
    data object NoChanges : AcceptancePlan

    data class Blocked(
        val reason: AcceptanceBlock,
    ) : AcceptancePlan

    data class Review(
        val items: StudioList<AcceptanceReview>,
    ) : AcceptancePlan

    /** Complete proposed state; the accepted baseline advances only if this operation commits. */
    data class Commit(
        val target: StudioTarget,
        val document: StudioDocument,
        val route: StudioEffectRoute,
        val limitations: StudioList<EffectLimitation>,
        val native: StudioList<NativeResolution>,
        val lease: AcceptanceLease,
        val rotationOrigin: RotationAcceptanceOrigin?,
    ) : AcceptancePlan
}
