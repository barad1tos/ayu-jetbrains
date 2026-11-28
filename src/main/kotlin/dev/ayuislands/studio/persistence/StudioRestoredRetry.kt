package dev.ayuislands.studio.persistence

import dev.ayuislands.rotation.RotationCompletionPlan
import dev.ayuislands.rotation.RotationStepInputs
import dev.ayuislands.rotation.RotationStepPlanner
import dev.ayuislands.studio.model.EffectOwner

internal fun PendingJournal.planRotationRetry(restoredAt: Long): PendingJournal? {
    val attempt = recovery.purpose as? RecoveryPurpose.RotationStep ?: return null
    if (!hasRestoredAccent()) return null
    val selected = recovery.rotationStep ?: return null
    val runtime = candidate.runtime
    val previous = runtime.rotation ?: return null
    val inputs = RotationStepInputs(previous.owner, selected.intent, selected.variant)
    val plan = RotationStepPlanner.planRestoredRetry(previous, inputs, selected, restoredAt)
    if (plan !is RotationCompletionPlan.Ready) return null
    return copy(
        candidate = candidate.copy(runtime = runtime.copy(rotation = plan.checkpoint)),
        recovery = recovery.copy(purpose = RecoveryPurpose.RotationRetry(attempt.operationId)),
    )
}

internal fun PendingJournal.hasRotationRetry(): Boolean {
    if (recovery.purpose !is RecoveryPurpose.RotationRetry || !hasRestoredAccent()) return false
    val retry = candidate.runtime.rotation?.retry ?: return false
    return retry.failures > 0 && retry.candidate == recovery.rotationStep
}

private fun PendingJournal.hasRestoredAccent(): Boolean =
    recovery.owners.singleOrNull() == EffectOwner.ACCENT && receipts.all(StudioReceiptPolicy::isTerminal)
