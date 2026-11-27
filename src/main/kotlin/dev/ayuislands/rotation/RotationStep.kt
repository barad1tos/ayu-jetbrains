package dev.ayuislands.rotation

import dev.ayuislands.accent.AyuVariant
import java.util.UUID

/** The runtime resolves current availability before asking for a step. */
internal data class RotationStepInputs(
    val owner: RuntimeOwner,
    val intent: RotationIntent,
    val eligibleVariant: AyuVariant?,
)

internal enum class RotationStepBlock {
    DISABLED,
    OWNER_CHANGED,
    RECOVERY_PENDING,
    INELIGIBLE,
    SERIAL_OVERFLOW,
    DEADLINE_OVERFLOW,
    INVALID_COLOR,
    STALE_CANDIDATE,
}

internal sealed interface RotationStepPlan {
    data class Ready(
        val candidate: RotationCandidate,
        val intervalMillis: Long,
    ) : RotationStepPlan

    data class RandomRequired(
        val operationId: UUID,
        val variant: AyuVariant,
    ) : RotationStepPlan

    data class Blocked(
        val reason: RotationStepBlock,
    ) : RotationStepPlan
}

internal sealed interface RotationCompletionPlan {
    data class Ready(
        val checkpoint: RotationRuntimeCheckpoint,
    ) : RotationCompletionPlan

    data class Blocked(
        val reason: RotationStepBlock,
    ) : RotationCompletionPlan
}
