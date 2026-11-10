package dev.ayuislands.studio.runtime

import dev.ayuislands.rotation.ClockSample
import dev.ayuislands.rotation.FullIntervalReason
import dev.ayuislands.rotation.RotationAcceptanceOrigin
import dev.ayuislands.rotation.RotationCandidate
import dev.ayuislands.rotation.RotationCompletionPlan
import dev.ayuislands.rotation.RotationIntent
import dev.ayuislands.rotation.RotationPauseReason
import dev.ayuislands.rotation.RotationResume
import dev.ayuislands.rotation.RotationRuntimeCheckpoint
import dev.ayuislands.rotation.RotationSnapshot
import dev.ayuislands.rotation.RotationStepPlan
import dev.ayuislands.rotation.RotationStepPlanner
import dev.ayuislands.rotation.RuntimeInputs
import dev.ayuislands.rotation.RuntimeOwner
import dev.ayuislands.rotation.intent
import dev.ayuislands.rotation.intervalMillis
import dev.ayuislands.rotation.reasons
import dev.ayuislands.studio.model.AcceptancePlan
import dev.ayuislands.studio.model.ApplyFailure
import dev.ayuislands.studio.model.DraftIntent
import dev.ayuislands.studio.model.StudioDocument
import dev.ayuislands.studio.model.StudioGlowSelection
import dev.ayuislands.studio.model.StudioList
import dev.ayuislands.studio.model.StudioTarget
import dev.ayuislands.studio.session.StudioAccentPolicy
import dev.ayuislands.studio.session.StudioAcceptanceMerge
import dev.ayuislands.studio.session.StudioTargets

/** Pure preparation of a validated user transaction; no stored or live value becomes accepted here. */
internal object RuntimeAcceptancePolicy {
    fun plan(
        request: RuntimeAcceptanceRequest,
        current: RotationSnapshot,
        focus: FocusSnapshot,
        clock: ClockSample,
        randomHex: String? = null,
    ): RuntimePreparationPlan {
        val session = request.acceptance.session
        if (session.target != focus.target) {
            return if (session.work.shared.isNotEmpty() || request.acceptance.initialization != null) {
                RuntimePreparationPlan.Blocked(ApplyFailure("activate-target"))
            } else {
                RuntimePreparationPlan.Ready(
                    request.plan.copy(effects = StudioList(emptyList()), glowSelection = StudioGlowSelection()),
                )
            }
        }
        val origin = origin(request, current)
        if (origin == RotationAcceptanceOrigin.CATALOG_ONLY) return RuntimePreparationPlan.Ready(request.plan)
        val inputs = projectInputs(request.plan.candidate, session.target, current.inputs)
        var checkpoint = rebind(current.checkpoint, inputs.owner, origin)
        if (inputs.availability.effectiveFollowSystem) {
            checkpoint = checkpoint.copy(resume = RotationResume.FullInterval(FullIntervalReason.FOLLOW_SYSTEM))
            return ready(request.plan, checkpoint)
        }
        if (!inputs.intent.enabled) return ready(request.plan, checkpoint.copy(retryCandidate = null))
        if (origin == RotationAcceptanceOrigin.PROFILE_ACTIVATION &&
            current.inputs.availability.reasons
                .isNotEmpty()
        ) {
            checkpoint = checkpoint.copy(resume = RotationResume.FullInterval(FullIntervalReason.INTERRUPTED_HANDOFF))
        }
        if (!requestsStep(origin, current.inputs.intent, inputs.intent)) {
            return if (current.inputs.availability.effectiveFollowSystem) {
                prepareSchedule(request.plan, checkpoint, inputs, clock)
            } else {
                ready(request.plan, checkpoint)
            }
        }
        val pending = checkpoint.copy(retryCandidate = null)
        if (inputs.availability.reasons.singleOrNull() == RotationPauseReason.NO_PRO) {
            return ready(request.plan, pending.copy(resume = deferredResume(pending, origin)))
        }
        return prepareStep(request, pending, inputs, randomHex)
    }

    private fun origin(
        request: RuntimeAcceptanceRequest,
        current: RotationSnapshot,
    ): RotationAcceptanceOrigin {
        val session = request.acceptance.session
        val work = session.work
        val draft = work.drafts.single { it.id == work.selected }
        return when {
            draft.tracking.intent == DraftIntent.FULL_PROFILE -> {
                RotationAcceptanceOrigin.PROFILE_ACTIVATION
            }

            StudioAcceptanceMerge.hasAppearanceIntent(draft) -> {
                draft.tracking.rotationOrigin ?: RotationAcceptanceOrigin.LOCAL_APPEARANCE_EDIT
            }

            work.shared.isNotEmpty() -> {
                RotationAcceptanceOrigin.SHARED_POLICY_EDIT
            }

            initializesAppearance(request, current) -> {
                RotationAcceptanceOrigin.LOCAL_APPEARANCE_EDIT
            }

            else -> {
                RotationAcceptanceOrigin.CATALOG_ONLY
            }
        }
    }

