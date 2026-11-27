package dev.ayuislands.rotation

import dev.ayuislands.accent.AYU_ACCENT_PRESETS
import dev.ayuislands.accent.AccentHex
import java.util.UUID

internal object RotationStepPlanner {
    fun planStep(
        checkpoint: RotationRuntimeCheckpoint,
        inputs: RotationStepInputs,
        operationId: UUID,
        randomHex: String? = null,
    ): RotationStepPlan {
        blocked(checkpoint, inputs)?.let { return RotationStepPlan.Blocked(it) }
        val intent = inputs.intent
        val mode = AccentRotationMode.fromName(intent.modeRaw)
        val interval = rotationIntervalMillis(intent.intervalHoursRaw)
        val variant = checkNotNull(inputs.eligibleVariant)
        val retry = checkpoint.retryCandidate?.takeIf { matches(it, inputs) }
        if (retry != null) {
            if (AccentHex.of(retry.hex) == null) return RotationStepPlan.Blocked(RotationStepBlock.INVALID_COLOR)
            return RotationStepPlan.Ready(retry, interval)
        }

        val (cursor, color) =
            when (mode) {
                AccentRotationMode.PRESET -> {
                    val current = Math.floorMod(checkpoint.sequenceCursorRaw, AYU_ACCENT_PRESETS.size)
                    nextPresetHex(current)
                }
                AccentRotationMode.RANDOM -> {
                    if (randomHex == null) return RotationStepPlan.RandomRequired(operationId, variant)
                    checkpoint.sequenceCursorRaw to randomHex
                }
            }
        val hex = AccentHex.of(color) ?: return RotationStepPlan.Blocked(RotationStepBlock.INVALID_COLOR)
        return RotationStepPlan.Ready(
            RotationCandidate(operationId, inputs.owner, intent, mode, variant, hex.value, cursor),
            interval,
        )
    }

    /** Propose history for the applied candidate. The caller commits it with the effect transaction. */
    fun planCompletion(
        checkpoint: RotationRuntimeCheckpoint,
        inputs: RotationStepInputs,
        candidate: RotationCandidate,
        completedAtEpochMillis: Long,
    ): RotationCompletionPlan {
        blocked(checkpoint, inputs)?.let { return RotationCompletionPlan.Blocked(it) }
        if (!matches(candidate, inputs)) return RotationCompletionPlan.Blocked(RotationStepBlock.STALE_CANDIDATE)
        if (AccentHex.of(candidate.hex) == null) return RotationCompletionPlan.Blocked(RotationStepBlock.INVALID_COLOR)
        val interval = rotationIntervalMillis(inputs.intent.intervalHoursRaw)
        val cursor =
            if (candidate.mode == AccentRotationMode.PRESET) candidate.nextCursor else checkpoint.sequenceCursorRaw
        return when (val proposed = planSchedule(checkpoint, interval, completedAtEpochMillis)) {
            is RotationCompletionPlan.Blocked -> proposed
            is RotationCompletionPlan.Ready ->
                RotationCompletionPlan.Ready(
                    proposed.checkpoint.copy(
                        sequenceCursorRaw = cursor,
                        successfulStepSerial = checkpoint.successfulStepSerial + 1,
                        lastSuccessfulStepEpochMillis = completedAtEpochMillis,
                        output = RotationOutput(candidate.owner, candidate.variant, candidate.hex),
                        retryCandidate = null,
                    ),
                )
        }
    }

    fun planSchedule(
        checkpoint: RotationRuntimeCheckpoint,
        intervalMillis: Long,
        scheduledAtEpochMillis: Long,
    ): RotationCompletionPlan {
        val serial = checkpoint.schedule?.scheduleSerial ?: 0
        val schedule = planRotationAppointment(scheduledAtEpochMillis, intervalMillis, serial)
        if (schedule == null) {
            val reason =
                if (serial == Long.MAX_VALUE) RotationStepBlock.SERIAL_OVERFLOW else RotationStepBlock.DEADLINE_OVERFLOW
            return RotationCompletionPlan.Blocked(reason)
        }
        return RotationCompletionPlan.Ready(checkpoint.copy(schedule = schedule, resume = RotationResume.KeepDeadline))
    }

    private fun matches(
        candidate: RotationCandidate,
        inputs: RotationStepInputs,
    ): Boolean =
        candidate.owner == inputs.owner &&
            candidate.intent == inputs.intent &&
            candidate.mode == AccentRotationMode.fromName(inputs.intent.modeRaw) &&
            candidate.variant == inputs.eligibleVariant

    private fun blocked(
        checkpoint: RotationRuntimeCheckpoint,
        inputs: RotationStepInputs,
    ): RotationStepBlock? =
        when {
            !inputs.intent.enabled -> RotationStepBlock.DISABLED
            checkpoint.owner != inputs.owner -> RotationStepBlock.OWNER_CHANGED
            checkpoint.recoveryFault != null -> RotationStepBlock.RECOVERY_PENDING
            inputs.eligibleVariant == null -> RotationStepBlock.INELIGIBLE
            checkpoint.successfulStepSerial == Long.MAX_VALUE ||
                checkpoint.schedule?.scheduleSerial == Long.MAX_VALUE -> RotationStepBlock.SERIAL_OVERFLOW
            else -> null
        }
}
