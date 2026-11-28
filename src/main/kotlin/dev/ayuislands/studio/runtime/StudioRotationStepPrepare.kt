package dev.ayuislands.studio.runtime

import dev.ayuislands.rotation.RotationRuntimeCommand
import dev.ayuislands.rotation.RotationRuntimeSnapshot
import dev.ayuislands.rotation.RotationStepInputs
import dev.ayuislands.rotation.RotationStepPlan
import dev.ayuislands.rotation.RotationStepPlanner
import dev.ayuislands.studio.model.AcceptanceLease
import dev.ayuislands.studio.model.EffectOwner
import dev.ayuislands.studio.model.StudioDocument
import dev.ayuislands.studio.model.StudioList
import dev.ayuislands.studio.persistence.PrepareResult
import dev.ayuislands.studio.persistence.RecoveryPlan
import dev.ayuislands.studio.persistence.RecoveryPurpose
import dev.ayuislands.studio.persistence.StoreIssue
import dev.ayuislands.studio.persistence.StoreLoad
import dev.ayuislands.studio.persistence.StudioAppearanceStore

/** Reserve the frozen candidate and its current attempt before the caller can apply any Accent effect. */
internal fun prepareRotationStep(
    store: StudioAppearanceStore,
    request: RotationRuntimeCommand.StepDue,
    current: RotationRuntimeSnapshot,
    lease: AcceptanceLease,
    attempt: RotationStepAttempt,
): RotationStepAdmission {
    if (request.source != current) return RotationStepAdmission.Stale
    val accepted =
        when (val load = store.load()) {
            is StoreLoad.Ready -> load.document
            is StoreLoad.ReadOnly -> return RotationStepAdmission.StoreUnavailable(load.issue)
            is StoreLoad.Recovering -> return RotationStepAdmission.StoreUnavailable(StoreIssue.RECOVERY_REQUIRED)
            StoreLoad.Empty -> return RotationStepAdmission.Stale
        }
    if (accepted.header.revision != lease.documentRevision || accepted.runtime.rotation != current.checkpoint) {
        return RotationStepAdmission.Stale
    }
    val checkpoint = current.checkpoint
    val variant = current.availability.takeIf { it.pauseReasons.isEmpty() }?.variant
    val inputs = RotationStepInputs(checkpoint.owner, current.intent, variant)
    return when (val step = RotationStepPlanner.planStep(checkpoint, inputs, attempt.operationId, attempt.randomHex)) {
        is RotationStepPlan.Ready -> {
            val work =
                RotationStepWork(attempt.operationId, step.candidate, RotationStepContext(checkpoint, inputs, lease))
            prepareAccentStep(store, accepted, work)
        }
        is RotationStepPlan.RandomRequired -> RotationStepAdmission.NeedsRandom(step)
        is RotationStepPlan.Blocked -> RotationStepAdmission.Blocked(step.reason)
    }
}

private fun prepareAccentStep(
    store: StudioAppearanceStore,
    accepted: StudioDocument,
    work: RotationStepWork,
): RotationStepAdmission {
    val header =
        accepted.header.copy(
            revision = accepted.header.revision + 1,
            transactionId = work.operationId.toString(),
        )
    val recovery =
        RecoveryPlan(
            target = work.context.checkpoint.owner.target,
            owners = StudioList(listOf(EffectOwner.ACCENT)),
            rotationStep = work.candidate,
            purpose = RecoveryPurpose.RotationStep(work.operationId),
        )
    return when (val prepared = store.prepare(accepted.header.revision, accepted.copy(header = header), recovery)) {
        is PrepareResult.Prepared -> RotationStepAdmission.Prepared(RotationStepPhase.Preparing(work), prepared.journal)
        is PrepareResult.Rejected -> RotationStepAdmission.StoreUnavailable(prepared.issue, prepared.attemptedJournal)
    }
}
