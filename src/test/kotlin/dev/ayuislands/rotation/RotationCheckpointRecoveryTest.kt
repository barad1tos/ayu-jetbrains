package dev.ayuislands.rotation

import dev.ayuislands.studio.model.ApplyFailure
import dev.ayuislands.studio.persistence.CommitResolution
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

class RotationCheckpointRecoveryTest {
    @Test
    fun `a long interval arms a bounded guard without shortening its appointment`() {
        val inputs = rotationInputs(RotationIntent(true, "PRESET", 30))

        val waiting = waitForInterval(inputs)

        assertEquals(24 * H, waiting.ticket.delayMillis)
        assertEquals(30 * H, waiting.ticket.remainingMillis)
        assertEquals(
            42 * H,
            waiting.snapshot.checkpoint.schedule
                ?.deadlineEpochMillis,
        )
        assertEquals(30, inputs.intent.intervalHoursRaw)
    }

    @Test
    fun `guard wakes subtract monotonic time while wall jumps leave the step untouched`() {
        val inputs = rotationInputs(RotationIntent(true, "PRESET", 30))
        val initial = waitForInterval(inputs)
        for (wallTime in listOf(-12 * H, 60 * H)) {
            val guard =
                RotationRuntimeMachine.transition(
                    initial,
                    RotationEvent.TimerDue(initial.ticket, NEXT_OPERATION),
                    ClockSample(wallTime, TimeUnit.HOURS.toNanos(24)),
                )

            val waiting = assertIs<RotationRuntimeState.Waiting>(guard.state)
            assertSame(initial.snapshot.checkpoint, waiting.snapshot.checkpoint)
            assertEquals(6 * H, waiting.ticket.delayMillis)
            assertEquals(6 * H, waiting.ticket.remainingMillis)
            assertEquals(waiting.ticket, assertIs<RotationCommand.ArmSchedule>(guard.commands.single()).ticket)
            val due =
                RotationRuntimeMachine.transition(
                    waiting,
                    RotationEvent.TimerDue(waiting.ticket, NEXT_OPERATION),
                    ClockSample(wallTime, TimeUnit.HOURS.toNanos(30)),
                )
            assertIs<RotationRuntimeState.Applying>(due.state)
            assertIs<RotationCommand.PrepareStep>(due.commands.single())
            assertSame(initial.snapshot.checkpoint, due.state.requireSnapshot().checkpoint)
        }
    }

    @Test
    fun `a replaced guard ticket cannot prepare another step`() {
        val inputs = rotationInputs(RotationIntent(true, "PRESET", 30))
        val initial = waitForInterval(inputs)
        val guarded =
            RotationRuntimeMachine.transition(
                initial,
                RotationEvent.TimerDue(initial.ticket, NEXT_OPERATION),
                ClockSample(36 * H, TimeUnit.HOURS.toNanos(24)),
            )

        val stale =
            RotationRuntimeMachine.transition(
                guarded.state,
                RotationEvent.TimerDue(initial.ticket, NEXT_OPERATION),
                ClockSample(42 * H, TimeUnit.HOURS.toNanos(30)),
            )

        assertSame(guarded.state, stale.state)
        assertTrue(stale.commands.isEmpty())
    }

    @Test
    fun `an ancient overdue appointment cannot overflow into a future wait`() {
        val inputs = rotationInputs()
        val before = checkpoint(inputs, RotationSchedule(Long.MIN_VALUE, Long.MIN_VALUE + H, H, 7))

        val resumed = resume(before, inputs, 12 * H)

        assertSame(before, assertIs<RotationRuntimeState.Waiting>(resumed.state).snapshot.checkpoint)
        assertEquals(0L, assertIs<RotationCommand.ArmSchedule>(resumed.commands.single()).ticket.delayMillis)
    }

    @Test
    fun `a malformed appointment is replaced before any timer is armed`() {
        val inputs = rotationInputs()
        val malformed =
            listOf(
                RotationSchedule(12 * H, 13 * H, 0, 7),
                RotationSchedule(12 * H, 13 * H, -H, 7),
                RotationSchedule(12 * H, 14 * H, H, 7),
                RotationSchedule(12 * H, 13 * H, H, -1),
                RotationSchedule(Long.MAX_VALUE - H / 2, Long.MIN_VALUE + H / 2 - 1, H, 7),
            )
        for (appointment in malformed) {
            val before = checkpoint(inputs, appointment)

            val resumed = resume(before, inputs, 12 * H)

            val pending = assertIs<RotationRuntimeState.Applying>(resumed.state, "$appointment")
            assertSame(before, pending.snapshot.checkpoint)
            val proposal = assertIs<RotationCommand.PersistCheckpoint>(resumed.commands.single()).checkpoint
            assertEquals(12 * H, proposal.schedule?.scheduledAtEpochMillis)
            assertEquals(13 * H, proposal.schedule?.deadlineEpochMillis)
            assertEquals(H, proposal.schedule?.intervalMillisAtScheduling)
            assertEquals(maxOf(0, appointment.scheduleSerial) + 1, proposal.schedule?.scheduleSerial)
            assertEquals(before.sequenceCursorRaw, proposal.sequenceCursorRaw)
            assertEquals(before.successfulStepSerial, proposal.successfulStepSerial)
            assertSame(inputs.appearance, pending.snapshot.inputs.appearance)
        }
    }

    @Test
    fun `backward wall clock repair uses the saved appointment interval`() {
        val inputs = rotationInputs(RotationIntent(true, "PRESET", 1))
        val before = checkpoint(inputs, RotationSchedule(12 * H, 18 * H, 6 * H, 7))

        val resumed = resume(before, inputs, 11 * H)

        val pending = assertIs<RotationRuntimeState.Applying>(resumed.state)
        assertSame(before, pending.snapshot.checkpoint)
        val proposal = assertIs<RotationCommand.PersistCheckpoint>(resumed.commands.single()).checkpoint
        assertEquals(RotationSchedule(11 * H, 17 * H, 6 * H, 8), proposal.schedule)
        val acknowledged =
            completeRotationCommit(pending, OPERATION, clock(11 * H))
        assertEquals(6 * H, assertIs<RotationCommand.ArmSchedule>(acknowledged.commands.single()).ticket.delayMillis)
        assertEquals(1, inputs.intent.intervalHoursRaw)
    }

    @Test
    fun `an absent appointment schedules a full interval without inventing a step`() {
        val inputs = rotationInputs()
        val before = checkpoint(inputs, null)

        val resumed = resume(before, inputs, 12 * H)

        assertSame(before, assertIs<RotationRuntimeState.Applying>(resumed.state).snapshot.checkpoint)
        val proposal = assertIs<RotationCommand.PersistCheckpoint>(resumed.commands.single()).checkpoint
        assertEquals(RotationSchedule(12 * H, 13 * H, H, 1), proposal.schedule)
        assertEquals(before.sequenceCursorRaw, proposal.sequenceCursorRaw)
        assertEquals(before.lastSuccessfulStepEpochMillis, proposal.lastSuccessfulStepEpochMillis)
        assertEquals(before.successfulStepSerial, proposal.successfulStepSerial)
    }

