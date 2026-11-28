package dev.ayuislands.studio.runtime

import dev.ayuislands.rotation.RotationCandidate
import dev.ayuislands.rotation.RotationRuntimeCheckpoint
import dev.ayuislands.rotation.RotationStepBlock
import dev.ayuislands.rotation.RotationStepInputs
import dev.ayuislands.studio.model.AcceptanceLease
import dev.ayuislands.studio.persistence.PreparedToken
import java.util.UUID

internal data class RotationStepContext(
    val checkpoint: RotationRuntimeCheckpoint,
    val inputs: RotationStepInputs,
    val lease: AcceptanceLease,
)

/** The frozen candidate can survive retries. The operation owns only this attempt. */
internal data class RotationStepWork(
    val operationId: UUID,
    val candidate: RotationCandidate,
    val context: RotationStepContext,
)

internal sealed interface RotationStepPhase {
    data class Preparing(
        val work: RotationStepWork,
    ) : RotationStepPhase

    data class Applying(
        val work: RotationStepWork,
        val token: PreparedToken,
    ) : RotationStepPhase

    data class Committing(
        val effect: Applying,
        val checkpoint: RotationRuntimeCheckpoint,
    ) : RotationStepPhase

    data class Recovering(
        val commit: Committing,
    ) : RotationStepPhase

    data class Restoring(
        val effect: Applying,
        val reason: RotationStepRestoration,
    ) : RotationStepPhase

    /** Only publication and cleanup remain after the durable commit is confirmed. */
    data class Accepted(
        val commit: Committing,
    ) : RotationStepPhase
}

internal sealed interface RotationEffectsOutcome {
    data class Applied(
        val completedAtEpochMillis: Long,
    ) : RotationEffectsOutcome

    data object Rejected : RotationEffectsOutcome
}

internal sealed interface RotationStepRestoration {
    data object LostLease : RotationStepRestoration

    data object NativeFailure : RotationStepRestoration

    data object CommitRejected : RotationStepRestoration

    data class CompletionBlocked(
        val reason: RotationStepBlock,
    ) : RotationStepRestoration
}
