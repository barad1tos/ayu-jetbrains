package dev.ayuislands.rotation

import dev.ayuislands.accent.AYU_ACCENT_PRESETS
import dev.ayuislands.accent.AccentColor
import dev.ayuislands.accent.AccentHex
import java.util.UUID

/** Selects immutable output only; the accepting transaction advances history after successful effects. */
internal object RotationStepPlanner {
    fun planStep(
        checkpoint: RotationRuntimeCheckpoint,
        inputs: RuntimeInputs,
        operationId: UUID,
        randomHex: String?,
        presets: List<AccentColor> = AYU_ACCENT_PRESETS,
    ): RotationStepPlan {
        val intent = inputs.intent
        val blocked = block(checkpoint, inputs, intent)
        if (blocked != null) return RotationStepPlan.Blocked(blocked)
        val mode = AccentRotationMode.fromName(intent.modeRaw)
        val variant = checkNotNull(inputs.availability.variant)
        val retry =
            checkpoint.retryCandidate?.takeIf {
                matchesInputs(it, inputs, intent)
            }
        if (retry != null) {
            return RotationStepPlan.Ready(retry, intent.intervalMillis)
        }
        val (nextCursor, hex) =
            when (mode) {
                AccentRotationMode.PRESET -> {
                    if (presets.isEmpty()) return RotationStepPlan.Blocked(RotationStepBlock.EMPTY_CATALOG)
                    val next = (Math.floorMod(checkpoint.sequenceCursorRaw, presets.size) + 1) % presets.size
                    next to presets[next].hex
                }

                AccentRotationMode.RANDOM -> {
                    if (randomHex == null) return RotationStepPlan.RandomRequired(operationId, variant)
                    checkpoint.sequenceCursorRaw to randomHex
                }
            }
        if (AccentHex.of(hex) == null) return RotationStepPlan.Blocked(RotationStepBlock.INVALID_COLOR)
        return RotationStepPlan.Ready(
            RotationCandidate(operationId, inputs.owner, intent, mode, variant, hex, nextCursor),
            intent.intervalMillis,
        )
    }

    /** Proposes history for the same prepared transaction; storage acknowledgement alone makes it authoritative. */
    fun planCompletion(
        checkpoint: RotationRuntimeCheckpoint,
        inputs: RuntimeInputs,
        candidate: RotationCandidate,
        completedAtEpochMillis: Long,
    ): RotationCompletionPlan {
        val intent = inputs.intent
        val blocked = block(checkpoint, inputs, intent)
        if (blocked != null) return RotationCompletionPlan.Blocked(blocked)
        if (!matchesInputs(candidate, inputs, intent)) {
            return RotationCompletionPlan.Blocked(RotationStepBlock.STALE_CANDIDATE)
        }
        val scheduled = planSchedule(checkpoint, intent.intervalMillis, completedAtEpochMillis)
        if (scheduled is RotationCompletionPlan.Blocked) return scheduled
        val proposed = (scheduled as RotationCompletionPlan.Ready).checkpoint
        val cursor =
            when (candidate.mode) {
                AccentRotationMode.PRESET -> candidate.nextCursor
                AccentRotationMode.RANDOM -> checkpoint.sequenceCursorRaw
            }
        return RotationCompletionPlan.Ready(
            proposed.copy(
                sequenceCursorRaw = cursor,
                successfulStepSerial = checkpoint.successfulStepSerial + 1,
                lastSuccessfulStepEpochMillis = completedAtEpochMillis,
                output = RotationOutput(candidate.owner, candidate.variant, candidate.hex),
                retryCandidate = null,
            ),
        )
    }

    /** Proposes one appointment without advancing accepted color history. */
    fun planSchedule(
        checkpoint: RotationRuntimeCheckpoint,
        intervalMillis: Long,
        scheduledAtEpochMillis: Long,
    ): RotationCompletionPlan {
        require(intervalMillis > 0) { "A rotation interval must be positive" }
        val serial = maxOf(0L, checkpoint.schedule?.scheduleSerial ?: 0)
        if (serial == Long.MAX_VALUE) return RotationCompletionPlan.Blocked(RotationStepBlock.SERIAL_OVERFLOW)
        if (scheduledAtEpochMillis > Long.MAX_VALUE - intervalMillis) {
            return RotationCompletionPlan.Blocked(RotationStepBlock.DEADLINE_OVERFLOW)
        }
        return RotationCompletionPlan.Ready(
            checkpoint.copy(
                schedule =
                    RotationSchedule(
                        scheduledAtEpochMillis,
                        scheduledAtEpochMillis + intervalMillis,
                        intervalMillis,
                        serial + 1,
                    ),
                resume = RotationResume.KeepDeadline,
            ),
        )
    }

    private fun matchesInputs(
        candidate: RotationCandidate,
        inputs: RuntimeInputs,
        intent: RotationIntent,
    ): Boolean =
        candidate.owner == inputs.owner &&
            candidate.intent == intent &&
            candidate.variant == inputs.availability.variant

    private fun block(
        checkpoint: RotationRuntimeCheckpoint,
        inputs: RuntimeInputs,
        intent: RotationIntent,
    ): RotationStepBlock? =
        when {
            !intent.enabled -> {
                RotationStepBlock.DISABLED
            }

            checkpoint.owner != inputs.owner -> {
                RotationStepBlock.OWNER_CHANGED
            }

            checkpoint.recoveryFault != null -> {
                RotationStepBlock.RECOVERY_PENDING
            }

            inputs.availability.reasons.isNotEmpty() -> {
                RotationStepBlock.INELIGIBLE
            }

            checkpoint.successfulStepSerial == Long.MAX_VALUE ||
                checkpoint.schedule?.scheduleSerial == Long.MAX_VALUE -> {
                RotationStepBlock.SERIAL_OVERFLOW
            }

            else -> {
                null
            }
        }
}
