package dev.ayuislands.studio.session

import dev.ayuislands.rotation.RotationRuntimeState
import dev.ayuislands.rotation.RotationScheduleCommit
import dev.ayuislands.studio.model.AcceptancePlan
import dev.ayuislands.studio.model.StudioDocument
import dev.ayuislands.studio.model.StudioGlowSelection
import dev.ayuislands.studio.model.StudioIntegrationSelection
import dev.ayuislands.studio.model.StudioRuntimeState
import dev.ayuislands.studio.persistence.RecoveryPurpose
import dev.ayuislands.studio.runtime.StudioRuntimeCoordinator

/** A retained schedule writes only its exact checkpoint; native focus may already belong to the next context. */
internal class StudioCheckpointLease private constructor(
    private val runtime: StudioRuntimeCoordinator,
    private val commit: RotationScheduleCommit,
) : StudioRuntimeLease {
    private var isReleased = false
    override val glow: StudioGlowHolds? = null
    override val accent: StudioAccentResult? = null
    override val observation: StudioNativeObservation? = null
    override val native: StudioNativeFacts
        get() = error("Checkpoint persistence has no native observations")

    override suspend fun confirmAccent(result: StudioAccentResult): Unit =
        error("Checkpoint persistence cannot confirm Accent")

    override suspend fun confirmTheme(transition: StudioThemeTransition): Unit =
        error("Checkpoint persistence cannot confirm a theme")

    override suspend fun isValid(): Boolean {
        val pending = runtime.state as? RotationRuntimeState.Applying ?: return false
        return !isReleased && pending.operation === commit && pending.snapshot.inputs.owner == commit.checkpoint.owner
    }

    override suspend fun settledRuntime(candidate: StudioDocument): StudioRuntimeState {
        check(isValid() && candidate.runtime.rotation == commit.checkpoint)
        return candidate.runtime
    }

    override suspend fun release(accepted: StudioDocument?) {
        isReleased = true
    }

    companion object {
        fun prepare(
            runtime: StudioRuntimeCoordinator,
            latest: StudioDocument,
            plan: AcceptancePlan.Commit,
            purpose: RecoveryPurpose.RotationSchedule,
        ): StudioAcceptancePreparation.Prepared {
            val applying = checkNotNull(runtime.state as? RotationRuntimeState.Applying)
            val commit = checkNotNull(applying.operation as? RotationScheduleCommit)
            check(commit.operationId == purpose.operationId && commit.checkpoint == plan.candidate.runtime.rotation)
            val owner = commit.checkpoint.owner
            check(StudioTargets.current(latest, owner.target)?.revision == owner.appearanceRevision)
            check(
                plan.candidate.copy(
                    header = latest.header,
                    runtime = plan.candidate.runtime.copy(rotation = latest.runtime.rotation),
                ) == latest,
            ) { "Checkpoint admission cannot change accepted preferences or unrelated runtime state" }
            check(plan.effects.isEmpty() && plan.nativeChoices.isEmpty() && plan.workspaceFields.isEmpty())
            check(plan.themeChange == null && plan.rotationStep == null && plan.lease == null)
            check(
                plan.glowSelection == StudioGlowSelection() &&
                    plan.integrationSelection == StudioIntegrationSelection(),
            )
            return StudioAcceptancePreparation.Prepared(StudioCheckpointLease(runtime, commit), plan)
        }
    }
}
