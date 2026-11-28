package dev.ayuislands.studio.persistence

import dev.ayuislands.rotation.RotationCompletionPlan
import dev.ayuislands.rotation.RotationStepInputs
import dev.ayuislands.rotation.RotationStepPlanner
import dev.ayuislands.studio.model.EffectOwner
import dev.ayuislands.studio.model.StudioRuntimeState

internal fun PendingJournal.planRotationRetry(
    previousRuntime: StudioRuntimeState,
    restoredAt: Long,
): PendingJournal? {
    val attempt = recovery.purpose as? RecoveryPurpose.RotationStep ?: return null
    if (!hasRestoredAccent()) return null
    val selected = recovery.rotationStep ?: return null
    val previous = previousRuntime.rotation ?: return null
    val inputs = RotationStepInputs(previous.owner, selected.intent, selected.variant)
    val plan = RotationStepPlanner.planRestoredRetry(previous, inputs, selected, restoredAt, attempt.operationId)
    if (plan !is RotationCompletionPlan.Ready) return null
    return copy(
        candidate = candidate.copy(runtime = previousRuntime.copy(rotation = plan.checkpoint)),
        recovery = recovery.copy(purpose = RecoveryPurpose.RotationRetry(attempt.operationId)),
    )
}

internal fun PendingJournal.hasRotationRetry(): Boolean {
    val purpose = recovery.purpose as? RecoveryPurpose.RotationRetry ?: return false
    if (!hasRestoredAccent()) return false
    val retry = candidate.runtime.rotation?.retry ?: return false
    return retry.restoredAttempt == purpose.operationId && retry.candidate == recovery.rotationStep
}

private fun PendingJournal.hasRestoredAccent(): Boolean =
    recovery.owners.singleOrNull() == EffectOwner.ACCENT && receipts.all(StudioReceiptPolicy::isTerminal)