    @Test
    fun `the full Int hour range produces a positive checked appointment`() {
        val inputs = rotationInputs(RotationIntent(true, "PRESET", Int.MAX_VALUE))
        val before = checkpoint(inputs, null)
        val interval = TimeUnit.HOURS.toMillis(Int.MAX_VALUE.toLong())

        val resumed = resume(before, inputs, 12 * H)

        val proposal = assertIs<RotationCommand.PersistCheckpoint>(resumed.commands.single()).checkpoint
        assertEquals(RotationSchedule(12 * H, 12 * H + interval, interval, 1), proposal.schedule)
        assertEquals(Int.MAX_VALUE, inputs.intent.intervalHoursRaw)
    }

    @Test
    fun `unrepresentable repairs fault without wrapping an appointment or serial`() {
        val inputs = rotationInputs()
        val exhausted = checkpoint(inputs, RotationSchedule(12 * H, 14 * H, H, Long.MAX_VALUE))
        val cases = listOf(exhausted to 12 * H, checkpoint(inputs, null) to Long.MAX_VALUE)
        for ((before, now) in cases) {
            val resumed = resume(before, inputs, now)

            val faulted = assertIs<RotationRuntimeState.Faulted.Loaded>(resumed.state)
            assertSame(before, faulted.snapshot.checkpoint)
            assertIs<RuntimeFault.DurableRecovery>(faulted.fault)
            assertIs<RotationCommand.ReportFault>(resumed.commands.single())
        }
    }

    private fun waitForInterval(inputs: RuntimeInputs): RotationRuntimeState.Waiting {
        val before = checkpoint(inputs, null)
        val pending = resume(before, inputs, 12 * H)
        return assertIs<RotationRuntimeState.Waiting>(
            completeRotationCommit(pending.state, OPERATION, clock(12 * H)).state,
        )
    }

    private fun checkpoint(
        inputs: RuntimeInputs,
        schedule: RotationSchedule?,
    ): RotationRuntimeCheckpoint =
        RotationRuntimeCheckpoint(
            owner = inputs.owner,
            sequenceCursorRaw = Int.MIN_VALUE,
            successfulStepSerial = 12,
            lastSuccessfulStepEpochMillis = 5 * H,
            schedule = schedule,
        )

    private fun resume(
        checkpoint: RotationRuntimeCheckpoint,
        inputs: RuntimeInputs,
        now: Long,
    ): RotationTransition {
        val blocked = inputs.copy(availability = inputs.availability.copy(lastDefinitivePro = false))
        val paused = RotationRuntimeState.Paused(RotationSnapshot(checkpoint, blocked))
        return RotationRuntimeMachine.transition(
            paused,
            RotationEvent.AvailabilityObserved(1, inputs.availability, OPERATION),
            clock(now),
        )
    }

    private fun clock(now: Long): ClockSample = ClockSample(now, 0)

    private companion object {
        const val H = 3_600_000L
        val OPERATION: UUID = UUID.fromString("c2891239-55db-4d46-bb47-b70e6648b76c")
        val NEXT_OPERATION: UUID = UUID.fromString("c1e1669d-ea32-4609-a6e2-878e7d29f3f2")
    }
}

class RotationFailureRecoveryTest {
    @Test
    fun `schedule and prepared step persistence carry exact transaction context and settle separately`() {
        val inputs = rotationInputs(RotationIntent(true, "RANDOM", 1))
        val initial = preparing(inputs)
        val paused =
            RotationRuntimeState.Paused(
                initial.snapshot.copy(checkpoint = initial.snapshot.checkpoint.copy(schedule = null)),
            )
        val scheduled =
            RotationRuntimeMachine.transition(
                paused,
                RotationEvent.AvailabilityObserved(1, inputs.availability, CHECKPOINT_ONE),
                at(12),
            )
        val schedule =
            assertIs<RotationScheduleCommit>(assertIs<RotationRuntimeState.Applying>(scheduled.state).operation)
        assertSame(schedule, assertIs<RotationCommand.PersistCheckpoint>(scheduled.commands.single()).operation)
        val effects = prepared(initial)
        val committed = advance(effects, RotationEvent.EffectsSettled(OPERATION, at(12)))
        val step = assertIs<RotationStepCommit>(assertIs<RotationRuntimeState.Applying>(committed.state).operation)
        assertSame(step, assertIs<RotationCommand.PersistCheckpoint>(committed.commands.single()).operation)
        for (pending in listOf(
            assertIs<RotationRuntimeState.Applying>(scheduled.state),
            assertIs<RotationRuntimeState.Applying>(committed.state),
        )) {
            val operation = assertIs<RotationCheckpointCommit>(pending.operation)
            val durable =
                advance(pending, RotationEvent.CommitResolved(operation.operationId, RotationCommitOutcome.Accepted))
            val settling = assertIs<RotationRuntimeState.Applying>(durable.state)
            val context = assertIs<RotationSettlement>(settling.operation)
            assertSame(operation, context.commit)
            assertSame(context, assertIs<RotationCommand.PublishAccepted>(durable.commands.single()).operation)
            assertSame(operation.checkpoint, settling.snapshot.checkpoint)
            val cleaned =
                advance(settling, RotationEvent.CommitResolved(operation.operationId, RotationCommitOutcome.Settled))
            assertIs<RotationRuntimeState.Waiting>(cleaned.state)
            assertIs<RotationCommand.ArmSchedule>(cleaned.commands.single())
        }
    }

    @Test
    fun `rejected prepared step preserves history and journals the exact retry under a fresh identity`() {
        val initial = preparing()
        val candidate = assertIs<RotationStepPreparation>(initial.operation).candidate
        val rejected =
            advance(
                initial,
                RotationEvent.Prepared(OPERATION, RotationPreparationOutcome.RejectedRestored(CHECKPOINT_ONE, FAILURE)),
                at(14),
            )
        val pending = assertIs<RotationRuntimeState.Applying>(rejected.state)
        val write = assertIs<RotationCommand.PersistCheckpoint>(rejected.commands.single())
        assertIs<RotationScheduleCommit>(write.operation)
        assertEquals(CHECKPOINT_ONE, write.operationId)
        assertSame(initial.snapshot.checkpoint, pending.snapshot.checkpoint)
        assertSame(candidate, write.checkpoint.retryCandidate)
        assertEquals(1, pending.requireSnapshot().consecutiveFailures)
        assertEquals(initial.snapshot.checkpoint.sequenceCursorRaw, write.checkpoint.sequenceCursorRaw)
        assertEquals(initial.snapshot.checkpoint.output, write.checkpoint.output)
        assertEquals(12L, write.checkpoint.successfulStepSerial)
        assertEquals(RotationSchedule(14 * H, 15 * H, H, 8), write.checkpoint.schedule)
        val duplicate = advance(pending, RotationEvent.Prepared(OPERATION))
        assertSame(pending, duplicate.state)
        assertTrue(duplicate.commands.isEmpty())
    }

    @Test
    fun `ambiguous preparation resolves its existing journal before any effect or retry`() {
        val initial = preparing()
        val unknown =
            advance(initial, RotationEvent.Prepared(OPERATION, RotationPreparationOutcome.OutcomeUnknown(FAILURE)))
        val resolving = assertIs<RotationRuntimeState.Applying>(unknown.state)
        val resolution = assertIs<RotationOutcomeResolution>(resolving.operation)
        assertSame(initial.operation, resolution.pending)
        assertSame(resolution, assertIs<RotationCommand.ResolveStoreOutcome>(unknown.commands.single()).operation)
        assertSame(initial.snapshot.checkpoint, resolving.snapshot.checkpoint)
        val readback =
            advance(
                resolving,
                RotationEvent.CommitResolved(OPERATION, RotationCommitOutcome.Readback(CommitResolution.NotCommitted)),
            )
        val restoring = assertIs<RotationRuntimeState.Applying>(readback.state)
        val restore = assertIs<RotationRestore>(restoring.operation)
        assertSame(initial.operation, restore.pending)
        assertSame(restore, assertIs<RotationCommand.RestoreOwnedEffects>(readback.commands.single()).operation)
        val restored =
            advance(
                restoring,
                RotationEvent.EffectsSettled(OPERATION, at(14), RotationEffectOutcome.RejectedRestored(CHECKPOINT_ONE)),
                at(14),
            )
        assertEquals(
            CHECKPOINT_ONE,
            assertIs<RotationCommand.PersistCheckpoint>(restored.commands.single()).operationId,
        )
        assertEquals(
            12L,
            restored.state
                .requireSnapshot()
                .checkpoint.successfulStepSerial,
        )
    }

