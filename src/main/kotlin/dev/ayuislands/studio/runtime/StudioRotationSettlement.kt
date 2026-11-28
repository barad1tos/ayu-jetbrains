package dev.ayuislands.studio.runtime

import dev.ayuislands.rotation.RotationCompletionPlan
import dev.ayuislands.rotation.RotationStepPlanner
import dev.ayuislands.studio.persistence.CommitResolution
import dev.ayuislands.studio.persistence.PendingJournal
import dev.ayuislands.studio.persistence.PreparedToken
import dev.ayuislands.studio.persistence.RecoveryPurpose

/** Admit one prepared effect after the durable journal and current lease agree. */
internal fun admitRotationEffects(
    phase: RotationStepPhase,
    journal: PendingJournal,
    current: RotationStepContext,
): RotationStepPhase {
    if (phase !is RotationStepPhase.Preparing || !journal.matches(phase.work)) return phase
    val effect = RotationStepPhase.Applying(phase.work, journal.token)
    return if (current == phase.work.context && current.lease.documentRevision == journal.token.baseRevision) {
        effect
    } else {
        RotationStepPhase.Restoring(effect, RotationStepRestoration.LostLease)
    }
}

/** Acknowledgements carry the token, while the phase owns the original candidate. */
internal fun settleRotationEffects(
    phase: RotationStepPhase,
    token: PreparedToken,
    outcome: RotationEffectsOutcome,
    current: RotationStepContext,
): RotationStepPhase {
    if (phase !is RotationStepPhase.Applying || phase.token != token) return phase
    if (current != phase.work.context) return RotationStepPhase.Restoring(phase, RotationStepRestoration.LostLease)
    return when (outcome) {
        RotationEffectsOutcome.Rejected -> RotationStepPhase.Restoring(phase, RotationStepRestoration.NativeFailure)
        is RotationEffectsOutcome.Applied -> proposeRotationCommit(phase, outcome.completedAtEpochMillis)
    }
}

/** Store readback settles the exact proposal, even after the visible owner has moved. */
internal fun settleRotationCommit(
    phase: RotationStepPhase,
    token: PreparedToken,
    outcome: CommitResolution,
): RotationStepPhase {
    val commit =
        when (phase) {
            is RotationStepPhase.Committing -> phase
            is RotationStepPhase.Recovering -> phase.commit
            else -> return phase
        }
    if (commit.effect.token != token) return phase
    return when (outcome) {
        CommitResolution.Committed -> RotationStepPhase.Accepted(commit)
        CommitResolution.NotCommitted ->
            RotationStepPhase.Restoring(
                commit.effect,
                RotationStepRestoration.CommitRejected,
            )
        CommitResolution.Unknown ->
            if (phase is RotationStepPhase.Recovering) {
                phase
            } else {
                RotationStepPhase.Recovering(
                    commit,
                )
            }
    }
}

private fun PendingJournal.matches(work: RotationStepWork): Boolean =
    recovery.purpose == RecoveryPurpose.RotationStep(work.operationId) &&
        token.transactionId == work.operationId.toString() &&
        recovery.rotationStep == work.candidate

private fun proposeRotationCommit(
    effect: RotationStepPhase.Applying,
    completedAtEpochMillis: Long,
): RotationStepPhase {
    val work = effect.work
    val proposal =
        RotationStepPlanner.planCompletion(
            work.context.checkpoint,
            work.context.inputs,
            work.candidate,
            completedAtEpochMillis,
        )
    return when (proposal) {
        is RotationCompletionPlan.Ready -> RotationStepPhase.Committing(effect, proposal.checkpoint)
        is RotationCompletionPlan.Blocked ->
            RotationStepPhase.Restoring(effect, RotationStepRestoration.CompletionBlocked(proposal.reason))
    }
}