    private fun initializesAppearance(
        request: RuntimeAcceptanceRequest,
        current: RotationSnapshot,
    ): Boolean {
        if (request.acceptance.initialization == null) return false
        val target = request.acceptance.session.target
        val accepted = requireNotNull(StudioTargets.current(request.plan.candidate, target))
        return RuntimeOwner(target, accepted.revision) != current.inputs.owner
    }

    fun projectInputs(
        candidate: StudioDocument,
        target: StudioTarget,
        current: RuntimeInputs,
    ): RuntimeInputs {
        val accepted = requireNotNull(StudioTargets.current(candidate, target))
        return current.copy(
            owner = RuntimeOwner(target, accepted.revision),
            appearance = accepted.appearance,
            availability =
                current.availability.copy(
                    effectiveFollowSystem =
                        StudioAccentPolicy.followsSystem(
                            accepted.appearance,
                            candidate.shared.systemAccentPolicy,
                        ),
                ),
        )
    }

    private fun rebind(
        checkpoint: RotationRuntimeCheckpoint,
        owner: RuntimeOwner,
        origin: RotationAcceptanceOrigin,
    ): RotationRuntimeCheckpoint =
        if (origin == RotationAcceptanceOrigin.PROFILE_ACTIVATION) {
            checkpoint.copy(owner = owner, output = null, retryCandidate = null)
        } else {
            checkpoint.copy(
                owner = owner,
                output = checkpoint.output?.copy(owner = owner),
                retryCandidate = checkpoint.retryCandidate?.copy(owner = owner),
            )
        }

    private fun requestsStep(
        origin: RotationAcceptanceOrigin,
        before: RotationIntent,
        after: RotationIntent,
    ): Boolean =
        when (origin) {
            RotationAcceptanceOrigin.EXPLICIT_ROTATION_ENABLE -> {
                !before.enabled && after.enabled
            }

            RotationAcceptanceOrigin.LOCAL_ROTATION_EDIT -> {
                before.modeRaw != after.modeRaw || before.intervalHoursRaw != after.intervalHoursRaw
            }

            RotationAcceptanceOrigin.LOCAL_APPEARANCE_EDIT, RotationAcceptanceOrigin.PROFILE_ACTIVATION,
            RotationAcceptanceOrigin.SHARED_POLICY_EDIT, RotationAcceptanceOrigin.CATALOG_ONLY,
            RotationAcceptanceOrigin.MIGRATION,
            -> {
                false
            }
        }

    private fun prepareStep(
        request: RuntimeAcceptanceRequest,
        checkpoint: RotationRuntimeCheckpoint,
        inputs: RuntimeInputs,
        randomHex: String?,
    ): RuntimePreparationPlan =
        when (val step = RotationStepPlanner.planStep(checkpoint, inputs, request.operationId, randomHex)) {
            is RotationStepPlan.Ready -> {
                ready(request.plan, checkpoint, step.candidate)
            }

            is RotationStepPlan.RandomRequired -> {
                RuntimePreparationPlan.RandomRequired(step)
            }

            is RotationStepPlan.Blocked -> {
                RuntimePreparationPlan.Blocked(ApplyFailure("rotation-${step.reason.name.lowercase()}"))
            }
        }

    private fun deferredResume(
        checkpoint: RotationRuntimeCheckpoint,
        origin: RotationAcceptanceOrigin,
    ): RotationResume =
        when {
            checkpoint.resume is RotationResume.FullInterval -> checkpoint.resume
            origin == RotationAcceptanceOrigin.EXPLICIT_ROTATION_ENABLE -> RotationResume.ImmediateEnable
            else -> RotationResume.ImmediateConfiguration
        }

    private fun prepareSchedule(
        plan: AcceptancePlan.Commit,
        checkpoint: RotationRuntimeCheckpoint,
        inputs: RuntimeInputs,
        clock: ClockSample,
    ): RuntimePreparationPlan {
        if (inputs.availability.reasons.isNotEmpty()) return ready(plan, checkpoint)
        return when (
            val scheduled =
                RotationStepPlanner.planSchedule(
                    checkpoint,
                    inputs.intent.intervalMillis,
                    clock.epochMillis,
                )
        ) {
            is RotationCompletionPlan.Ready -> {
                ready(plan, scheduled.checkpoint)
            }

            is RotationCompletionPlan.Blocked -> {
                RuntimePreparationPlan.Blocked(ApplyFailure("rotation-${scheduled.reason.name.lowercase()}"))
            }
        }
    }

    private fun ready(
        plan: AcceptancePlan.Commit,
        checkpoint: RotationRuntimeCheckpoint,
        step: RotationCandidate? = null,
    ): RuntimePreparationPlan.Ready =
        RuntimePreparationPlan.Ready(
            plan.copy(
                candidate = plan.candidate.copy(runtime = plan.candidate.runtime.copy(rotation = checkpoint)),
                rotationStep = step,
            ),
        )
}