    @Test
    fun `effect failure restores owned values before scheduling its frozen random candidate`() {
        val initial = preparing(rotationInputs(RotationIntent(true, "RANDOM", 2)))
        val effects = prepared(initial)
        val failed =
            advance(
                effects,
                RotationEvent.EffectsSettled(OPERATION, at(13), RotationEffectOutcome.Failed(FAILURE)),
                at(13),
            )
        val restoring = assertIs<RotationRuntimeState.Applying>(failed.state)
        val restore = assertIs<RotationRestore>(restoring.operation)
        assertSame(effects.operation, restore.pending)
        assertSame(restore, assertIs<RotationCommand.RestoreOwnedEffects>(failed.commands.single()).operation)
        assertSame(initial.snapshot.checkpoint, restoring.snapshot.checkpoint)
        assertEquals(0, restoring.snapshot.consecutiveFailures)
        val restored =
            advance(
                restoring,
                RotationEvent.EffectsSettled(OPERATION, at(14), RotationEffectOutcome.RejectedRestored(CHECKPOINT_ONE)),
                at(14),
            )
        val write = assertIs<RotationCommand.PersistCheckpoint>(restored.commands.single())
        assertSame(assertIs<RotationStepPreparation>(initial.operation).candidate, write.checkpoint.retryCandidate)
        assertEquals("#aB01Ef", write.checkpoint.retryCandidate?.hex)
        assertEquals(RotationSchedule(14 * H, 16 * H, 2 * H, 8), write.checkpoint.schedule)
        assertEquals(initial.snapshot.checkpoint.output, write.checkpoint.output)
        assertEquals(Int.MIN_VALUE, write.checkpoint.sequenceCursorRaw)
        val settled = completeRotationCommit(restored.state, CHECKPOINT_ONE, at(14))
        val waiting = assertIs<RotationRuntimeState.Waiting>(settled.state)
        assertEquals(2 * H, waiting.ticket.delayMillis)
        assertEquals(1, waiting.snapshot.consecutiveFailures)
        val retry = advance(waiting, RotationEvent.TimerDue(waiting.ticket, SECOND_ATTEMPT), at(16))
        val preparation = assertIs<RotationCommand.PrepareStep>(retry.commands.single())
        assertEquals(SECOND_ATTEMPT, preparation.operationId)
        assertSame(write.checkpoint.retryCandidate, preparation.candidate)
    }

    @Test
    fun `unknown commit readback accepts its exact proposal once without replaying native work`() {
        val initial = committing()
        val commit = assertIs<RotationStepCommit>(initial.operation)
        val unknownEvent = RotationEvent.CommitResolved(OPERATION, RotationCommitOutcome.OutcomeUnknown(FAILURE))
        val unknown = advance(initial, unknownEvent)
        val resolving = assertIs<RotationRuntimeState.Applying>(unknown.state)
        val resolution = assertIs<RotationOutcomeResolution>(resolving.operation)
        assertSame(commit, resolution.pending)
        assertSame(initial.snapshot.checkpoint, resolving.snapshot.checkpoint)
        assertSame(resolution, assertIs<RotationCommand.ResolveStoreOutcome>(unknown.commands.single()).operation)
        val duplicate = advance(resolving, unknownEvent)
        assertSame(resolving, duplicate.state)
        assertTrue(duplicate.commands.isEmpty())
        val accepted =
            advance(
                resolving,
                RotationEvent.CommitResolved(OPERATION, RotationCommitOutcome.Readback(CommitResolution.Committed)),
            )
        val settling = assertIs<RotationRuntimeState.Applying>(accepted.state)
        val settlement = assertIs<RotationStepSettlement>(settling.operation)
        assertSame(commit, settlement.commit)
        assertSame(commit.checkpoint, settling.snapshot.checkpoint)
        assertEquals(13L, settling.snapshot.checkpoint.successfulStepSerial)
        assertSame(settlement, assertIs<RotationCommand.PublishAccepted>(accepted.commands.single()).operation)
        val repeated =
            advance(
                settling,
                RotationEvent.CommitResolved(OPERATION, RotationCommitOutcome.Readback(CommitResolution.Committed)),
            )
        assertSame(settling, repeated.state)
        assertTrue(repeated.commands.isEmpty())
    }

    @Test
    fun `not committed is not proof of restoration and cannot advance retry history`() {
        val initial = committing()
        val rejected =
            advance(initial, RotationEvent.CommitResolved(OPERATION, RotationCommitOutcome.NotCommitted(FAILURE)))
        val restoring = assertIs<RotationRuntimeState.Applying>(rejected.state)
        val restore = assertIs<RotationRestore>(restoring.operation)
        assertSame(initial.operation, restore.pending)
        assertSame(initial.snapshot.checkpoint, restoring.snapshot.checkpoint)
        assertEquals(0, restoring.snapshot.consecutiveFailures)
        assertSame(restore, assertIs<RotationCommand.RestoreOwnedEffects>(rejected.commands.single()).operation)
        val unresolved =
            advance(
                restoring,
                RotationEvent.EffectsSettled(OPERATION, at(13), RotationEffectOutcome.RecoveryPending(FAILURE)),
                at(13),
            )
        val faulted = assertIs<RotationRuntimeState.Faulted.Loaded>(unresolved.state)
        assertSame(restore, faulted.pendingOperation)
        assertSame(initial.snapshot.checkpoint, faulted.snapshot.checkpoint)
        assertEquals(0, faulted.snapshot.consecutiveFailures)
        assertIs<RuntimeFault.DurableRecovery>(faulted.fault)
        assertIs<RotationCommand.ReportFault>(unresolved.commands.single())
        val retried = advance(faulted, RotationEvent.RetryRecovery(RECOVERY_REQUEST), at(14))
        val resolution =
            assertIs<RotationOutcomeResolution>(assertIs<RotationRuntimeState.Applying>(retried.state).operation)
        assertSame(initial.operation, resolution.pending)
        assertEquals(RECOVERY_REQUEST, resolution.completionId)
        assertIs<RotationCommand.ResolveStoreOutcome>(retried.commands.single())
    }

