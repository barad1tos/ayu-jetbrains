package dev.ayuislands.rotation

import dev.ayuislands.accent.AYU_ACCENT_PRESETS
import dev.ayuislands.accent.AyuVariant
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class RotationStepPlannerTest {
    @Test
    fun `qualified cleanup acknowledgement arms the remaining interval without repeating acceptance`() {
        val committing = committingStep()
        val operation = assertIs<RotationStepCommit>(committing.operation)
        val accepted =
            RotationRuntimeMachine.transition(
                committing,
                RotationEvent.CommitResolved(OPERATION, RotationCommitOutcome.Accepted),
                operation.startedAt,
            )
        val settling = assertIs<RotationRuntimeState.Applying>(accepted.state)
        val settlement = assertIs<RotationStepSettlement>(settling.operation)
        assertSame(operation, settlement.commit)
        assertSame(operation.candidate, settlement.commit.candidate)
        assertEquals(RotationSettlementProgress.PUBLICATION_PENDING, settlement.progress)
        for (event in listOf(
            RotationEvent.Prepared(OPERATION),
            RotationEvent.EffectsSettled(OPERATION, operation.startedAt),
            RotationEvent.CommitResolved(OPERATION, RotationCommitOutcome.Accepted),
            RotationEvent.CommitResolved(NEXT_OPERATION, RotationCommitOutcome.Settled),
        )) {
            val stale = RotationRuntimeMachine.transition(settling, event, operation.startedAt)
            assertSame(settling, stale.state)
            assertTrue(stale.commands.isEmpty())
        }
        val acknowledgedAt = ClockSample(TimeUnit.HOURS.toMillis(100), TimeUnit.MINUTES.toNanos(5))

        val completed =
            RotationRuntimeMachine.transition(
                settling,
                RotationEvent.CommitResolved(OPERATION, RotationCommitOutcome.Settled),
                acknowledgedAt,
            )

        val waiting = assertIs<RotationRuntimeState.Waiting>(completed.state)
        assertSame(operation.checkpoint, waiting.snapshot.checkpoint)
        assertEquals(13L, waiting.snapshot.checkpoint.successfulStepSerial)
        assertEquals(TimeUnit.MINUTES.toMillis(55), waiting.ticket.delayMillis)
        assertEquals(RotationCommand.ArmSchedule(waiting.ticket), completed.commands.single())
        val duplicate =
            RotationRuntimeMachine.transition(
                waiting,
                RotationEvent.CommitResolved(OPERATION, RotationCommitOutcome.Settled),
                acknowledgedAt,
            )
        assertSame(waiting, duplicate.state)
        assertTrue(duplicate.commands.isEmpty())
    }

    @Test
    fun `accepted cleanup failure retains history and retries only unacknowledged settlement work`() {
        for (progress in RotationSettlementProgress.entries) {
            val committing = committingStep()
            val operation = assertIs<RotationStepCommit>(committing.operation)
            val accepted =
                RotationRuntimeMachine.transition(
                    committing,
                    RotationEvent.CommitResolved(OPERATION, RotationCommitOutcome.Accepted),
                    operation.startedAt,
                )
            val failure = RuntimeFault.DurableRecovery("accepted-cleanup")
            val pending =
                RotationRuntimeMachine.transition(
                    accepted.state,
                    RotationEvent.CommitResolved(
                        OPERATION,
                        RotationCommitOutcome.AcceptedCleanupPending(progress, failure),
                    ),
                    operation.startedAt,
                )
            val faulted = assertIs<RotationRuntimeState.Faulted.Loaded>(pending.state)
            val settlement = assertIs<RotationStepSettlement>(faulted.pendingOperation)
            assertSame(operation.checkpoint, faulted.snapshot.checkpoint)
            assertSame(operation, settlement.commit)
            assertEquals(progress, settlement.progress)
            assertEquals(RotationCommand.ReportFault(failure), pending.commands.single())

            val retried =
                RotationRuntimeMachine.transition(
                    faulted,
                    RotationEvent.RetryRecovery(NEXT_OPERATION),
                    operation.startedAt,
                )

            val applying = assertIs<RotationRuntimeState.Applying>(retried.state)
            val request = assertIs<RotationStepSettlement>(applying.operation)
            assertSame(settlement.commit, request.commit)
            assertEquals(progress, request.progress)
            assertEquals(NEXT_OPERATION, request.completionId)
            assertEquals(
                RotationCommand.PublishAccepted(assertIs<RotationSettlement>(applying.operation)),
                retried.commands.single(),
            )
            val duplicate =
                RotationRuntimeMachine.transition(
                    applying,
                    RotationEvent.RetryRecovery(NEXT_OPERATION),
                    operation.startedAt,
                )
            assertSame(applying, duplicate.state)
            assertTrue(duplicate.commands.isEmpty())
            val completed =
                RotationRuntimeMachine.transition(
                    applying,
                    RotationEvent.CommitResolved(OPERATION, RotationCommitOutcome.Settled, NEXT_OPERATION),
                    operation.startedAt,
                )
            val waiting = assertIs<RotationRuntimeState.Waiting>(completed.state)
            assertSame(operation.checkpoint, waiting.snapshot.checkpoint)
            assertEquals(13L, waiting.snapshot.checkpoint.successfulStepSerial)
            assertEquals(RotationCommand.ArmSchedule(waiting.ticket), completed.commands.single())
        }
    }

    @Test
    fun `Follow observed during accepted settlement waits for cleanup before writing its obligation`() {
        val committing = committingStep()
        val inputs = committing.snapshot.inputs
        val operation = assertIs<RotationStepCommit>(committing.operation)
        val accepted =
            RotationRuntimeMachine.transition(
                committing,
                RotationEvent.CommitResolved(OPERATION, RotationCommitOutcome.Accepted),
                operation.startedAt,
            )
        val follow = inputs.copy(availability = inputs.availability.copy(effectiveFollowSystem = true))
        val observed =
            RotationRuntimeMachine.transition(
                accepted.state,
                RotationEvent.AvailabilityObserved(1, follow.availability, NEXT_OPERATION),
                operation.startedAt,
            )
        val settling = assertIs<RotationRuntimeState.Applying>(observed.state)
        assertIs<RotationStepSettlement>(settling.operation)
        assertSame(operation.checkpoint, settling.snapshot.checkpoint)
        assertTrue(observed.commands.isEmpty())

        val completed =
            RotationRuntimeMachine.transition(
                settling,
                RotationEvent.CommitResolved(OPERATION, RotationCommitOutcome.Settled),
                operation.startedAt,
            )

        val applying = assertIs<RotationRuntimeState.Applying>(completed.state)
        assertIs<RotationScheduleCommit>(applying.operation)
        val checkpoint = assertIs<RotationCommand.PersistCheckpoint>(completed.commands.single())
        assertEquals(NEXT_OPERATION, checkpoint.operationId)
        assertEquals(13L, checkpoint.checkpoint.successfulStepSerial)
        assertEquals(RotationResume.FullInterval(FullIntervalReason.FOLLOW_SYSTEM), checkpoint.checkpoint.resume)
        assertSame(operation.checkpoint, applying.snapshot.checkpoint)
    }

    @Test
    fun `accepted rotation keeps its settlement identity before the next timer is armed`() {
        val inputs = rotationInputs()
        val before = dueState(inputs).snapshot
        val candidate =
            assertIs<RotationStepPlan.Ready>(
                RotationStepPlanner.planStep(before.checkpoint, inputs, OPERATION, null),
            ).candidate
        val completedAt = ClockSample(TimeUnit.HOURS.toMillis(12), 0)
        val checkpoint =
            assertIs<RotationCompletionPlan.Ready>(
                RotationStepPlanner.planCompletion(before.checkpoint, inputs, candidate, completedAt.epochMillis),
            ).checkpoint
        val committing =
            RotationRuntimeState.Applying(
                before,
                RotationStepCommit(OPERATION, checkpoint, completedAt, candidate),
            )

        val accepted =
            RotationRuntimeMachine.transition(
                committing,
                RotationEvent.CommitResolved(OPERATION, RotationCommitOutcome.Accepted),
                ClockSample(completedAt.epochMillis, TimeUnit.MINUTES.toNanos(5)),
            )

        val settling = assertIs<RotationRuntimeState.Applying>(accepted.state)
        assertEquals(OPERATION, settling.operation.operationId)
        assertSame(checkpoint, settling.snapshot.checkpoint)
        assertEquals(
            RotationCommand.PublishAccepted(assertIs<RotationSettlement>(settling.operation)),
            accepted.commands.single(),
        )
        assertEquals(12L, before.checkpoint.successfulStepSerial)
    }

    @Test
    fun `preset completion replaces the old appointment using the actual completion time`() {
        val inputs = rotationInputs(RotationIntent(true, "PRESET", 1))
        val hour = TimeUnit.HOURS.toMillis(1)
        val previousOutput = RotationOutput(inputs.owner, AyuVariant.MIRAGE, "#112233")
        val before =
            dueState(inputs).snapshot.checkpoint.copy(
                lastSuccessfulStepEpochMillis = 6 * hour,
                output = previousOutput,
                schedule = RotationSchedule(6 * hour, 12 * hour, 6 * hour, 7),
            )
        val candidate =
            assertIs<RotationStepPlan.Ready>(
                RotationStepPlanner.planStep(before, inputs, OPERATION, null),
            ).candidate
        val completedAt = 12 * hour + TimeUnit.MINUTES.toMillis(7)

        val plan =
            assertIs<RotationCompletionPlan.Ready>(
                RotationStepPlanner.planCompletion(before, inputs, candidate, completedAt),
            )

        assertEquals(4, plan.checkpoint.sequenceCursorRaw)
        assertEquals(13L, plan.checkpoint.successfulStepSerial)
        assertEquals(completedAt, plan.checkpoint.lastSuccessfulStepEpochMillis)
        assertEquals(RotationOutput(inputs.owner, AyuVariant.MIRAGE, AYU_ACCENT_PRESETS[4].hex), plan.checkpoint.output)
        assertEquals(RotationSchedule(completedAt, completedAt + hour, hour, 8), plan.checkpoint.schedule)
        assertEquals(RotationResume.KeepDeadline, plan.checkpoint.resume)
        assertEquals(3, before.sequenceCursorRaw)
        assertEquals(12L, before.successfulStepSerial)
        assertEquals(6 * hour, before.lastSuccessfulStepEpochMillis)
        assertSame(previousOutput, before.output)
        assertEquals(12 * hour, requireNotNull(before.schedule).deadlineEpochMillis)
    }

    @Test
    fun `random completion preserves the raw cursor and accepts the exact retained retry color`() {
        val inputs = rotationInputs(RotationIntent(true, "RANDOM", 0))
        val initial = dueState(inputs).snapshot.checkpoint.copy(sequenceCursorRaw = Int.MIN_VALUE)
        val candidate =
            assertIs<RotationStepPlan.Ready>(
                RotationStepPlanner.planStep(initial, inputs, OPERATION, "#aB01Ef"),
            ).candidate
        val before = initial.copy(retryCandidate = candidate)
        val completedAt = TimeUnit.HOURS.toMillis(40)
        val hour = TimeUnit.HOURS.toMillis(1)

        val plan =
            assertIs<RotationCompletionPlan.Ready>(
                RotationStepPlanner.planCompletion(before, inputs, candidate, completedAt),
            )

        assertEquals(Int.MIN_VALUE, plan.checkpoint.sequenceCursorRaw)
        assertEquals(RotationOutput(inputs.owner, AyuVariant.MIRAGE, "#aB01Ef"), plan.checkpoint.output)
        assertEquals(13L, plan.checkpoint.successfulStepSerial)
        assertEquals(completedAt, plan.checkpoint.lastSuccessfulStepEpochMillis)
        assertEquals(RotationSchedule(completedAt, completedAt + hour, hour, 8), plan.checkpoint.schedule)
        assertNull(plan.checkpoint.retryCandidate)
        assertSame(candidate, before.retryCandidate)
        assertEquals(0, inputs.intent.intervalHoursRaw)
        assertEquals(Int.MIN_VALUE, before.sequenceCursorRaw)
    }

    @Test
    fun `completion refuses candidates superseded by a different owner configuration or variant`() {
        val inputs = rotationInputs()
        val before = dueState(inputs).snapshot.checkpoint
        val candidate =
            assertIs<RotationStepPlan.Ready>(
                RotationStepPlanner.planStep(before, inputs, OPERATION, null),
            ).candidate
        val changed =
            listOf(
                inputs.copy(owner = inputs.owner.copy(appearanceRevision = 4)) to RotationStepBlock.OWNER_CHANGED,
                rotationInputs(RotationIntent(true, "RANDOM", 1)) to RotationStepBlock.STALE_CANDIDATE,
                rotationInputs(RotationIntent(true, "PRESET", 2)) to RotationStepBlock.STALE_CANDIDATE,
                inputs.copy(availability = inputs.availability.copy(variant = AyuVariant.DARK)) to
                    RotationStepBlock.STALE_CANDIDATE,
            )

        for ((current, reason) in changed) {
            val plan = RotationStepPlanner.planCompletion(before, current, candidate, 0)
            assertEquals(reason, assertIs<RotationCompletionPlan.Blocked>(plan).reason)
        }

        assertEquals(3, before.sequenceCursorRaw)
        assertEquals(12L, before.successfulStepSerial)
        assertNull(before.output)
    }

    @Test
    fun `completion cannot accept history while disabled unavailable or under recovery`() {
        val inputs = rotationInputs()
        val before = dueState(inputs).snapshot.checkpoint
        val candidate =
            assertIs<RotationStepPlan.Ready>(
                RotationStepPlanner.planStep(before, inputs, OPERATION, null),
            ).candidate
        val blocked =
            listOf(
                rotationInputs(RotationIntent(false, "PRESET", 1)) to RotationStepBlock.DISABLED,
                inputs.copy(availability = inputs.availability.copy(lastDefinitivePro = false)) to
                    RotationStepBlock.INELIGIBLE,
                inputs.copy(availability = inputs.availability.copy(recoveryPending = true)) to
                    RotationStepBlock.INELIGIBLE,
            )
        for ((current, reason) in blocked) {
            val plan = RotationStepPlanner.planCompletion(before, current, candidate, 0)
            assertEquals(reason, assertIs<RotationCompletionPlan.Blocked>(plan).reason)
        }
        val recovery = before.copy(recoveryFault = RuntimeFault.DurableRecovery("unresolved-owner"))

        val recoveryPlan = RotationStepPlanner.planCompletion(recovery, inputs, candidate, 0)

        assertEquals(RotationStepBlock.RECOVERY_PENDING, assertIs<RotationCompletionPlan.Blocked>(recoveryPlan).reason)
        assertEquals(12L, before.successfulStepSerial)
        assertNull(before.output)
    }

    @Test
    fun `completion checks both serials and the deadline before proposing accepted history`() {
        val inputs = rotationInputs()
        val before = dueState(inputs).snapshot.checkpoint
        val candidate =
            assertIs<RotationStepPlan.Ready>(
                RotationStepPlanner.planStep(before, inputs, OPERATION, null),
            ).candidate
        val hour = TimeUnit.HOURS.toMillis(1)
        val overflowCases =
            listOf(
                Triple(before.copy(successfulStepSerial = Long.MAX_VALUE), 0L, RotationStepBlock.SERIAL_OVERFLOW),
                Triple(
                    before.copy(schedule = requireNotNull(before.schedule).copy(scheduleSerial = Long.MAX_VALUE)),
                    0L,
                    RotationStepBlock.SERIAL_OVERFLOW,
                ),
                Triple(before, Long.MAX_VALUE - hour + 1, RotationStepBlock.DEADLINE_OVERFLOW),
            )

        for ((checkpoint, completedAt, reason) in overflowCases) {
            val plan = RotationStepPlanner.planCompletion(checkpoint, inputs, candidate, completedAt)
            assertEquals(reason, assertIs<RotationCompletionPlan.Blocked>(plan).reason)
        }

        assertEquals(12L, before.successfulStepSerial)
        assertEquals(7L, requireNotNull(before.schedule).scheduleSerial)
    }

    @Test
    fun `completion supports the full stored interval range at valid epoch boundaries`() {
        val inputs = rotationInputs(RotationIntent(true, "PRESET", Int.MAX_VALUE))
        val before = RotationRuntimeCheckpoint(inputs.owner, 3, 12)
        val candidate =
            assertIs<RotationStepPlan.Ready>(
                RotationStepPlanner.planStep(before, inputs, OPERATION, null),
            ).candidate
        val interval = TimeUnit.HOURS.toMillis(Int.MAX_VALUE.toLong())

        for (completedAt in listOf(Long.MIN_VALUE, Long.MAX_VALUE - interval)) {
            val plan =
                assertIs<RotationCompletionPlan.Ready>(
                    RotationStepPlanner.planCompletion(before, inputs, candidate, completedAt),
                )
            assertEquals(RotationSchedule(completedAt, completedAt + interval, interval, 1), plan.checkpoint.schedule)
            assertEquals(completedAt, plan.checkpoint.lastSuccessfulStepEpochMillis)
        }

        assertNull(before.schedule)
        assertEquals(Int.MAX_VALUE, inputs.intent.intervalHoursRaw)
    }

    @Test
    fun `a retry keeps its candidate but rejects acknowledgements from the previous attempt`() {
        val inputs = rotationInputs(RotationIntent(true, "RANDOM", 1))
        val initial = dueState(inputs)
        val original =
            assertIs<RotationStepPlan.Ready>(
                RotationStepPlanner.planStep(initial.snapshot.checkpoint, inputs, OPERATION, "#Ab12cD"),
            ).candidate
        val hour = TimeUnit.HOURS.toMillis(1)
        val retryCheckpoint =
            initial.snapshot.checkpoint.copy(
                retryCandidate = original,
                schedule = RotationSchedule(hour, 2 * hour, hour, 8),
            )
        val waiting =
            RotationRuntimeState.Waiting(
                RotationSnapshot(retryCheckpoint, inputs),
                RotationTicket(inputs.owner, 8, 0, 0),
            )

        val retry =
            RotationRuntimeMachine.transition(
                waiting,
                RotationEvent.TimerDue(waiting.ticket, NEXT_OPERATION),
                ClockSample(2 * hour, 0),
            )

        val applying = assertIs<RotationRuntimeState.Applying>(retry.state)
        val preparation = assertIs<RotationCommand.PrepareStep>(retry.commands.single())
        assertSame(original, preparation.candidate)
        assertEquals(NEXT_OPERATION, preparation.operationId)
        assertEquals(NEXT_OPERATION, applying.operation.operationId)
        val duplicate =
            RotationRuntimeMachine.transition(
                applying,
                RotationEvent.CommitResolved(OPERATION, RotationCommitOutcome.RejectedRestored),
                ClockSample(2 * hour, 0),
            )
        assertSame(applying, duplicate.state)
        assertTrue(duplicate.commands.isEmpty())
        assertSame(retryCheckpoint, applying.snapshot.checkpoint)
    }

    @Test
    fun `a variant change replaces pending random ownership before accepting a color`() {
        val inputs = rotationInputs(RotationIntent(true, "RANDOM", 1))
        val initial = dueState(inputs)
        val requested =
            RotationRuntimeMachine.transition(
                initial,
                RotationEvent.TimerDue(initial.ticket, OPERATION),
                ClockSample(0, 0),
            )
        val dark = inputs.copy(availability = inputs.availability.copy(variant = AyuVariant.DARK))

        val replaced =
            RotationRuntimeMachine.transition(
                requested.state,
                RotationEvent.AvailabilityObserved(1, dark.availability, NEXT_OPERATION),
                ClockSample(0, 0),
            )

        val request = assertIs<RotationCommand.RequestRandomColor>(replaced.commands.single())
        assertEquals(NEXT_OPERATION, request.operationId)
        assertEquals(AyuVariant.DARK, request.variant)
        val stale =
            RotationRuntimeMachine.transition(
                replaced.state,
                RotationEvent.RandomColorChosen(OPERATION, "#FAFAFA"),
                ClockSample(0, 0),
            )
        assertSame(replaced.state, stale.state)
        assertTrue(stale.commands.isEmpty())
        val prepared =
            RotationRuntimeMachine.transition(
                replaced.state,
                RotationEvent.RandomColorChosen(NEXT_OPERATION, "#121212"),
                ClockSample(0, 0),
            )
        val candidate = assertIs<RotationCommand.PrepareStep>(prepared.commands.single()).candidate
        assertEquals(AyuVariant.DARK, candidate.variant)
        assertEquals("#121212", candidate.hex)
        assertSame(initial.snapshot.checkpoint, prepared.state.requireSnapshot().checkpoint)
    }

    @Test
    fun `a due preset prepares one candidate without accepting any history`() {
        val inputs = rotationInputs()
        val initial = dueState(inputs)

        val prepared =
            RotationRuntimeMachine.transition(
                initial,
                RotationEvent.TimerDue(initial.ticket, OPERATION),
                ClockSample(TimeUnit.HOURS.toMillis(12), 0),
            )

        assertSame(
            initial.snapshot.checkpoint,
            assertIs<RotationRuntimeState.Applying>(prepared.state).snapshot.checkpoint,
        )
        val candidate = assertIs<RotationCommand.PrepareStep>(prepared.commands.single()).candidate
        assertEquals(OPERATION, candidate.operationId)
        assertEquals(4, candidate.nextCursor)
        assertEquals(AYU_ACCENT_PRESETS[4].hex, candidate.hex)
        val repeated =
            RotationRuntimeMachine.transition(
                prepared.state,
                RotationEvent.TimerDue(initial.ticket, NEXT_OPERATION),
                ClockSample(TimeUnit.HOURS.toMillis(12), 0),
            )
        assertSame(prepared.state, repeated.state)
        assertTrue(repeated.commands.isEmpty())
    }

    @Test
    fun `a random tick requests one matching color before durable preparation`() {
        val inputs = rotationInputs(RotationIntent(true, "RANDOM", 1))
        val initial = dueState(inputs)
        val chosen =
            RotationRuntimeMachine.transition(
                initial,
                RotationEvent.TimerDue(initial.ticket, OPERATION),
                ClockSample(TimeUnit.HOURS.toMillis(12), 0),
            )
        val request = assertIs<RotationCommand.RequestRandomColor>(chosen.commands.single())
        assertEquals(OPERATION, request.operationId)
        assertEquals(AyuVariant.MIRAGE, request.variant)
        val stale =
            RotationRuntimeMachine.transition(
                chosen.state,
                RotationEvent.RandomColorChosen(NEXT_OPERATION, "#FFFFFF"),
                ClockSample(TimeUnit.HOURS.toMillis(12), 0),
            )
        assertSame(chosen.state, stale.state)
        assertTrue(stale.commands.isEmpty())

        val prepared =
            RotationRuntimeMachine.transition(
                chosen.state,
                RotationEvent.RandomColorChosen(OPERATION, "#Ab12cD"),
                ClockSample(TimeUnit.HOURS.toMillis(12), 0),
            )

        val candidate = assertIs<RotationCommand.PrepareStep>(prepared.commands.single()).candidate
        assertEquals("#Ab12cD", candidate.hex)
        assertEquals(3, candidate.nextCursor)
        assertSame(initial.snapshot.checkpoint, prepared.state.requireSnapshot().checkpoint)
        val repeated =
            RotationRuntimeMachine.transition(
                prepared.state,
                RotationEvent.RandomColorChosen(OPERATION, "#FFFFFF"),
                ClockSample(TimeUnit.HOURS.toMillis(12), 0),
            )
        assertSame(prepared.state, repeated.state)
        assertTrue(repeated.commands.isEmpty())
    }

    @Test
    fun `an invalid random result faults before preparing or changing history`() {
        val inputs = rotationInputs(RotationIntent(true, "RANDOM", 1))
        val initial = dueState(inputs)
        val requested =
            RotationRuntimeMachine.transition(
                initial,
                RotationEvent.TimerDue(initial.ticket, OPERATION),
                ClockSample(0, 0),
            )

        val invalid =
            RotationRuntimeMachine.transition(
                requested.state,
                RotationEvent.RandomColorChosen(OPERATION, "invalid"),
                ClockSample(0, 0),
            )

        assertSame(
            initial.snapshot.checkpoint,
            assertIs<RotationRuntimeState.Faulted.Loaded>(invalid.state).snapshot.checkpoint,
        )
        assertIs<RotationCommand.ReportFault>(invalid.commands.single())
    }

    @Test
    fun `an exhausted schedule serial blocks effects before preparation`() {
        val inputs = rotationInputs()
        val initial = dueState(inputs)
        val checkpoint = initial.snapshot.checkpoint
        val exhausted =
            checkpoint.copy(schedule = requireNotNull(checkpoint.schedule).copy(scheduleSerial = Long.MAX_VALUE))

        val plan = RotationStepPlanner.planStep(exhausted, inputs, OPERATION, null)

        assertEquals(RotationStepBlock.SERIAL_OVERFLOW, assertIs<RotationStepPlan.Blocked>(plan).reason)
    }

    @Test
    fun `preset planning interprets arbitrary cursors without changing accepted history`() {
        val inputs = rotationInputs(RotationIntent(true, "unknown-mode", Int.MAX_VALUE))
        for (cursor in listOf(Int.MIN_VALUE, -1, 0, AYU_ACCENT_PRESETS.lastIndex, Int.MAX_VALUE)) {
            val checkpoint = RotationRuntimeCheckpoint(inputs.owner, cursor, 12)

            val plan =
                assertIs<RotationStepPlan.Ready>(RotationStepPlanner.planStep(checkpoint, inputs, OPERATION, null))

            val next = (Math.floorMod(cursor, AYU_ACCENT_PRESETS.size) + 1) % AYU_ACCENT_PRESETS.size
            assertEquals(next, plan.candidate.nextCursor)
            assertEquals(AYU_ACCENT_PRESETS[next].hex, plan.candidate.hex)
            assertEquals(AccentRotationMode.PRESET, plan.candidate.mode)
            assertEquals(OPERATION, plan.candidate.operationId)
            assertEquals(inputs.owner, plan.candidate.owner)
            assertEquals(cursor, checkpoint.sequenceCursorRaw)
            assertEquals(12L, checkpoint.successfulStepSerial)
            assertEquals("unknown-mode", inputs.intent.modeRaw)
            assertEquals(Int.MAX_VALUE, inputs.intent.intervalHoursRaw)
            assertEquals(TimeUnit.HOURS.toMillis(Int.MAX_VALUE.toLong()), plan.intervalMillis)
        }
    }

    @Test
    fun `random planning requests a supplied color and retains the shared preset cursor`() {
        val inputs = rotationInputs(RotationIntent(true, "RANDOM", 0))
        val checkpoint = RotationRuntimeCheckpoint(inputs.owner, Int.MIN_VALUE, 12)

        assertIs<RotationStepPlan.RandomRequired>(RotationStepPlanner.planStep(checkpoint, inputs, OPERATION, null))
        val plan =
            assertIs<RotationStepPlan.Ready>(RotationStepPlanner.planStep(checkpoint, inputs, OPERATION, "#12AB34"))

        assertEquals("#12AB34", plan.candidate.hex)
        assertEquals(Int.MIN_VALUE, plan.candidate.nextCursor)
        assertEquals(AccentRotationMode.RANDOM, plan.candidate.mode)
        assertEquals(TimeUnit.HOURS.toMillis(1), plan.intervalMillis)
        assertEquals(0, inputs.intent.intervalHoursRaw)
    }

    @Test
    fun `a retry uses its original operation and exact random color without another draw`() {
        val inputs = rotationInputs(RotationIntent(true, "RANDOM", 1))
        val before = RotationRuntimeCheckpoint(inputs.owner, 3, 12)
        val first = assertIs<RotationStepPlan.Ready>(RotationStepPlanner.planStep(before, inputs, OPERATION, "#Ab12cD"))
        val checkpoint = before.copy(retryCandidate = first.candidate)

        val retry =
            assertIs<RotationStepPlan.Ready>(RotationStepPlanner.planStep(checkpoint, inputs, NEXT_OPERATION, null))
        val redundantColor =
            assertIs<RotationStepPlan.Ready>(
                RotationStepPlanner.planStep(checkpoint, inputs, NEXT_OPERATION, "#FFFFFF"),
            )

        assertSame(first.candidate, retry.candidate)
        assertEquals(retry, redundantColor)
        assertEquals("#Ab12cD", retry.candidate.hex)
        assertEquals(OPERATION, retry.candidate.operationId)
        assertEquals(before.sequenceCursorRaw, checkpoint.sequenceCursorRaw)
        assertEquals(before.successfulStepSerial, checkpoint.successfulStepSerial)
    }

    @Test
    fun `configuration and variant changes discard a retry without consuming it`() {
        val inputs = rotationInputs(RotationIntent(true, "RANDOM", 1))
        val before = RotationRuntimeCheckpoint(inputs.owner, 3, 12)
        val first = assertIs<RotationStepPlan.Ready>(RotationStepPlanner.planStep(before, inputs, OPERATION, "#123456"))
        val checkpoint = before.copy(retryCandidate = first.candidate)
        val changed =
            listOf(
                rotationInputs(RotationIntent(true, "RANDOM", 2)),
                inputs.copy(availability = inputs.availability.copy(variant = AyuVariant.DARK)),
            )

        for (current in changed) {
            assertIs<RotationStepPlan.RandomRequired>(
                RotationStepPlanner.planStep(checkpoint, current, NEXT_OPERATION, null),
            )
            assertSame(first.candidate, checkpoint.retryCandidate)
            assertEquals(3, checkpoint.sequenceCursorRaw)
            assertEquals(12L, checkpoint.successfulStepSerial)
        }
    }

    @Test
    fun `unusable inputs fail visibly without inventing a color or serial`() {
        val inputs = rotationInputs()
        val checkpoint = RotationRuntimeCheckpoint(inputs.owner, 3, 12)
        val empty = RotationStepPlanner.planStep(checkpoint, inputs, OPERATION, null, emptyList())
        assertEquals(RotationStepBlock.EMPTY_CATALOG, assertIs<RotationStepPlan.Blocked>(empty).reason)
        val overflow =
            RotationStepPlanner.planStep(
                checkpoint.copy(successfulStepSerial = Long.MAX_VALUE),
                inputs,
                OPERATION,
                null,
            )
        assertEquals(RotationStepBlock.SERIAL_OVERFLOW, assertIs<RotationStepPlan.Blocked>(overflow).reason)
        val random = rotationInputs(RotationIntent(true, "RANDOM", 1))
        val invalid = RotationStepPlanner.planStep(checkpoint, random, OPERATION, "invalid")
        assertEquals(RotationStepBlock.INVALID_COLOR, assertIs<RotationStepPlan.Blocked>(invalid).reason)
    }

    @Test
    fun `disabled unavailable or replaced owners cannot prepare a step`() {
        val inputs = rotationInputs()
        val checkpoint = RotationRuntimeCheckpoint(inputs.owner, 3, 12)
        val disabled =
            RotationStepPlanner.planStep(
                checkpoint,
                rotationInputs(RotationIntent(false, "PRESET", 1)),
                OPERATION,
                null,
            )
        assertEquals(RotationStepBlock.DISABLED, assertIs<RotationStepPlan.Blocked>(disabled).reason)
        val paused =
            RotationStepPlanner.planStep(
                checkpoint,
                inputs.copy(availability = inputs.availability.copy(lastDefinitivePro = null)),
                OPERATION,
                null,
            )
        assertEquals(RotationStepBlock.INELIGIBLE, assertIs<RotationStepPlan.Blocked>(paused).reason)
        val replaced =
            RotationStepPlanner.planStep(
                checkpoint,
                inputs.copy(owner = inputs.owner.copy(appearanceRevision = 4)),
                OPERATION,
                null,
            )
        assertEquals(RotationStepBlock.OWNER_CHANGED, assertIs<RotationStepPlan.Blocked>(replaced).reason)
    }

    private fun committingStep(): RotationRuntimeState.Applying {
        val inputs = rotationInputs(RotationIntent(true, "RANDOM", 1))
        val before = dueState(inputs).snapshot
        val candidate =
            assertIs<RotationStepPlan.Ready>(
                RotationStepPlanner.planStep(before.checkpoint, inputs, OPERATION, "#aB01Ef"),
            ).candidate
        val completedAt = ClockSample(TimeUnit.HOURS.toMillis(12), 0)
        val checkpoint =
            assertIs<RotationCompletionPlan.Ready>(
                RotationStepPlanner.planCompletion(before.checkpoint, inputs, candidate, completedAt.epochMillis),
            ).checkpoint
        return RotationRuntimeState.Applying(
            before,
            RotationStepCommit(OPERATION, checkpoint, completedAt, candidate),
        )
    }

    private fun dueState(inputs: RuntimeInputs): RotationRuntimeState.Waiting {
        val appointment = RotationSchedule(0, TimeUnit.HOURS.toMillis(1), TimeUnit.HOURS.toMillis(1), 7)
        val checkpoint = RotationRuntimeCheckpoint(inputs.owner, 3, 12, schedule = appointment)
        return RotationRuntimeState.Waiting(
            RotationSnapshot(checkpoint, inputs),
            RotationTicket(inputs.owner, appointment.scheduleSerial, 0, 0),
        )
    }

    private companion object {
        val OPERATION: UUID = UUID.fromString("8a72c301-4822-4137-a89a-4aecab798054")
        val NEXT_OPERATION: UUID = UUID.fromString("12cbd0c5-c5ef-4e44-96a0-8846c52d81b7")
    }
}
