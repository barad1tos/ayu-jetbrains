package dev.ayuislands.rotation

import dev.ayuislands.accent.AYU_ACCENT_PRESETS
import dev.ayuislands.accent.AyuVariant
import dev.ayuislands.studio.model.StudioProjectKey
import dev.ayuislands.studio.model.StudioTarget
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class RotationStepPlannerTest {
    private val owner = RuntimeOwner(StudioTarget.Project(StudioProjectKey("project-a")), appearanceRevision = 4)
    private val operation = UUID(0L, 1L)
    private val variant = AyuVariant.MIRAGE
    private val checkpoint = RotationRuntimeCheckpoint(owner, sequenceCursorRaw = -1, successfulStepSerial = 7)
    private val intent = RotationIntent(enabled = true, modeRaw = "older-preset-value", intervalHoursRaw = 2)
    private val inputs = RotationStepInputs(owner, intent, variant)

    @Test
    fun `preset planning follows the saved order and retains the original intent`() {
        val plan = assertIs<RotationStepPlan.Ready>(RotationStepPlanner.planStep(checkpoint, inputs, operation))

        assertEquals(
            RotationCandidate(
                operation,
                owner,
                intent,
                AccentRotationMode.PRESET,
                variant,
                AYU_ACCENT_PRESETS[0].hex,
                0,
            ),
            plan.candidate,
        )
        assertEquals(7_200_000L, plan.intervalMillis)
        assertEquals(-1, checkpoint.sequenceCursorRaw)
        assertNull(checkpoint.output)
    }

    @Test
    fun `random planning requests a color and retains the shared preset cursor`() {
        val random = inputs.copy(intent = intent.copy(modeRaw = AccentRotationMode.RANDOM.name))

        assertEquals(
            RotationStepPlan.RandomRequired(operation, variant),
            RotationStepPlanner.planStep(checkpoint, random, operation),
        )
        val chosen =
            assertIs<RotationStepPlan.Ready>(
                RotationStepPlanner.planStep(checkpoint, random, operation, randomHex = "#AABBCC"),
            )
        assertEquals("#AABBCC", chosen.candidate.hex)
        assertEquals(checkpoint.sequenceCursorRaw, chosen.candidate.nextCursor)
        assertEquals(
            RotationStepPlan.Blocked(RotationStepBlock.INVALID_COLOR),
            RotationStepPlanner.planStep(checkpoint, random, operation, randomHex = "invalid"),
        )
    }

    @Test
    fun `a retry keeps its frozen color until the variant or configuration changes`() {
        val random = inputs.copy(intent = intent.copy(modeRaw = AccentRotationMode.RANDOM.name))
        val original =
            assertIs<RotationStepPlan.Ready>(
                RotationStepPlanner.planStep(checkpoint, random, operation, randomHex = "#AABBCC"),
            ).candidate
        val saved = checkpoint.copy(retryCandidate = original)
        val nextOperation = UUID(0L, 2L)

        val retry = assertIs<RotationStepPlan.Ready>(RotationStepPlanner.planStep(saved, random, nextOperation))
        assertEquals(original, retry.candidate)
        val changed = random.copy(eligibleVariant = AyuVariant.DARK)
        assertEquals(
            RotationStepPlan.RandomRequired(nextOperation, AyuVariant.DARK),
            RotationStepPlanner.planStep(saved, changed, nextOperation),
        )
        val invalid = saved.copy(retryCandidate = original.copy(hex = "invalid"))
        assertEquals(
            RotationStepPlan.Blocked(RotationStepBlock.INVALID_COLOR),
            RotationStepPlanner.planStep(invalid, random, nextOperation),
        )
        assertEquals(original, saved.retryCandidate)
    }

    @Test
    fun `disabled ineligible and newer owners cannot prepare a color`() {
        val cases =
            listOf(
                inputs.copy(intent = intent.copy(enabled = false)) to RotationStepBlock.DISABLED,
                inputs.copy(eligibleVariant = null) to RotationStepBlock.INELIGIBLE,
                inputs.copy(owner = owner.copy(appearanceRevision = 5)) to RotationStepBlock.OWNER_CHANGED,
            )
        for ((observation, reason) in cases) {
            val result = RotationStepPlanner.planStep(checkpoint, observation, operation)
            assertEquals(RotationStepPlan.Blocked(reason), result)
        }
        val pending = checkpoint.copy(recoveryFault = RuntimeFault.DurableRecovery("restore"))
        assertEquals(
            RotationStepPlan.Blocked(RotationStepBlock.RECOVERY_PENDING),
            RotationStepPlanner.planStep(pending, inputs, operation),
        )
        val exhausted = checkpoint.copy(successfulStepSerial = Long.MAX_VALUE)
        assertEquals(
            RotationStepPlan.Blocked(RotationStepBlock.SERIAL_OVERFLOW),
            RotationStepPlanner.planStep(exhausted, inputs, operation),
        )
    }

    @Test
    fun `preset completion schedules from the applied time and advances only the proposed history`() {
        val previous = RotationSchedule(0, 7_200_000, 7_200_000, 10)
        val accepted = checkpoint.copy(schedule = previous)
        val prepared = assertIs<RotationStepPlan.Ready>(RotationStepPlanner.planStep(accepted, inputs, operation))
        val candidate = prepared.candidate
        val completed =
            assertIs<RotationCompletionPlan.Ready>(
                RotationStepPlanner.planCompletion(accepted, inputs, candidate, completedAtEpochMillis = 7_200_100),
            ).checkpoint

        assertEquals(RotationSchedule(7_200_100, 14_400_100, 7_200_000, 11), completed.schedule)
        assertEquals(8L, completed.successfulStepSerial)
        assertEquals(7_200_100L, completed.lastSuccessfulStepEpochMillis)
        assertEquals(0, completed.sequenceCursorRaw)
        assertEquals(RotationOutput(owner, variant, candidate.hex), completed.output)
        assertEquals(previous, accepted.schedule)
        assertNull(accepted.output)
    }

    @Test
    fun `completion rejects a superseded color and clears a retry only in the accepted proposal`() {
        val random = inputs.copy(intent = intent.copy(modeRaw = AccentRotationMode.RANDOM.name))
        val chosen =
            assertIs<RotationStepPlan.Ready>(
                RotationStepPlanner.planStep(checkpoint, random, operation, randomHex = "#AABBCC"),
            ).candidate
        val saved = checkpoint.copy(retryCandidate = chosen)
        val changed = random.copy(intent = random.intent.copy(intervalHoursRaw = 1))

        assertEquals(
            RotationCompletionPlan.Blocked(RotationStepBlock.STALE_CANDIDATE),
            RotationStepPlanner.planCompletion(saved, changed, chosen, completedAtEpochMillis = 1_000),
        )
        val next =
            assertIs<RotationCompletionPlan.Ready>(
                RotationStepPlanner.planCompletion(saved, random, chosen, completedAtEpochMillis = 1_000),
            ).checkpoint
        assertEquals(saved.sequenceCursorRaw, next.sequenceCursorRaw)
        assertNull(next.retryCandidate)
        assertEquals(chosen, saved.retryCandidate)
    }

    @Test
    fun `an appointment alone retains accepted color history`() {
        val accepted =
            checkpoint.copy(
                output = RotationOutput(owner, variant, "#AABBCC"),
                lastSuccessfulStepEpochMillis = 400,
                resume = RotationResume.FullInterval(FullIntervalReason.FOLLOW_SYSTEM),
            )
        val proposed =
            assertIs<RotationCompletionPlan.Ready>(
                RotationStepPlanner.planSchedule(accepted, intervalMillis = 3_600_000, scheduledAtEpochMillis = 500),
            ).checkpoint

        val schedule = RotationSchedule(500, 3_600_500, 3_600_000, 1)
        assertEquals(accepted.copy(schedule = schedule, resume = RotationResume.KeepDeadline), proposed)
        assertEquals(
            RotationCompletionPlan.Blocked(RotationStepBlock.DEADLINE_OVERFLOW),
            RotationStepPlanner.planSchedule(accepted, 3_600_000, Long.MAX_VALUE),
        )
    }
}