    @Test
    fun `unknown readback faults and explicit retry rejects a stale recovery acknowledgement`() {
        val initial = committing()
        val unknown =
            advance(initial, RotationEvent.CommitResolved(OPERATION, RotationCommitOutcome.OutcomeUnknown(FAILURE)))
        val unreadable =
            advance(
                unknown.state,
                RotationEvent.CommitResolved(OPERATION, RotationCommitOutcome.Readback(CommitResolution.Unknown)),
            )
        val faulted = assertIs<RotationRuntimeState.Faulted.Loaded>(unreadable.state)
        val retained = assertIs<RotationOutcomeResolution>(faulted.pendingOperation)
        assertSame(initial.operation, retained.pending)
        assertSame(initial.snapshot.checkpoint, faulted.snapshot.checkpoint)
        assertIs<RotationCommand.ReportFault>(unreadable.commands.single())
        val retried = advance(faulted, RotationEvent.RetryRecovery(RECOVERY_REQUEST))
        val resolving = assertIs<RotationRuntimeState.Applying>(retried.state)
        val request = assertIs<RotationCommand.ResolveStoreOutcome>(retried.commands.single()).operation
        assertEquals(OPERATION, request.operationId)
        assertEquals(RECOVERY_REQUEST, request.completionId)
        assertSame(initial.operation, request.pending)
        val old =
            advance(
                resolving,
                RotationEvent.CommitResolved(OPERATION, RotationCommitOutcome.Readback(CommitResolution.Committed)),
            )
        assertSame(resolving, old.state)
        assertTrue(old.commands.isEmpty())
        val current =
            advance(
                resolving,
                RotationEvent.CommitResolved(
                    OPERATION,
                    RotationCommitOutcome.Readback(CommitResolution.NotCommitted),
                    RECOVERY_REQUEST,
                ),
            )
        val restore = assertIs<RotationRestore>(assertIs<RotationRuntimeState.Applying>(current.state).operation)
        assertEquals(RECOVERY_REQUEST, restore.completionId)
        assertIs<RotationCommand.RestoreOwnedEffects>(current.commands.single())
    }

    @Test
    fun `retry checkpoint uncertainty retains the frozen candidate and cannot arm a timer`() {
        val initial = preparing()
        val retry = failStep(initial, CHECKPOINT_ONE, at(14))
        val proposal = assertIs<RotationScheduleCommit>(assertIs<RotationRuntimeState.Applying>(retry.state).operation)
        val unknown =
            advance(
                retry.state,
                RotationEvent.CommitResolved(CHECKPOINT_ONE, RotationCommitOutcome.OutcomeUnknown(FAILURE)),
                at(14),
            )
        val resolution =
            assertIs<RotationOutcomeResolution>(assertIs<RotationRuntimeState.Applying>(unknown.state).operation)
        assertSame(proposal, resolution.pending)
        assertSame(initial.snapshot.checkpoint, unknown.state.requireSnapshot().checkpoint)
        assertIs<RotationCommand.ResolveStoreOutcome>(unknown.commands.single())
        val accepted =
            advance(
                unknown.state,
                RotationEvent.CommitResolved(
                    CHECKPOINT_ONE,
                    RotationCommitOutcome.Readback(CommitResolution.Committed),
                ),
                at(14),
            )
        val settling = assertIs<RotationRuntimeState.Applying>(accepted.state)
        assertIs<RotationScheduleSettlement>(settling.operation)
        assertSame(proposal.checkpoint, settling.snapshot.checkpoint)
        assertEquals(12L, settling.snapshot.checkpoint.successfulStepSerial)
        assertEquals(1, settling.snapshot.consecutiveFailures)
        assertIs<RotationCommand.PublishAccepted>(accepted.commands.single())
        val completed =
            advance(settling, RotationEvent.CommitResolved(CHECKPOINT_ONE, RotationCommitOutcome.Settled), at(14))
        assertEquals(H, assertIs<RotationRuntimeState.Waiting>(completed.state).ticket.delayMillis)
        assertIs<RotationCommand.ArmSchedule>(completed.commands.single())
    }

    @Test
    fun `three fully restored automatic failures stop and explicit recovery keeps the same candidate`() {
        var initial = preparing()
        val candidate = assertIs<RotationStepPreparation>(initial.operation).candidate
        val retries =
            listOf(
                CHECKPOINT_ONE to SECOND_ATTEMPT,
                CHECKPOINT_TWO to THIRD_ATTEMPT,
                CHECKPOINT_THREE to RECOVERY_REQUEST,
            )
        var breaker: RotationRuntimeState.Faulted.Loaded? = null
        retries.forEachIndexed { index, (checkpointId, nextAttempt) ->
            val rollbackTime = at(14 + index * 2)
            val failed = failStep(initial, checkpointId, rollbackTime)
            assertEquals(index + 1, failed.state.requireSnapshot().consecutiveFailures)
            val write = assertIs<RotationCommand.PersistCheckpoint>(failed.commands.single())
            assertSame(candidate, write.checkpoint.retryCandidate)
            assertEquals(12L, write.checkpoint.successfulStepSerial)
            val settled = completeRotationCommit(failed.state, checkpointId, rollbackTime)
            if (index < 2) {
                val waiting = assertIs<RotationRuntimeState.Waiting>(settled.state)
                val due = advance(waiting, RotationEvent.TimerDue(waiting.ticket, nextAttempt), at(15 + index * 2))
                initial = assertIs<RotationRuntimeState.Applying>(due.state)
                assertSame(candidate, assertIs<RotationCommand.PrepareStep>(due.commands.single()).candidate)
            } else {
                val failureState = assertIs<RotationRuntimeState.Faulted.Loaded>(settled.state)
                breaker = failureState
                assertIs<RuntimeFault.SessionCircuitBreaker>(failureState.fault)
                assertIs<RotationCommand.ReportFault>(settled.commands.single())
            }
        }
        val stopped = requireNotNull(breaker)
        val retried = advance(stopped, RotationEvent.RetryRecovery(RECOVERY_REQUEST), at(20))
        val schedule = assertIs<RotationCommand.PersistCheckpoint>(retried.commands.single())
        assertEquals(RECOVERY_REQUEST, schedule.operationId)
        assertSame(candidate, schedule.checkpoint.retryCandidate)
        assertEquals(0, retried.state.requireSnapshot().consecutiveFailures)
        assertEquals(RotationSchedule(20 * H, 21 * H, H, 11), schedule.checkpoint.schedule)
        val resumed = completeRotationCommit(retried.state, RECOVERY_REQUEST, at(20))
        assertEquals(H, assertIs<RotationRuntimeState.Waiting>(resumed.state).ticket.delayMillis)
    }

    @Test
    fun `accepted step and ordinary stopped eligibility reset only the session failure budget`() {
        val initial = preparing()
        val twiceFailed = initial.copy(snapshot = initial.snapshot.copy(consecutiveFailures = 2))
        val pending = committing(twiceFailed)
        val accepted = advance(pending, RotationEvent.CommitResolved(OPERATION, RotationCommitOutcome.Accepted))
        assertEquals(0, accepted.state.requireSnapshot().consecutiveFailures)
        val waiting =
            RotationRuntimeState.Waiting(
                twiceFailed.snapshot,
                RotationTicket(initial.snapshot.inputs.owner, 7, H, at(12).monotonicNanos),
            )
        val denied =
            waiting.snapshot.inputs.copy(
                availability =
                    waiting.snapshot.inputs.availability
                        .copy(lastDefinitivePro = false),
            )
        val paused =
            advance(
                waiting,
                RotationEvent.AvailabilityObserved(1, denied.availability, RECOVERY_REQUEST),
                at(12),
            )
        assertEquals(0, assertIs<RotationRuntimeState.Paused>(paused.state).snapshot.consecutiveFailures)
        assertSame(initial.snapshot.checkpoint, paused.state.requireSnapshot().checkpoint)
        assertIs<RotationCommand.CancelSchedule>(paused.commands.single())
    }

