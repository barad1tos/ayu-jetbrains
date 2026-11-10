package dev.ayuislands.studio.runtime

import dev.ayuislands.rotation.FullIntervalReason
import dev.ayuislands.rotation.RotationCompletionPlan
import dev.ayuislands.rotation.RotationIntent
import dev.ayuislands.rotation.RotationLoadOutcome
import dev.ayuislands.rotation.RotationResume
import dev.ayuislands.rotation.RotationRuntimeCheckpoint
import dev.ayuislands.rotation.RotationSnapshot
import dev.ayuislands.rotation.RotationStepPlanner
import dev.ayuislands.rotation.RuntimeFault
import dev.ayuislands.rotation.RuntimeInputs
import dev.ayuislands.rotation.RuntimeOwner
import dev.ayuislands.rotation.intervalMillis
import dev.ayuislands.studio.model.StudioDocument
import dev.ayuislands.studio.model.StudioTarget
import dev.ayuislands.studio.persistence.AyuSettingsView
import dev.ayuislands.studio.persistence.StudioLegacyProjection
import dev.ayuislands.studio.session.StudioAccentPolicy
import dev.ayuislands.studio.session.StudioTargets

/** Converts retained history and accepted Current without reading time or changing raw preferences. */
internal object StudioRotationProjection {
    fun importLegacy(
        document: StudioDocument,
        fresh: Boolean,
    ): RotationRuntimeCheckpoint {
        val settings = requireNotNull(StudioLegacyProjection.project(document, StudioTarget.Global)).settings
        val checkpoint = retained(settings, RuntimeOwner(StudioTarget.Global, document.currents.global.revision))
        if (!settings.accentRotationEnabled || fresh) return checkpoint
        val lastSwitch = settings.accentRotationLastSwitchMs
        if (lastSwitch == 0L) return checkpoint.copy(resume = RotationResume.NoHistory)
        if (lastSwitch < 0L) return repair(checkpoint)
        val intent =
            RotationIntent(
                enabled = true,
                settings.accentRotationMode,
                settings.accentRotationIntervalHours,
            )
        return when (val plan = RotationStepPlanner.planSchedule(checkpoint, intent.intervalMillis, lastSwitch)) {
            is RotationCompletionPlan.Ready -> plan.checkpoint
            is RotationCompletionPlan.Blocked -> repair(checkpoint)
        }
    }

    fun load(
        document: StudioDocument,
        target: StudioTarget,
        observation: RuntimeDispatch.Start,
    ): RotationLoadOutcome {
        val accepted =
            StudioTargets.current(document, target)
                ?: return RotationLoadOutcome.Unavailable(RuntimeFault.DurableRecovery("rotation-target-unavailable"))
        val owner = RuntimeOwner(target, accepted.revision)
        val checkpoint =
            document.runtime.rotation ?: repair(
                retained(requireNotNull(StudioLegacyProjection.project(document, target)).settings, owner),
            )
        return RotationLoadOutcome.Ready(
            RotationSnapshot(
                checkpoint,
                RuntimeInputs(
                    owner,
                    accepted.appearance,
                    observation.availability.copy(
                        effectiveFollowSystem =
                            StudioAccentPolicy.followsSystem(accepted.appearance, document.shared.systemAccentPolicy),
                    ),
                ),
                observation.generation,
            ),
        )
    }

    fun diagnostic(document: StudioDocument): RuntimeFault.DurableRecovery? =
        when {
            document.runtime.rotation == null -> RuntimeFault.DurableRecovery("rotation-checkpoint-missing")
            document.runtime.rotation.resume == RotationResume.FullInterval(FullIntervalReason.REPAIRED_SCHEDULE) ->
                RuntimeFault.DurableRecovery("rotation-schedule-invalid")
            else -> null
        }

    private fun retained(
        settings: AyuSettingsView,
        owner: RuntimeOwner,
    ): RotationRuntimeCheckpoint =
        RotationRuntimeCheckpoint(
            owner = owner,
            sequenceCursorRaw = settings.accentRotationPresetIndex,
            successfulStepSerial = 0,
            lastSuccessfulStepEpochMillis = settings.accentRotationLastSwitchMs.takeIf { it > 0 },
        )

    private fun repair(checkpoint: RotationRuntimeCheckpoint): RotationRuntimeCheckpoint =
        checkpoint.copy(resume = RotationResume.FullInterval(FullIntervalReason.REPAIRED_SCHEDULE))
}
