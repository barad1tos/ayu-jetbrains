package dev.ayuislands.studio.runtime

import dev.ayuislands.rotation.RotationStepPlan
import dev.ayuislands.studio.model.AcceptancePlan
import dev.ayuislands.studio.model.AcceptanceRequest
import dev.ayuislands.studio.model.ApplyFailure
import dev.ayuislands.studio.model.StudioTarget
import java.util.UUID

/** The already validated user transaction, with its separately allocated runtime operation identity. */
internal data class RuntimeAcceptanceRequest(
    val operationId: UUID,
    val acceptance: AcceptanceRequest,
    val plan: AcceptancePlan.Commit,
)

internal data class FocusSnapshot(
    val target: StudioTarget,
    val generation: Long,
)

internal sealed interface RuntimePreparationPlan {
    /** The candidate owns the proposed checkpoint; a prepared step advances it only after successful effects. */
    data class Ready(
        val plan: AcceptancePlan.Commit,
    ) : RuntimePreparationPlan

    data class RandomRequired(
        val request: RotationStepPlan.RandomRequired,
    ) : RuntimePreparationPlan

    data class Blocked(
        val failure: ApplyFailure,
    ) : RuntimePreparationPlan
}