    @Test
    fun `recovery observations never clear durable ownership or alter accepted user choices`() {
        val initial = committing()
        val unknown =
            advance(initial, RotationEvent.CommitResolved(OPERATION, RotationCommitOutcome.OutcomeUnknown(FAILURE)))
        val stopped =
            advance(
                unknown.state,
                RotationEvent.CommitResolved(OPERATION, RotationCommitOutcome.Readback(CommitResolution.Unknown)),
            )
        val faulted = assertIs<RotationRuntimeState.Faulted.Loaded>(stopped.state)
        for (mask in 0 until 32) {
            val previous = faulted.snapshot.inputs
            val gates =
                RuntimeAvailability(
                    lastDefinitivePro = mask and 2 == 0,
                    effectiveFollowSystem = mask and 1 != 0,
                    variant = previous.availability.variant.takeIf { mask and 4 == 0 },
                    targetValid = mask and 8 == 0,
                    recoveryPending = mask and 16 != 0,
                )
            val observed =
                advance(faulted, RotationEvent.AvailabilityObserved(1, gates, RECOVERY_REQUEST), at(13))
            val retained = assertIs<RotationRuntimeState.Faulted.Loaded>(observed.state)
            assertSame(faulted.pendingOperation, retained.pendingOperation)
            assertSame(faulted.snapshot.checkpoint, retained.snapshot.checkpoint)
            assertSame(faulted.fault, retained.fault)
            assertSame(previous.appearance, retained.snapshot.inputs.appearance)
            assertTrue(observed.commands.isEmpty())
            val duplicate =
                advance(retained, RotationEvent.AvailabilityObserved(1, gates, RECOVERY_REQUEST), at(14))
            assertSame(retained, duplicate.state)
            assertTrue(duplicate.commands.isEmpty())
        }
    }

    @Test
    fun `Follow cycle during cleanup fault retains a full interval until final eligibility returns`() {
        val initial = committing()
        val accepted = advance(initial, RotationEvent.CommitResolved(OPERATION, RotationCommitOutcome.Accepted))
        val failed =
            advance(
                accepted.state,
                RotationEvent.CommitResolved(
                    OPERATION,
                    RotationCommitOutcome.AcceptedCleanupPending(
                        RotationSettlementProgress.CLEANUP_PENDING,
                        RuntimeFault.DurableRecovery("cleanup"),
                    ),
                ),
            )
        var state: RotationRuntimeState = failed.state
        val originalInputs = state.requireSnapshot().inputs
        val snapshots =
            listOf(
                originalInputs.availability.copy(recoveryPending = true),
                originalInputs.availability.copy(recoveryPending = true, effectiveFollowSystem = true),
                originalInputs.availability.copy(recoveryPending = true),
            )
        snapshots.forEachIndexed { index, availability ->
            val observed =
                advance(
                    state,
                    RotationEvent.AvailabilityObserved(index + 1L, availability, SECOND_ATTEMPT),
                    at(13),
                )
            assertIs<RotationRuntimeState.Faulted.Loaded>(observed.state)
            assertTrue(observed.commands.isEmpty())
            state = observed.state
        }
        val retried = advance(state, RotationEvent.RetryRecovery(RECOVERY_REQUEST), at(14))
        val cleaned =
            advance(
                retried.state,
                RotationEvent.CommitResolved(OPERATION, RotationCommitOutcome.Settled, RECOVERY_REQUEST),
                at(14),
            )
        assertIs<RotationRuntimeState.Paused>(cleaned.state)
        assertTrue(cleaned.commands.isEmpty())
        val resumed =
            advance(
                cleaned.state,
                RotationEvent.AvailabilityObserved(4, originalInputs.availability, CHECKPOINT_ONE),
                at(15),
            )
        val schedule = assertIs<RotationCommand.PersistCheckpoint>(resumed.commands.single())
        assertEquals(CHECKPOINT_ONE, schedule.operationId)
        assertEquals(15 * H, schedule.checkpoint.schedule?.scheduledAtEpochMillis)
        assertEquals(16 * H, schedule.checkpoint.schedule?.deadlineEpochMillis)
        assertEquals(13L, schedule.checkpoint.successfulStepSerial)
        assertEquals(RotationResume.KeepDeadline, schedule.checkpoint.resume)
    }

    @Test
    fun `schedule cleanup recovery retains runtime context and rejects an earlier completion`() {
        val initial = preparing()
        val proposal = failStep(initial, CHECKPOINT_ONE, at(14))
        val accepted =
            advance(
                proposal.state,
                RotationEvent.CommitResolved(CHECKPOINT_ONE, RotationCommitOutcome.Accepted),
                at(14),
            )
        val settling = assertIs<RotationRuntimeState.Applying>(accepted.state)
        val settlement = assertIs<RotationScheduleSettlement>(settling.operation)
        val pending =
            advance(
                settling,
                RotationEvent.CommitResolved(
                    CHECKPOINT_ONE,
                    RotationCommitOutcome.AcceptedCleanupPending(
                        RotationSettlementProgress.CLEANUP_PENDING,
                        RuntimeFault.DurableRecovery("cleanup"),
                    ),
                ),
                at(14),
            )
        val faulted = assertIs<RotationRuntimeState.Faulted.Loaded>(pending.state)
        assertSame(settlement.commit.checkpoint, faulted.snapshot.checkpoint)
        assertEquals(12L, faulted.snapshot.checkpoint.successfulStepSerial)
        val retried = advance(faulted, RotationEvent.RetryRecovery(RECOVERY_REQUEST), at(14))
        val resumed = assertIs<RotationRuntimeState.Applying>(retried.state)
        val command = assertIs<RotationCommand.PublishAccepted>(retried.commands.single())
        val request = assertIs<RotationScheduleSettlement>(command.operation)
        assertSame(settlement.commit, request.commit)
        assertEquals(CHECKPOINT_ONE, request.operationId)
        assertEquals(RECOVERY_REQUEST, request.completionId)
        assertEquals(RotationSettlementProgress.CLEANUP_PENDING, request.progress)
        val stale =
            advance(resumed, RotationEvent.CommitResolved(CHECKPOINT_ONE, RotationCommitOutcome.Settled), at(14))
        assertSame(resumed, stale.state)
        assertTrue(stale.commands.isEmpty())
        val completed =
            advance(
                resumed,
                RotationEvent.CommitResolved(CHECKPOINT_ONE, RotationCommitOutcome.Settled, RECOVERY_REQUEST),
                at(14),
            )
        assertSame(settlement.commit.checkpoint, completed.state.requireSnapshot().checkpoint)
        assertIs<RotationRuntimeState.Waiting>(completed.state)
        assertIs<RotationCommand.ArmSchedule>(completed.commands.single())
    }

