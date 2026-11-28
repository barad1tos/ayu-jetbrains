package dev.ayuislands.studio.runtime

import dev.ayuislands.rotation.RotationStepBlock
import dev.ayuislands.rotation.RotationStepPlan
import dev.ayuislands.studio.persistence.PendingJournal
import dev.ayuislands.studio.persistence.StoreIssue
import java.util.UUID

internal data class RotationStepAttempt(
    val operationId: UUID,
    val randomHex: String? = null,
)

internal sealed interface RotationStepAdmission {
    data class Prepared(
        val phase: RotationStepPhase.Preparing,
        val journal: PendingJournal,
    ) : RotationStepAdmission

    data class NeedsRandom(
        val request: RotationStepPlan.RandomRequired,
    ) : RotationStepAdmission

    data class Blocked(
        val reason: RotationStepBlock,
    ) : RotationStepAdmission

    data class StoreUnavailable(
        val issue: StoreIssue,
    ) : RotationStepAdmission

    data object Stale : RotationStepAdmission
}