    @Test
    fun `a restoration retry rejects an old acknowledgement before journaling another appointment`() {
        val effects = prepared(preparing())
        val failed =
            advance(
                effects,
                RotationEvent.EffectsSettled(OPERATION, at(13), RotationEffectOutcome.Failed(FAILURE)),
                at(13),
            )
        val incomplete =
            advance(
                failed.state,
                RotationEvent.EffectsSettled(OPERATION, at(13), RotationEffectOutcome.RecoveryPending(FAILURE)),
                at(13),
            )
        val retried = advance(incomplete.state, RotationEvent.RetryRecovery(RECOVERY_REQUEST), at(14))
        val known =
            advance(
                retried.state,
                RotationEvent.CommitResolved(
                    OPERATION,
                    RotationCommitOutcome.Readback(CommitResolution.NotCommitted),
                    RECOVERY_REQUEST,
                ),
                at(14),
            )
        val restoring = assertIs<RotationRuntimeState.Applying>(known.state)
        val request = assertIs<RotationCommand.RestoreOwnedEffects>(known.commands.single()).operation
        assertEquals(OPERATION, request.operationId)
        assertEquals(RECOVERY_REQUEST, request.completionId)
        val stale =
            advance(
                restoring,
                RotationEvent.EffectsSettled(OPERATION, at(14), RotationEffectOutcome.RejectedRestored(CHECKPOINT_ONE)),
                at(14),
            )
        assertSame(restoring, stale.state)
        assertTrue(stale.commands.isEmpty())
        val restored =
            advance(
                restoring,
                RotationEvent.EffectsSettled(
                    OPERATION,
                    at(14),
                    RotationEffectOutcome.RejectedRestored(CHECKPOINT_ONE),
                    RECOVERY_REQUEST,
                ),
                at(14),
            )
        assertEquals(
            CHECKPOINT_ONE,
            assertIs<RotationCommand.PersistCheckpoint>(restored.commands.single()).operationId,
        )
        assertEquals(1, restored.state.requireSnapshot().consecutiveFailures)
        assertEquals(
            12L,
            restored.state
                .requireSnapshot()
                .checkpoint.successfulStepSerial,
        )
    }

    @Test
    fun `restored failure waits for every eligibility gate before starting its full retry interval`() {
        for (mask in 1 until 32) {
            val initial = preparing()
            val twiceFailed = initial.copy(snapshot = initial.snapshot.copy(consecutiveFailures = 2))
            val effects = prepared(twiceFailed)
            val failed =
                advance(effects, RotationEvent.EffectsSettled(OPERATION, at(13), RotationEffectOutcome.Failed(FAILURE)))
            val originalInputs = initial.snapshot.inputs
            val gates =
                RuntimeAvailability(
                    lastDefinitivePro = mask and 2 == 0,
                    effectiveFollowSystem = mask and 1 != 0,
                    variant = originalInputs.availability.variant.takeIf { mask and 4 == 0 },
                    targetValid = mask and 8 == 0,
                    recoveryPending = mask and 16 != 0,
                )
            val observed =
                advance(failed.state, RotationEvent.AvailabilityObserved(1, gates, SECOND_ATTEMPT), at(13))
            val restored =
                advance(
                    observed.state,
                    RotationEvent.EffectsSettled(
                        OPERATION,
                        at(14),
                        RotationEffectOutcome.RejectedRestored(CHECKPOINT_ONE),
                    ),
                    at(14),
                )
            val candidate = assertIs<RotationStepPreparation>(initial.operation).candidate
            val paused =
                if (gates.recoveryPending) {
                    assertTrue(restored.commands.isEmpty())
                    assertSame(initial.snapshot.checkpoint, restored.state.requireSnapshot().checkpoint)
                    assertIs<RotationRuntimeState.Paused>(restored.state)
                } else {
                    val retained = assertIs<RotationCommand.PersistCheckpoint>(restored.commands.single())
                    assertSame(candidate, retained.checkpoint.retryCandidate)
                    assertIs<RotationResume.FullInterval>(retained.checkpoint.resume)
                    val saved = completeRotationCommit(restored.state, CHECKPOINT_ONE, at(14))
                    assertTrue(saved.commands.isEmpty())
                    assertIs<RotationRuntimeState.Paused>(saved.state)
                }
            assertEquals(0, paused.snapshot.consecutiveFailures)
            assertEquals(12L, paused.snapshot.checkpoint.successfulStepSerial)
            val resumed =
                advance(
                    paused,
                    RotationEvent.AvailabilityObserved(2, originalInputs.availability, CHECKPOINT_TWO),
                    at(16),
                )
            val schedule = assertIs<RotationCommand.PersistCheckpoint>(resumed.commands.single())
            assertEquals(CHECKPOINT_TWO, schedule.operationId)
            assertSame(candidate, schedule.checkpoint.retryCandidate)
            assertEquals(16 * H, schedule.checkpoint.schedule?.scheduledAtEpochMillis)
            assertEquals(17 * H, schedule.checkpoint.schedule?.deadlineEpochMillis)
            assertEquals(RotationResume.KeepDeadline, schedule.checkpoint.resume)
            val duplicate =
                advance(
                    resumed.state,
                    RotationEvent.AvailabilityObserved(2, originalInputs.availability, CHECKPOINT_TWO),
                    at(17),
                )
            assertSame(resumed.state, duplicate.state)
            assertTrue(duplicate.commands.isEmpty())
            val waiting = completeRotationCommit(resumed.state, CHECKPOINT_TWO, at(16))
            assertEquals(H, assertIs<RotationRuntimeState.Waiting>(waiting.state).ticket.delayMillis)
        }
    }

    @Test
    fun `rejected retry checkpoint retains the candidate for an explicit fresh storage attempt`() {
        val initial = preparing()
        val retry = failStep(initial, CHECKPOINT_ONE, at(14))
        val proposed = assertIs<RotationCommand.PersistCheckpoint>(retry.commands.single()).checkpoint
        val rejected =
            advance(
                retry.state,
                RotationEvent.CommitResolved(CHECKPOINT_ONE, RotationCommitOutcome.RejectedRestored),
                at(14),
            )
        val faulted = assertIs<RotationRuntimeState.Faulted.Loaded>(rejected.state)
        assertSame(initial.snapshot.checkpoint, faulted.snapshot.checkpoint)
        assertIs<RuntimeFault.DurableRecovery>(faulted.fault)
        assertIs<RotationCommand.ReportFault>(rejected.commands.single())
        val retried = advance(faulted, RotationEvent.RetryRecovery(RECOVERY_REQUEST), at(16))
        val write = assertIs<RotationCommand.PersistCheckpoint>(retried.commands.single())
        assertEquals(RECOVERY_REQUEST, write.operationId)
        assertSame(proposed.retryCandidate, write.checkpoint.retryCandidate)
        assertEquals(12L, write.checkpoint.successfulStepSerial)
        assertEquals(16 * H, write.checkpoint.schedule?.scheduledAtEpochMillis)
        assertEquals(17 * H, write.checkpoint.schedule?.deadlineEpochMillis)
        val completed = completeRotationCommit(retried.state, RECOVERY_REQUEST, at(16))
        assertEquals(H, assertIs<RotationRuntimeState.Waiting>(completed.state).ticket.delayMillis)
    }

    @Test
    fun `eligibility clearing during retry checkpoint persistence starts one full interval`() {
        val initial = preparing()
        val effects = prepared(initial)
        val failed =
            advance(effects, RotationEvent.EffectsSettled(OPERATION, at(13), RotationEffectOutcome.Failed(FAILURE)))
        val originalInputs = initial.snapshot.inputs
        val denied = originalInputs.copy(availability = originalInputs.availability.copy(lastDefinitivePro = false))
        val observed =
            advance(
                failed.state,
                RotationEvent.AvailabilityObserved(1, denied.availability, SECOND_ATTEMPT),
                at(13),
            )
        val restored =
            advance(
                observed.state,
                RotationEvent.EffectsSettled(OPERATION, at(14), RotationEffectOutcome.RejectedRestored(CHECKPOINT_ONE)),
                at(14),
            )
        val retained = assertIs<RotationCommand.PersistCheckpoint>(restored.commands.single())
        assertIs<RotationResume.FullInterval>(retained.checkpoint.resume)
        val eligible =
            advance(
                restored.state,
                RotationEvent.AvailabilityObserved(2, originalInputs.availability, CHECKPOINT_TWO),
                at(16),
            )
        assertTrue(eligible.commands.isEmpty())
        val settled = completeRotationCommit(eligible.state, CHECKPOINT_ONE, at(17))
        val schedule = assertIs<RotationCommand.PersistCheckpoint>(settled.commands.single())
        assertEquals(CHECKPOINT_TWO, schedule.operationId)
        assertEquals(16 * H, schedule.checkpoint.schedule?.scheduledAtEpochMillis)
        assertEquals(17 * H, schedule.checkpoint.schedule?.deadlineEpochMillis)
        assertSame(retained.checkpoint.retryCandidate, schedule.checkpoint.retryCandidate)
        assertEquals(12L, schedule.checkpoint.successfulStepSerial)
        assertEquals(RotationResume.KeepDeadline, schedule.checkpoint.resume)
        val waiting = completeRotationCommit(settled.state, CHECKPOINT_TWO, at(17))
        assertEquals(0L, assertIs<RotationRuntimeState.Waiting>(waiting.state).ticket.delayMillis)
    }

    @Test
    fun `completion deadline overflow restores applied effects without losing ownership`() {
        val initial = preparing()
        val effects = prepared(initial)
        val completedAt = ClockSample(Long.MAX_VALUE, at(13).monotonicNanos)
        val blocked = advance(effects, RotationEvent.EffectsSettled(OPERATION, completedAt), completedAt)
        val restore = assertIs<RotationCommand.RestoreOwnedEffects>(blocked.commands.single()).operation
        assertSame(effects.operation, restore.pending)
        assertSame(initial.snapshot.checkpoint, blocked.state.requireSnapshot().checkpoint)
        assertEquals(0, blocked.state.requireSnapshot().consecutiveFailures)
        val restored =
            advance(
                blocked.state,
                RotationEvent.EffectsSettled(OPERATION, at(14), RotationEffectOutcome.RejectedRestored(CHECKPOINT_ONE)),
                at(14),
            )
        val retry = assertIs<RotationCommand.PersistCheckpoint>(restored.commands.single())
        assertSame(assertIs<RotationStepPreparation>(initial.operation).candidate, retry.checkpoint.retryCandidate)
        assertEquals(12L, retry.checkpoint.successfulStepSerial)
        val stillOverflowing =
            advance(
                blocked.state,
                RotationEvent.EffectsSettled(
                    OPERATION,
                    completedAt,
                    RotationEffectOutcome.RejectedRestored(CHECKPOINT_ONE),
                ),
                completedAt,
            )
        val faulted = assertIs<RotationRuntimeState.Faulted.Loaded>(stillOverflowing.state)
        assertSame(initial.snapshot.checkpoint, faulted.snapshot.checkpoint)
        assertEquals(1, faulted.snapshot.consecutiveFailures)
        assertIs<RotationCommand.ReportFault>(stillOverflowing.commands.single())
        val recovered = advance(faulted, RotationEvent.RetryRecovery(RECOVERY_REQUEST), at(14))
        val recoveredWrite = assertIs<RotationCommand.PersistCheckpoint>(recovered.commands.single())
        assertEquals(RECOVERY_REQUEST, recoveredWrite.operationId)
        assertSame(
            assertIs<RotationStepPreparation>(initial.operation).candidate,
            recoveredWrite.checkpoint.retryCandidate,
        )
        assertEquals(12L, recoveredWrite.checkpoint.successfulStepSerial)
        assertEquals(RotationSchedule(14 * H, 15 * H, H, 8), recoveredWrite.checkpoint.schedule)
        assertEquals(1, recovered.state.requireSnapshot().consecutiveFailures)
    }

    @Test
    fun `restored checkpoint without a color candidate can retry its schedule explicitly`() {
        val initial = preparing()
        val paused =
            RotationRuntimeState.Paused(
                initial.snapshot.copy(checkpoint = initial.snapshot.checkpoint.copy(schedule = null)),
            )
        val scheduled =
            advance(
                paused,
                RotationEvent.AvailabilityObserved(1, paused.snapshot.inputs.availability, CHECKPOINT_ONE),
                at(12),
            )
        val proposal = assertIs<RotationCommand.PersistCheckpoint>(scheduled.commands.single()).operation
        val failed =
            advance(
                scheduled.state,
                RotationEvent.CommitResolved(CHECKPOINT_ONE, RotationCommitOutcome.NotCommitted(FAILURE)),
                at(13),
            )
        val restore = assertIs<RotationCommand.RestoreOwnedEffects>(failed.commands.single()).operation
        assertSame(proposal, restore.pending)
        val restored =
            advance(
                failed.state,
                RotationEvent.EffectsSettled(
                    CHECKPOINT_ONE,
                    at(14),
                    RotationEffectOutcome.RejectedRestored(CHECKPOINT_TWO),
                ),
                at(14),
            )
        assertIs<RotationRuntimeState.Faulted.Loaded>(restored.state)
        assertIs<RotationCommand.ReportFault>(restored.commands.single())
        assertSame(paused.snapshot.checkpoint, restored.state.requireSnapshot().checkpoint)
        val retried = advance(restored.state, RotationEvent.RetryRecovery(RECOVERY_REQUEST), at(16))
        val write = assertIs<RotationCommand.PersistCheckpoint>(retried.commands.single())
        assertEquals(RECOVERY_REQUEST, write.operationId)
        assertEquals(null, write.checkpoint.retryCandidate)
        assertEquals(paused.snapshot.checkpoint.sequenceCursorRaw, write.checkpoint.sequenceCursorRaw)
        assertEquals(paused.snapshot.checkpoint.output, write.checkpoint.output)
        assertEquals(12L, write.checkpoint.successfulStepSerial)
        assertEquals(RotationSchedule(16 * H, 17 * H, H, 1), write.checkpoint.schedule)
        val completed = completeRotationCommit(retried.state, RECOVERY_REQUEST, at(16))
        assertEquals(H, assertIs<RotationRuntimeState.Waiting>(completed.state).ticket.delayMillis)
    }

    @Test
    fun `stopped eligibility resets the failure budget at every pending settlement boundary`() {
        for (boundary in RecoveryBoundary.entries) {
            for (mask in 1 until 32) {
                val initial = preparing()
                val twiceFailed = initial.copy(snapshot = initial.snapshot.copy(consecutiveFailures = 2))
                val retry = failStep(twiceFailed, CHECKPOINT_ONE, at(14))
                val durable =
                    if (boundary == RecoveryBoundary.CHECKPOINT) {
                        retry
                    } else {
                        advance(
                            retry.state,
                            RotationEvent.CommitResolved(CHECKPOINT_ONE, RotationCommitOutcome.Accepted),
                            at(14),
                        )
                    }
                val pending =
                    if (boundary == RecoveryBoundary.FAULTED) {
                        advance(
                            durable.state,
                            RotationEvent.CommitResolved(
                                CHECKPOINT_ONE,
                                RotationCommitOutcome.AcceptedCleanupPending(
                                    RotationSettlementProgress.CLEANUP_PENDING,
                                    RuntimeFault.DurableRecovery("cleanup"),
                                ),
                            ),
                            at(14),
                        ).state
                    } else {
                        durable.state
                    }
                assertEquals(3, pending.requireSnapshot().consecutiveFailures)
                val originalInputs = initial.snapshot.inputs
                val gates =
                    RuntimeAvailability(
                        lastDefinitivePro = mask and 2 == 0,
                        effectiveFollowSystem = mask and 1 != 0,
                        variant = originalInputs.availability.variant.takeIf { mask and 4 == 0 },
                        targetValid = mask and 8 == 0,
                        recoveryPending = mask and 16 != 0,
                    )
                val observed =
                    advance(
                        pending,
                        RotationEvent.AvailabilityObserved(1, gates, SECOND_ATTEMPT),
                        at(14),
                    )
                assertEquals(0, observed.state.requireSnapshot().consecutiveFailures)
                assertSame(pending.requireSnapshot().checkpoint, observed.state.requireSnapshot().checkpoint)
                assertTrue(observed.commands.isEmpty())
                val cleaned =
                    when (boundary) {
                        RecoveryBoundary.CHECKPOINT -> {
                            completeRotationCommit(observed.state, CHECKPOINT_ONE, at(14))
                        }

                        RecoveryBoundary.SETTLEMENT -> {
                            advance(
                                observed.state,
                                RotationEvent.CommitResolved(CHECKPOINT_ONE, RotationCommitOutcome.Settled),
                                at(14),
                            )
                        }

                        RecoveryBoundary.FAULTED -> {
                            val beforeFault = assertIs<RotationRuntimeState.Faulted.Loaded>(pending)
                            val afterFault = assertIs<RotationRuntimeState.Faulted.Loaded>(observed.state)
                            assertSame(beforeFault.fault, afterFault.fault)
                            assertSame(beforeFault.pendingOperation, afterFault.pendingOperation)
                            val recovering = advance(afterFault, RotationEvent.RetryRecovery(RECOVERY_REQUEST), at(14))
                            advance(
                                recovering.state,
                                RotationEvent.CommitResolved(
                                    CHECKPOINT_ONE,
                                    RotationCommitOutcome.Settled,
                                    RECOVERY_REQUEST,
                                ),
                                at(14),
                            )
                        }
                    }
                val settled =
                    if (cleaned.state is RotationRuntimeState.Applying) {
                        val write = assertIs<RotationCommand.PersistCheckpoint>(cleaned.commands.single())
                        completeRotationCommit(cleaned.state, write.operationId, at(14))
                    } else {
                        cleaned
                    }
                assertIs<RotationRuntimeState.Paused>(settled.state)
                assertEquals(0, settled.state.requireSnapshot().consecutiveFailures)
                assertEquals(
                    12L,
                    settled.state
                        .requireSnapshot()
                        .checkpoint.successfulStepSerial,
                )
                assertTrue(settled.commands.isEmpty())
            }
        }
    }

    private fun preparing(
        inputs: RuntimeInputs = rotationInputs(RotationIntent(true, "RANDOM", 1)),
    ): RotationRuntimeState.Applying {
        val checkpoint =
            RotationRuntimeCheckpoint(
                owner = inputs.owner,
                sequenceCursorRaw = Int.MIN_VALUE,
                successfulStepSerial = 12,
                lastSuccessfulStepEpochMillis = 5 * H,
                output = RotationOutput(inputs.owner, requireNotNull(inputs.availability.variant), "#112233"),
                schedule = RotationSchedule(11 * H, 12 * H, H, 7),
            )
        val candidate =
            assertIs<RotationStepPlan.Ready>(
                RotationStepPlanner.planStep(checkpoint, inputs, OPERATION, "#aB01Ef"),
            ).candidate
        return RotationRuntimeState.Applying(
            RotationSnapshot(checkpoint, inputs),
            RotationStepPreparation(OPERATION, candidate),
        )
    }

    private fun prepared(
        initial: RotationRuntimeState.Applying,
        clock: ClockSample = at(12),
    ): RotationRuntimeState.Applying {
        val prepared = advance(initial, RotationEvent.Prepared(initial.operation.operationId), clock)
        assertIs<RotationCommand.ApplyPreparedEffects>(prepared.commands.single())
        return assertIs<RotationRuntimeState.Applying>(prepared.state)
    }

    private fun committing(initial: RotationRuntimeState.Applying = preparing()): RotationRuntimeState.Applying {
        val effects = prepared(initial)
        return assertIs<RotationRuntimeState.Applying>(
            advance(effects, RotationEvent.EffectsSettled(initial.operation.operationId, at(12))).state,
        )
    }

    private fun failStep(
        initial: RotationRuntimeState.Applying,
        checkpointId: UUID,
        completedAt: ClockSample,
    ): RotationTransition {
        val effects = prepared(initial, completedAt)
        val failed =
            advance(
                effects,
                RotationEvent.EffectsSettled(
                    initial.operation.operationId,
                    completedAt,
                    RotationEffectOutcome.Failed(FAILURE),
                ),
                completedAt,
            )
        assertIs<RotationCommand.RestoreOwnedEffects>(failed.commands.single())
        return advance(
            failed.state,
            RotationEvent.EffectsSettled(
                initial.operation.operationId,
                completedAt,
                RotationEffectOutcome.RejectedRestored(checkpointId),
            ),
            completedAt,
        )
    }

    private companion object {
        const val H = 3_600_000L
        val OPERATION: UUID = UUID.fromString("00000000-0000-0000-0000-000000000001")
        val CHECKPOINT_ONE: UUID = UUID.fromString("00000000-0000-0000-0000-000000000002")
        val SECOND_ATTEMPT: UUID = UUID.fromString("00000000-0000-0000-0000-000000000003")
        val CHECKPOINT_TWO: UUID = UUID.fromString("00000000-0000-0000-0000-000000000004")
        val THIRD_ATTEMPT: UUID = UUID.fromString("00000000-0000-0000-0000-000000000005")
        val CHECKPOINT_THREE: UUID = UUID.fromString("00000000-0000-0000-0000-000000000006")
        val RECOVERY_REQUEST: UUID = UUID.fromString("00000000-0000-0000-0000-000000000007")
        val FAILURE = ApplyFailure("native-step-failed")
    }
}

private enum class RecoveryBoundary { CHECKPOINT, SETTLEMENT, FAULTED }

private fun advance(
    state: RotationRuntimeState,
    event: RotationEvent,
    clock: ClockSample = at(12),
): RotationTransition = RotationRuntimeMachine.transition(state, event, clock)

private fun at(hour: Int): ClockSample =
    ClockSample(TimeUnit.HOURS.toMillis(hour.toLong()), TimeUnit.HOURS.toNanos(hour.toLong()))

internal fun completeRotationCommit(
    state: RotationRuntimeState,
    operationId: UUID,
    clock: ClockSample,
): RotationTransition {
    val accepted =
        RotationRuntimeMachine.transition(
            state,
            RotationEvent.CommitResolved(operationId, RotationCommitOutcome.Accepted),
            clock,
        )
    val settling = assertIs<RotationRuntimeState.Applying>(accepted.state)
    val operation = assertIs<RotationSettlement>(settling.operation)
    assertEquals(operationId, operation.operationId)
    assertSame(operation, assertIs<RotationCommand.PublishAccepted>(accepted.commands.single()).operation)
    return RotationRuntimeMachine.transition(
        settling,
        RotationEvent.CommitResolved(operationId, RotationCommitOutcome.Settled),
        clock,
    )
}

/** Existing traces require an accepted snapshot; cold states cannot silently provide a default. */
internal fun RotationRuntimeState.requireSnapshot(): RotationSnapshot =
    assertIs<RotationRuntimeState.Loaded>(this).snapshot
