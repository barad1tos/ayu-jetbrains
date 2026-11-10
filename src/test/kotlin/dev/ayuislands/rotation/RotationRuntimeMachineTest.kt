package dev.ayuislands.rotation

import dev.ayuislands.accent.AyuVariant
import dev.ayuislands.studio.StudioFixtures
import dev.ayuislands.studio.model.AppearanceSnapshot
import dev.ayuislands.studio.model.FactoryRecipe
import dev.ayuislands.studio.model.RawValue
import dev.ayuislands.studio.model.StudioList
import dev.ayuislands.studio.model.StudioTarget
import dev.ayuislands.studio.profiles.StudioFactoryProfiles
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class RotationRuntimeMachineTest {
    @Test
    fun `only the matching durable preparation acknowledgement starts effects once`() {
        val eligible = rotationInputs()
        val before = RotationRuntimeCheckpoint(eligible.owner, 3, 12, schedule = RotationSchedule(11 * H, 12 * H, H, 7))
        val candidate =
            assertIs<RotationStepPlan.Ready>(
                RotationStepPlanner.planStep(before, eligible, OPERATION, null),
            ).candidate
        val initial =
            RotationRuntimeState.Applying(
                RotationSnapshot(before, eligible),
                RotationStepPreparation(OPERATION, candidate),
            )
        val stale =
            RotationRuntimeMachine.transition(
                initial,
                RotationEvent.Prepared(NEXT_OPERATION),
                clock(12 * H),
            )
        assertSame(initial, stale.state)
        assertTrue(stale.commands.isEmpty())

        val prepared =
            RotationRuntimeMachine.transition(
                initial,
                RotationEvent.Prepared(OPERATION),
                clock(12 * H),
            )

        val applying = assertIs<RotationRuntimeState.Applying>(prepared.state)
        val effects = assertIs<RotationStepEffects>(applying.operation)
        val command = assertIs<RotationCommand.ApplyPreparedEffects>(prepared.commands.single())
        assertEquals(OPERATION, effects.operationId)
        assertEquals(OPERATION, command.operationId)
        assertSame(candidate, effects.candidate)
        assertSame(candidate, command.candidate)
        assertSame(before, applying.snapshot.checkpoint)
        val duplicate =
            RotationRuntimeMachine.transition(
                prepared.state,
                RotationEvent.Prepared(OPERATION),
                clock(12 * H),
            )
        assertSame(prepared.state, duplicate.state)
        assertTrue(duplicate.commands.isEmpty())
    }

    @Test
    fun `settled effects commit the same operation and acceptance publishes and arms exactly once`() {
        val eligible = rotationInputs()
        val before = RotationRuntimeCheckpoint(eligible.owner, 3, 12, schedule = RotationSchedule(11 * H, 12 * H, H, 7))
        val candidate =
            assertIs<RotationStepPlan.Ready>(
                RotationStepPlanner.planStep(before, eligible, OPERATION, null),
            ).candidate
        val initial =
            RotationRuntimeState.Applying(
                RotationSnapshot(before, eligible),
                RotationStepPreparation(OPERATION, candidate),
            )
        val prepared =
            RotationRuntimeMachine.transition(
                initial,
                RotationEvent.Prepared(OPERATION),
                clock(12 * H),
            )
        val completedAt = clock(12 * H + TimeUnit.MINUTES.toMillis(7))
        val settled =
            RotationRuntimeMachine.transition(
                prepared.state,
                RotationEvent.EffectsSettled(OPERATION, completedAt),
                clock(12 * H + TimeUnit.MINUTES.toMillis(9)),
            )

        val committing = assertIs<RotationRuntimeState.Applying>(settled.state)
        val pending = assertIs<RotationStepCommit>(committing.operation)
        val write = assertIs<RotationCommand.PersistCheckpoint>(settled.commands.single())
        assertEquals(OPERATION, pending.operationId)
        assertEquals(OPERATION, write.operationId)
        assertSame(before, committing.snapshot.checkpoint)
        assertSame(write.checkpoint, pending.checkpoint)
        assertEquals(completedAt.epochMillis, write.checkpoint.lastSuccessfulStepEpochMillis)
        assertEquals(completedAt.epochMillis + H, requireNotNull(write.checkpoint.schedule).deadlineEpochMillis)
        for (event in listOf(
            RotationEvent.EffectsSettled(OPERATION, clock(12 * H + TimeUnit.MINUTES.toMillis(10))),
            RotationEvent.CommitResolved(NEXT_OPERATION, RotationCommitOutcome.Accepted),
        )) {
            val stale = RotationRuntimeMachine.transition(settled.state, event, clock(12 * H))
            assertSame(settled.state, stale.state)
            assertTrue(stale.commands.isEmpty())
        }
        val acknowledgedAt = ClockSample(100 * H, completedAt.monotonicNanos + TimeUnit.MINUTES.toNanos(5))

        val accepted =
            RotationRuntimeMachine.transition(
                settled.state,
                RotationEvent.CommitResolved(OPERATION, RotationCommitOutcome.Accepted),
                acknowledgedAt,
            )

        val publishing = assertIs<RotationRuntimeState.Applying>(accepted.state)
        val publication = assertIs<RotationStepSettlement>(publishing.operation)
        assertSame(candidate, publication.commit.candidate)
        val published = assertIs<RotationCommand.PublishAccepted>(accepted.commands.single())
        val completed =
            RotationRuntimeMachine.transition(
                publishing,
                RotationEvent.CommitResolved(OPERATION, RotationCommitOutcome.Settled),
                acknowledgedAt,
            )
        val waiting = assertIs<RotationRuntimeState.Waiting>(completed.state)
        val scheduled = assertIs<RotationCommand.ArmSchedule>(completed.commands.single())
        assertEquals(OPERATION, published.operationId)
        assertSame(write.checkpoint, published.checkpoint)
        assertSame(write.checkpoint, waiting.snapshot.checkpoint)
        assertEquals(13L, waiting.snapshot.checkpoint.successfulStepSerial)
        assertEquals(4, waiting.snapshot.checkpoint.sequenceCursorRaw)
        assertEquals(H - TimeUnit.MINUTES.toMillis(5), waiting.ticket.delayMillis)
        assertEquals(waiting.ticket, scheduled.ticket)
        for (event in listOf(
            RotationEvent.Prepared(OPERATION),
            RotationEvent.EffectsSettled(OPERATION, completedAt),
            RotationEvent.CommitResolved(OPERATION, RotationCommitOutcome.Accepted),
        )) {
            val duplicate = RotationRuntimeMachine.transition(completed.state, event, acknowledgedAt)
            assertSame(completed.state, duplicate.state)
            assertTrue(duplicate.commands.isEmpty())
        }
        assertEquals(12L, before.successfulStepSerial)
        assertEquals(3, before.sequenceCursorRaw)
    }

    @Test
    fun `a retry keeps frozen color identity separate from every successful attempt acknowledgement`() {
        val eligible = rotationInputs(RotationIntent(true, "RANDOM", 1))
        val baseline =
            RotationRuntimeCheckpoint(
                eligible.owner,
                3,
                12,
                schedule = RotationSchedule(11 * H, 12 * H, H, 7),
            )
        val candidate =
            assertIs<RotationStepPlan.Ready>(
                RotationStepPlanner.planStep(baseline, eligible, OPERATION, "#aB01Ef"),
            ).candidate
        val before = baseline.copy(retryCandidate = candidate)
        val initial =
            RotationRuntimeState.Applying(
                RotationSnapshot(before, eligible),
                RotationStepPreparation(NEXT_OPERATION, candidate),
            )
        val prepared =
            RotationRuntimeMachine.transition(
                initial,
                RotationEvent.Prepared(NEXT_OPERATION),
                clock(12 * H),
            )
        val effects = assertIs<RotationCommand.ApplyPreparedEffects>(prepared.commands.single())
        assertEquals(NEXT_OPERATION, effects.operationId)
        assertSame(candidate, effects.candidate)
        val oldEffects =
            RotationRuntimeMachine.transition(
                prepared.state,
                RotationEvent.EffectsSettled(OPERATION, clock(12 * H)),
                clock(12 * H),
            )
        assertSame(prepared.state, oldEffects.state)
        assertTrue(oldEffects.commands.isEmpty())

        val settled =
            RotationRuntimeMachine.transition(
                prepared.state,
                RotationEvent.EffectsSettled(NEXT_OPERATION, clock(12 * H)),
                clock(12 * H),
            )

        val write = assertIs<RotationCommand.PersistCheckpoint>(settled.commands.single())
        assertEquals(NEXT_OPERATION, write.operationId)
        assertEquals("#aB01Ef", requireNotNull(write.checkpoint.output).hex)
        val oldCommit =
            RotationRuntimeMachine.transition(
                settled.state,
                RotationEvent.CommitResolved(OPERATION, RotationCommitOutcome.Accepted),
                clock(12 * H),
            )
        assertSame(settled.state, oldCommit.state)
        assertTrue(oldCommit.commands.isEmpty())
        val accepted =
            RotationRuntimeMachine.transition(
                settled.state,
                RotationEvent.CommitResolved(NEXT_OPERATION, RotationCommitOutcome.Accepted),
                clock(12 * H),
            )
        val publishing = assertIs<RotationRuntimeState.Applying>(accepted.state)
        val publication = assertIs<RotationStepSettlement>(publishing.operation)
        assertSame(candidate, publication.commit.candidate)
        val published = assertIs<RotationCommand.PublishAccepted>(accepted.commands.single())
        assertEquals(NEXT_OPERATION, published.operationId)
        val completed =
            RotationRuntimeMachine.transition(
                publishing,
                RotationEvent.CommitResolved(NEXT_OPERATION, RotationCommitOutcome.Settled),
                clock(12 * H),
            )
        val waiting = assertIs<RotationRuntimeState.Waiting>(completed.state)
        assertEquals(RotationCommand.ArmSchedule(waiting.ticket), completed.commands.single())
        assertEquals(3, waiting.snapshot.checkpoint.sequenceCursorRaw)
        assertEquals(13L, waiting.snapshot.checkpoint.successfulStepSerial)
        assertNull(waiting.snapshot.checkpoint.retryCandidate)
        assertSame(candidate, before.retryCandidate)
        assertEquals(OPERATION, candidate.operationId)
    }

    @Test
    fun `Follow entering a Pro pause preserves its obligation after Pro returns`() {
        val blocked = inputs(availability(NO_PRO))
        val checkpoint = checkpoint(blocked.owner).copy(resume = RotationResume.KeepDeadline)
        val initial = RotationRuntimeState.Paused(RotationSnapshot(checkpoint, blocked))
        val followed = blocked.copy(availability = availability(NO_PRO or FOLLOW))

        val pending = observe(initial, followed, 1, 12 * H)

        assertSame(checkpoint, assertIs<RotationRuntimeState.Applying>(pending.state).snapshot.checkpoint)
        val proposal = assertIs<RotationCommand.PersistCheckpoint>(pending.commands.single())
        assertEquals(RotationResume.FullInterval(FullIntervalReason.FOLLOW_SYSTEM), proposal.checkpoint.resume)
        val accepted = acknowledge(pending, 12 * H)
        val onlyFollow = followed.copy(availability = availability(FOLLOW))
        val proReturned = observe(accepted.state, onlyFollow, 2, 12 * H + H / 4)
        assertIs<RotationRuntimeState.Paused>(proReturned.state)
        assertTrue(proReturned.commands.isEmpty())
        val eligible = onlyFollow.copy(availability = availability(0))
        val resumed = observe(proReturned.state, eligible, 3, 12 * H + H / 2, NEXT_OPERATION)
        assertScheduleProposal(resumed, proposal.checkpoint, 12 * H + H / 2, 13 * H + H / 2)
    }

    @Test
    fun `Follow under recovery cancels its ticket and defers checkpoint writes until authority returns`() {
        val eligible = inputs(availability(0))
        val checkpoint =
            checkpoint(eligible.owner).copy(
                schedule = RotationSchedule(12 * H, 13 * H, H, 7),
                resume = RotationResume.KeepDeadline,
            )
        val ticket = RotationTicket(eligible.owner, 7, H, clock(12 * H).monotonicNanos)
        val initial = RotationRuntimeState.Waiting(RotationSnapshot(checkpoint, eligible), ticket)
        val recovering = eligible.copy(availability = availability(FOLLOW or RECOVERY))

        val blocked = observe(initial, recovering, 1, 12 * H)

        assertSame(checkpoint, assertIs<RotationRuntimeState.Paused>(blocked.state).snapshot.checkpoint)
        assertEquals(ticket, assertIs<RotationCommand.CancelSchedule>(blocked.commands.single()).ticket)
        val followed = recovering.copy(availability = availability(FOLLOW))
        val writable = observe(blocked.state, followed, 2, 12 * H + H / 4, NEXT_OPERATION)
        val proposal = assertIs<RotationCommand.PersistCheckpoint>(writable.commands.single())
        assertEquals(RotationResume.FullInterval(FullIntervalReason.FOLLOW_SYSTEM), proposal.checkpoint.resume)
        assertSame(checkpoint, assertIs<RotationRuntimeState.Applying>(writable.state).snapshot.checkpoint)
    }

    @Test
    fun `checkpoint acknowledgement under recovery cannot start a deferred write`() {
        val followed = inputs(availability(FOLLOW))
        val initial = RotationRuntimeState.Paused(RotationSnapshot(checkpoint(followed.owner), followed))
        val eligible = followed.copy(availability = availability(0))
        val pending = observe(initial, eligible, 1, 12 * H)
        val firstProposal = assertIs<RotationCommand.PersistCheckpoint>(pending.commands.single()).checkpoint
        val recovering = followed.copy(availability = availability(FOLLOW or RECOVERY))
        val deferred = observe(pending.state, recovering, 2, 12 * H + H / 4, NEXT_OPERATION)

        val acknowledged = acknowledge(deferred, 12 * H + H / 2)

        assertEquals(firstProposal, assertIs<RotationRuntimeState.Paused>(acknowledged.state).snapshot.checkpoint)
        assertTrue(acknowledged.commands.isEmpty())
        val writable = observe(acknowledged.state, followed, 3, 12 * H + 3 * H / 4, NEXT_OPERATION)
        val proposal = assertIs<RotationCommand.PersistCheckpoint>(writable.commands.single()).checkpoint
        assertEquals(RotationResume.FullInterval(FullIntervalReason.FOLLOW_SYSTEM), proposal.resume)
        assertEquals(firstProposal.schedule, proposal.schedule)
    }

    @Test
    fun `Pro return retains the actual appointment instead of restarting the current interval`() {
        val blocked = inputs(availability(NO_PRO))
        val checkpoint =
            checkpoint(blocked.owner).copy(
                schedule = RotationSchedule(7 * H, 13 * H, 6 * H, 7),
                resume = RotationResume.KeepDeadline,
            )
        val initial = RotationRuntimeState.Paused(RotationSnapshot(checkpoint, blocked))
        val eligible = blocked.copy(availability = availability(0))

        val resumed = observe(initial, eligible, 1, 12 * H + H / 6)

        val waiting = assertIs<RotationRuntimeState.Waiting>(resumed.state)
        assertSame(checkpoint, waiting.snapshot.checkpoint)
        assertEquals(5 * H / 6, assertIs<RotationCommand.ArmSchedule>(resumed.commands.single()).ticket.delayMillis)
    }

    @Test
    fun `unknown entitlement retains the previous definitive observation`() {
        val eligible = inputs(availability(0))
        val checkpoint =
            checkpoint(eligible.owner).copy(
                schedule = RotationSchedule(12 * H, 13 * H, H, 7),
                resume = RotationResume.KeepDeadline,
            )
        val ticket = RotationTicket(eligible.owner, 7, H, clock(12 * H).monotonicNanos)
        val initial = RotationRuntimeState.Waiting(RotationSnapshot(checkpoint, eligible), ticket)
        val unknown = eligible.copy(availability = eligible.availability.copy(lastDefinitivePro = null))

        val observed = observe(initial, unknown, 1, 12 * H)

        val waiting = assertIs<RotationRuntimeState.Waiting>(observed.state)
        assertEquals(true, waiting.snapshot.inputs.availability.lastDefinitivePro)
        assertSame(ticket, waiting.ticket)
        assertTrue(observed.commands.isEmpty())
        val denied = eligible.copy(availability = eligible.availability.copy(lastDefinitivePro = false))
        val paused = assertIs<RotationRuntimeState.Paused>(observe(waiting, denied, 2, 12 * H).state)
        val stillPaused = observe(paused, unknown, 3, 12 * H)
        assertEquals(
            false,
            assertIs<RotationRuntimeState.Paused>(stillPaused.state)
                .snapshot.inputs.availability.lastDefinitivePro,
        )
        assertTrue(stillPaused.commands.isEmpty())
    }

    @Test
    fun `Follow entering while waiting cancels the ticket and acknowledges its stronger obligation`() {
        val eligible = inputs(availability(0))
        val checkpoint =
            checkpoint(eligible.owner).copy(
                schedule = RotationSchedule(12 * H, 13 * H, H, 7),
                resume = RotationResume.KeepDeadline,
            )
        val ticket = RotationTicket(eligible.owner, 7, H, clock(12 * H).monotonicNanos)
        val initial = RotationRuntimeState.Waiting(RotationSnapshot(checkpoint, eligible), ticket)
        val followed = eligible.copy(availability = availability(FOLLOW))

        val observed = observe(initial, followed, 1, 12 * H)

        assertSame(checkpoint, assertIs<RotationRuntimeState.Applying>(observed.state).snapshot.checkpoint)
        assertEquals(ticket, assertIs<RotationCommand.CancelSchedule>(observed.commands.first()).ticket)
        val write = assertIs<RotationCommand.PersistCheckpoint>(observed.commands.last())
        assertEquals(RotationResume.FullInterval(FullIntervalReason.FOLLOW_SYSTEM), write.checkpoint.resume)
        assertSame(checkpoint.schedule, write.checkpoint.schedule)
        val accepted = acknowledge(observed, 12 * H)
        val paused = assertIs<RotationRuntimeState.Paused>(accepted.state)
        assertEquals(write.checkpoint, paused.snapshot.checkpoint)
        assertTrue(accepted.commands.isEmpty())
    }

    @Test
    fun `Follow cycle during pending acknowledgement cannot arm the obsolete appointment`() {
        val followed = inputs(availability(FOLLOW))
        val initial = RotationRuntimeState.Paused(RotationSnapshot(checkpoint(followed.owner), followed))
        val eligible = followed.copy(availability = availability(0))
        val pending = observe(initial, eligible, 1, 12 * H)
        val onId = UUID.fromString("d8f5033b-8b6f-4b31-bac2-858df3dfca7a")
        val offId = UUID.fromString("97da5fbc-de10-4720-bcca-94002c1f611e")
        val on =
            RotationRuntimeMachine.transition(
                pending.state,
                RotationEvent.AvailabilityObserved(2, followed.availability, onId),
                clock(12 * H + H / 4),
            )
        val off =
            RotationRuntimeMachine.transition(
                on.state,
                RotationEvent.AvailabilityObserved(3, eligible.availability, offId),
                clock(12 * H + H / 2),
            )

        val accepted =
            completeRotationCommit(off.state, OPERATION, clock(12 * H + 3 * H / 4))

        assertIs<RotationRuntimeState.Applying>(accepted.state)
        val replacement = assertIs<RotationCommand.PersistCheckpoint>(accepted.commands.single())
        assertEquals(offId, replacement.operationId)
        assertEquals(13 * H + H / 2, replacement.checkpoint.schedule?.deadlineEpochMillis)
        val settled =
            completeRotationCommit(accepted.state, offId, clock(12 * H + 3 * H / 4))
        assertEquals(3 * H / 4, assertIs<RotationCommand.ArmSchedule>(settled.commands.single()).ticket.delayMillis)
    }

    @Test
    fun `availability retains every simultaneous reason without changing accepted preferences`() {
        for (mask in 0 until 32) {
            val availability = availability(mask)
            val expected = RotationPauseReason.entries.filterIndexed { index, _ -> mask and (1 shl index) != 0 }
            assertEquals(expected, availability.reasons, "reason mask $mask")
            val inputs = inputs(availability).copy(appearance = disabledAppearance())
            val checkpoint = checkpoint(inputs.owner)
            val before = RotationRuntimeState.Off(RotationSnapshot(checkpoint, inputs))

            val result = RotationRuntimeMachine.transition(before, RotationEvent.ViewOnly, clock(12 * H))

            assertSame(before, result.state)
            assertTrue(result.commands.isEmpty())
            assertSame(checkpoint, assertIs<RotationRuntimeState.Off>(result.state).snapshot.checkpoint)
            assertSame(inputs.appearance, before.snapshot.inputs.appearance)
        }
    }

    @Test
    fun `follow ending while Pro is unavailable starts the full interval only when Pro returns`() {
        val inputs = inputs(availability(NO_PRO or FOLLOW))
        val checkpoint = checkpoint(inputs.owner)
        val initial = RotationRuntimeState.Paused(RotationSnapshot(checkpoint, inputs))
        val withoutFollow = inputs.copy(availability = availability(NO_PRO))

        val first = observe(initial, withoutFollow, 1, 12 * H)

        val paused = assertIs<RotationRuntimeState.Paused>(first.state)
        assertEquals(listOf(RotationPauseReason.NO_PRO), paused.reasons)
        assertSame(checkpoint, paused.snapshot.checkpoint)
        assertTrue(first.commands.isEmpty())
        val eligible = withoutFollow.copy(availability = availability(0))

        val resumed = observe(paused, eligible, 2, 12 * H + H / 2)

        assertScheduleProposal(resumed, checkpoint, 12 * H + H / 2, 13 * H + H / 2)
        val accepted = acknowledge(resumed, 12 * H + H / 2)
        val waiting = assertIs<RotationRuntimeState.Waiting>(accepted.state)
        assertEquals(
            13 * H + H / 2,
            waiting.snapshot.checkpoint.schedule
                ?.deadlineEpochMillis,
        )
        assertEquals(RotationResume.KeepDeadline, waiting.snapshot.checkpoint.resume)
        assertEquals(H, assertIs<RotationCommand.ArmSchedule>(accepted.commands.single()).ticket.delayMillis)
        assertEquals(checkpoint.sequenceCursorRaw, waiting.snapshot.checkpoint.sequenceCursorRaw)
        assertEquals(checkpoint.output, waiting.snapshot.checkpoint.output)
        assertSame(eligible.appearance, waiting.snapshot.inputs.appearance)
    }

    @Test
    fun `Pro returning first cannot consume the Follow resume obligation`() {
        val inputs = inputs(availability(NO_PRO or FOLLOW))
        val checkpoint = checkpoint(inputs.owner)
        val initial = RotationRuntimeState.Paused(RotationSnapshot(checkpoint, inputs))
        val onlyFollow = inputs.copy(availability = availability(FOLLOW))

        val first = observe(initial, onlyFollow, 1, 12 * H)

        val paused = assertIs<RotationRuntimeState.Paused>(first.state)
        assertEquals(listOf(RotationPauseReason.FOLLOW_SYSTEM), paused.reasons)
        assertSame(checkpoint, paused.snapshot.checkpoint)
        assertTrue(first.commands.isEmpty())
        val eligible = inputs.copy(availability = availability(0))
        val resumed = observe(paused, eligible, 2, 13 * H)
        assertScheduleProposal(resumed, checkpoint, 13 * H, 14 * H)
    }

    @Test
    fun `clearing any proper subset of pause reasons preserves the full interval obligation`() {
        for (mask in 1 until 32) {
            val inputs = inputs(availability(mask))
            val checkpoint = checkpoint(inputs.owner)
            val initial = RotationRuntimeState.Paused(RotationSnapshot(checkpoint, inputs))
            for (bit in 0 until 5) {
                val remaining = mask and (1 shl bit).inv()
                if (remaining == 0 || remaining == mask) continue
                val current = inputs.copy(availability = availability(remaining))

                val result = observe(initial, current, 1, 12 * H)

                val paused = assertIs<RotationRuntimeState.Paused>(result.state, "mask $mask, remaining $remaining")
                assertEquals(current.availability.reasons, paused.reasons)
                assertSame(checkpoint, paused.snapshot.checkpoint)
                assertTrue(result.commands.isEmpty())
            }
        }
    }

    @Test
    fun `a duplicate clear observation cannot reset an acknowledged appointment`() {
        val blocked = inputs(availability(FOLLOW))
        val initial = RotationRuntimeState.Paused(RotationSnapshot(checkpoint(blocked.owner), blocked))
        val eligible = blocked.copy(availability = availability(0))
        val scheduled = observe(initial, eligible, 1, 12 * H)
        val accepted = acknowledge(scheduled, 12 * H)

        val duplicate = observe(accepted.state, eligible, 1, 12 * H + H / 2)
        val repeated = observe(accepted.state, eligible, 2, 12 * H + H / 2)

        assertSame(accepted.state, duplicate.state)
        assertTrue(duplicate.commands.isEmpty())
        assertEquals(
            assertIs<RotationRuntimeState.Waiting>(accepted.state).snapshot.checkpoint,
            assertIs<RotationRuntimeState.Waiting>(repeated.state).snapshot.checkpoint,
        )
        assertTrue(repeated.commands.isEmpty())
    }

    @Test
    fun `checkpoint rejection never arms the new appointment or rewrites the accepted checkpoint`() {
        val blocked = inputs(availability(FOLLOW))
        val checkpoint = checkpoint(blocked.owner)
        val initial = RotationRuntimeState.Paused(RotationSnapshot(checkpoint, blocked))
        val eligible = blocked.copy(availability = availability(0))
        val scheduled = observe(initial, eligible, 1, 12 * H)

        val failed =
            RotationRuntimeMachine.transition(
                scheduled.state,
                RotationEvent.CommitResolved(OPERATION, RotationCommitOutcome.RejectedRestored),
                clock(12 * H),
            )

        val faulted = assertIs<RotationRuntimeState.Faulted.Loaded>(failed.state)
        assertSame(checkpoint, faulted.snapshot.checkpoint)
        assertIs<RuntimeFault.DurableRecovery>(faulted.fault)
        assertIs<RotationCommand.ReportFault>(failed.commands.single())
    }

    private fun assertScheduleProposal(
        transition: RotationTransition,
        before: RotationRuntimeCheckpoint,
        scheduledAt: Long,
        deadline: Long,
    ) {
        val applying = assertIs<RotationRuntimeState.Applying>(transition.state)
        assertSame(before, applying.snapshot.checkpoint)
        val command = assertIs<RotationCommand.PersistCheckpoint>(transition.commands.single())
        val proposed = command.checkpoint
        assertEquals(scheduledAt, proposed.schedule?.scheduledAtEpochMillis)
        assertEquals(deadline, proposed.schedule?.deadlineEpochMillis)
        assertEquals(H, proposed.schedule?.intervalMillisAtScheduling)
        assertEquals(8L, proposed.schedule?.scheduleSerial)
        assertEquals(RotationResume.KeepDeadline, proposed.resume)
        assertEquals(before.sequenceCursorRaw, proposed.sequenceCursorRaw)
        assertEquals(before.output, proposed.output)
    }

    private fun acknowledge(
        transition: RotationTransition,
        now: Long,
    ): RotationTransition = completeRotationCommit(transition.state, OPERATION, clock(now))

    private fun observe(
        state: RotationRuntimeState,
        inputs: RuntimeInputs,
        generation: Long,
        now: Long,
        operationId: UUID = OPERATION,
    ): RotationTransition =
        RotationRuntimeMachine.transition(
            state,
            RotationEvent.AvailabilityObserved(generation, inputs.availability, operationId),
            clock(now),
        )

    private fun inputs(availability: RuntimeAvailability): RuntimeInputs =
        rotationInputs(RotationIntent(true, "future-mode", 1), availability)

    private fun disabledAppearance(): AppearanceSnapshot =
        rotationInputs(RotationIntent(false, "future-mode", 1)).appearance

    private fun checkpoint(owner: RuntimeOwner): RotationRuntimeCheckpoint =
        RotationRuntimeCheckpoint(
            owner = owner,
            sequenceCursorRaw = Int.MIN_VALUE,
            successfulStepSerial = 12,
            lastSuccessfulStepEpochMillis = 5 * H,
            output = RotationOutput(owner, AyuVariant.MIRAGE, "#5CCFE6"),
            schedule = RotationSchedule(6 * H, 7 * H, H, 7),
            resume = RotationResume.FullInterval(FullIntervalReason.FOLLOW_SYSTEM),
        )

    private fun availability(mask: Int): RuntimeAvailability =
        RuntimeAvailability(
            lastDefinitivePro = if (mask and NO_PRO == 0) true else null,
            effectiveFollowSystem = mask and FOLLOW != 0,
            variant = AyuVariant.MIRAGE.takeIf { mask and EXTERNAL == 0 },
            targetValid = mask and TARGET == 0,
            recoveryPending = mask and RECOVERY != 0,
        )

    private fun clock(now: Long): ClockSample = ClockSample(now, now * 1_000_000)

    private companion object {
        const val H = 3_600_000L
        const val FOLLOW = 1
        const val NO_PRO = 2
        const val EXTERNAL = 4
        const val TARGET = 8
        const val RECOVERY = 16
        val OPERATION: UUID = UUID.fromString("3c9ab3ae-c8b5-4fb0-b1e1-c38300716035")
        val NEXT_OPERATION: UUID = UUID.fromString("9852c1c4-086c-46d4-b1b8-cfe9a610263e")
    }
}

internal fun rotationInputs(
    intent: RotationIntent = RotationIntent(true, "PRESET", 1),
    availability: RuntimeAvailability =
        RuntimeAvailability(
            lastDefinitivePro = true,
            effectiveFollowSystem = false,
            variant = AyuVariant.MIRAGE,
            targetValid = true,
            recoveryPending = false,
        ),
): RuntimeInputs {
    val appearance =
        StudioFactoryProfiles
            .create(StudioFixtures.fields())
            .single {
                it.id == FactoryRecipe.AMBIENT.id
            }.appearance
    val values =
        mapOf(
            "accentRotationEnabled" to RawValue.BooleanValue(intent.enabled),
            "accentRotationMode" to (intent.modeRaw?.let(RawValue::Text) ?: RawValue.Null),
            "accentRotationIntervalHours" to RawValue.Integer(intent.intervalHoursRaw),
        )
    val selected =
        appearance.copy(
            accent =
                appearance.accent.copy(
                    records =
                        StudioList(
                            appearance.accent.records.map { field ->
                                values[field.key.name]?.let { field.copy(value = it) } ?: field
                            },
                        ),
                ),
        )
    return RuntimeInputs(RuntimeOwner(StudioTarget.Global, 3), selected, availability)
}

class RotationStartupTest {
    @Test
    fun `startup records one load identity and rejects duplicate and stale load callbacks`() {
        val initial = RotationRuntimeState.Loading()
        val begun = dispatch(initial, RotationEvent.Initialize(LOAD))
        val loading = assertIs<RotationRuntimeState.Loading>(begun.state)
        assertEquals(LOAD, loading.operationId)
        assertTrue(begun.commands.isEmpty())
        val duplicate = dispatch(loading, RotationEvent.Initialize(NEXT))
        assertSame(loading, duplicate.state)
        assertTrue(duplicate.commands.isEmpty())
        val stale = dispatch(loading, RotationEvent.Loaded(NEXT, RotationLoadOutcome.Ready(createSnapshot())))
        assertSame(loading, stale.state)
        assertTrue(stale.commands.isEmpty())
        val loaded = dispatch(loading, RotationEvent.Loaded(LOAD, RotationLoadOutcome.Ready(createSnapshot())))
        val waiting = assertIs<RotationRuntimeState.Waiting>(loaded.state)
        for (event in listOf(
            RotationEvent.Initialize(NEXT),
            RotationEvent.Loaded(LOAD, RotationLoadOutcome.Ready(createSnapshot())),
        )) {
            val ignored = dispatch(waiting, event)
            assertSame(waiting, ignored.state)
            assertTrue(ignored.commands.isEmpty())
        }
    }

    @Test
    fun `authoritative disabled Current remains exact and clears only the previous session budget`() {
        val inputs = rotationInputs(RotationIntent(false, "future-mode", Int.MIN_VALUE))
        val before = createSnapshot(inputs).copy(consecutiveFailures = 2)
        val loaded = load(RotationLoadOutcome.Ready(before))
        val off = assertIs<RotationRuntimeState.Off>(loaded.state)
        assertSame(before.checkpoint, off.snapshot.checkpoint)
        assertSame(inputs, off.snapshot.inputs)
        assertEquals(0, off.snapshot.consecutiveFailures)
        assertTrue(loaded.commands.isEmpty())
    }

    @Test
    fun `restart preserves a valid longer appointment instead of using the newly active interval`() {
        val before = createSnapshot()
        val loaded = load(RotationLoadOutcome.Ready(before))
        val waiting = assertIs<RotationRuntimeState.Waiting>(loaded.state)
        assertSame(before.checkpoint, waiting.snapshot.checkpoint)
        assertEquals(4 * H, waiting.ticket.remainingMillis)
        assertEquals(4 * H, waiting.ticket.delayMillis)
        assertEquals(waiting.ticket, assertIs<RotationCommand.ArmSchedule>(loaded.commands.single()).ticket)
        assertEquals(1, waiting.snapshot.inputs.intent.intervalHoursRaw)
    }

    @Test
    fun `overdue authoritative startup prepares at most one step and never replays skipped appointments`() {
        val before =
            createSnapshot().let { value ->
                value.copy(checkpoint = value.checkpoint.copy(schedule = RotationSchedule(0, H, H, 7)))
            }
        val loaded = load(RotationLoadOutcome.Ready(before), hour = 20)
        val waiting = assertIs<RotationRuntimeState.Waiting>(loaded.state)
        assertEquals(0L, waiting.ticket.remainingMillis)
        val due = dispatch(waiting, RotationEvent.TimerDue(waiting.ticket, NEXT), hour = 20)
        val preparing = assertIs<RotationRuntimeState.Applying>(due.state)
        assertIs<RotationStepPreparation>(preparing.operation)
        assertIs<RotationCommand.PrepareStep>(due.commands.single())
        assertSame(before.checkpoint, preparing.snapshot.checkpoint)
        val duplicate = dispatch(preparing, RotationEvent.TimerDue(waiting.ticket, NEXT), hour = 20)
        assertSame(preparing, duplicate.state)
        assertTrue(duplicate.commands.isEmpty())
    }

    @Test
    fun `a different accepted startup owner persists one full interval and retains the shared sequence`() {
        val before = createSnapshot()
        val incoming = before.inputs.copy(owner = before.inputs.owner.copy(appearanceRevision = 8))
        val loaded = load(RotationLoadOutcome.Ready(before.copy(inputs = incoming)))
        val pending = assertIs<RotationRuntimeState.Applying>(loaded.state)
        val write = assertIs<RotationCommand.PersistCheckpoint>(loaded.commands.single())
        assertSame(before.checkpoint, pending.snapshot.checkpoint)
        assertEquals(incoming.owner, write.checkpoint.owner)
        assertEquals(before.checkpoint.sequenceCursorRaw, write.checkpoint.sequenceCursorRaw)
        assertEquals(before.checkpoint.successfulStepSerial, write.checkpoint.successfulStepSerial)
        assertNull(write.checkpoint.output)
        assertEquals(2 * H, write.checkpoint.schedule?.scheduledAtEpochMillis)
        assertEquals(3 * H, write.checkpoint.schedule?.deadlineEpochMillis)
        assertSame(incoming.appearance, pending.snapshot.inputs.appearance)
    }

    @Test
    fun `recognized no history is distinct from a missing v3 appointment and never advances before acceptance`() {
        val before =
            createSnapshot().let { value ->
                value.copy(
                    checkpoint =
                        value.checkpoint.copy(
                            successfulStepSerial = 0,
                            lastSuccessfulStepEpochMillis = null,
                            output = null,
                            schedule = null,
                            resume = RotationResume.NoHistory,
                        ),
                )
            }
        val loaded = load(RotationLoadOutcome.Ready(before))
        val preparing = assertIs<RotationRuntimeState.Applying>(loaded.state)
        assertIs<RotationStepPreparation>(preparing.operation)
        assertIs<RotationCommand.PrepareStep>(loaded.commands.single())
        assertSame(before.checkpoint, preparing.snapshot.checkpoint)
        assertEquals(Int.MIN_VALUE, preparing.snapshot.checkpoint.sequenceCursorRaw)
        val missing =
            load(
                RotationLoadOutcome.Ready(
                    before.copy(checkpoint = before.checkpoint.copy(resume = RotationResume.KeepDeadline)),
                ),
            )
        val repair = assertIs<RotationCommand.PersistCheckpoint>(missing.commands.single())
        assertEquals(3 * H, repair.checkpoint.schedule?.deadlineEpochMillis)
        assertEquals(before.checkpoint.successfulStepSerial, repair.checkpoint.successfulStepSerial)
    }

    @Test
    fun `Follow startup outranks no history and a full interval starts only after the final gate clears`() {
        val inputs =
            rotationInputs().let { value ->
                value.copy(
                    availability = value.availability.copy(effectiveFollowSystem = true, lastDefinitivePro = false),
                )
            }
        val before =
            createSnapshot(inputs).let { value ->
                value.copy(
                    checkpoint =
                        value.checkpoint.copy(
                            successfulStepSerial = 0,
                            lastSuccessfulStepEpochMillis = null,
                            output = null,
                            schedule = null,
                            resume = RotationResume.NoHistory,
                        ),
                )
            }
        val loaded = load(RotationLoadOutcome.Ready(before))
        val write = assertIs<RotationCommand.PersistCheckpoint>(loaded.commands.single())
        assertEquals(RotationResume.FullInterval(FullIntervalReason.FOLLOW_SYSTEM), write.checkpoint.resume)
        val accepted = dispatch(loaded.state, RotationEvent.CommitResolved(LOAD, RotationCommitOutcome.Accepted))
        val settled = dispatch(accepted.state, RotationEvent.CommitResolved(LOAD, RotationCommitOutcome.Settled))
        assertIs<RotationRuntimeState.Paused>(settled.state)
        assertTrue(settled.commands.isEmpty())
        val stillBlocked =
            dispatch(
                settled.state,
                RotationEvent.AvailabilityObserved(1, inputs.availability.copy(effectiveFollowSystem = false), NEXT),
                hour = 3,
            )
        assertIs<RotationRuntimeState.Paused>(stillBlocked.state)
        assertTrue(stillBlocked.commands.isEmpty())
        val resumed =
            dispatch(
                stillBlocked.state,
                RotationEvent.AvailabilityObserved(2, rotationInputs().availability, RETRY),
                hour = 4,
            )
        val appointment = assertIs<RotationCommand.PersistCheckpoint>(resumed.commands.single()).checkpoint
        assertEquals(4 * H, appointment.schedule?.scheduledAtEpochMillis)
        assertEquals(5 * H, appointment.schedule?.deadlineEpochMillis)
        assertEquals(before.checkpoint.sequenceCursorRaw, appointment.sequenceCursorRaw)
    }

    @Test
    fun `newer observations during load cannot be replaced by stale capability facts`() {
        val initial = dispatch(RotationRuntimeState.Loading(), RotationEvent.Initialize(LOAD)).state
        val blocked = rotationInputs().availability.copy(lastDefinitivePro = false, recoveryPending = true)
        val observed = dispatch(initial, RotationEvent.AvailabilityObserved(6, blocked, NEXT))
        assertIs<RotationRuntimeState.Loading>(observed.state)
        assertTrue(observed.commands.isEmpty())
        val unknown =
            dispatch(
                observed.state,
                RotationEvent.AvailabilityObserved(7, blocked.copy(lastDefinitivePro = null), RETRY),
            )
        assertTrue(unknown.commands.isEmpty())
        val stale = createSnapshot().copy(observationGeneration = 5)
        val loaded = dispatch(unknown.state, RotationEvent.Loaded(LOAD, RotationLoadOutcome.Ready(stale)))
        val paused = assertIs<RotationRuntimeState.Paused>(loaded.state)
        assertTrue(paused.snapshot.observationGeneration >= 6)
        assertEquals(blocked, paused.snapshot.inputs.availability)
        assertSame(stale.inputs.appearance, paused.snapshot.inputs.appearance)
        assertTrue(loaded.commands.isEmpty())
    }

    @Test
    fun `unavailable Current stays unloaded until the matching recovery load`() {
        val fault = RuntimeFault.DurableRecovery("store-integrity-unproven")
        val failed = load(RotationLoadOutcome.Unavailable(fault))
        val unloaded = assertIs<RotationRuntimeState.Faulted.Unloaded>(failed.state)
        assertSame(fault, unloaded.fault)
        assertSame(fault, assertIs<RotationCommand.ReportFault>(failed.commands.single()).fault)
        val observed = dispatch(unloaded, RotationEvent.AvailabilityObserved(1, rotationInputs().availability, NEXT))
        assertIs<RotationRuntimeState.Faulted.Unloaded>(observed.state)
        assertTrue(observed.commands.isEmpty())
        val retry = dispatch(observed.state, RotationEvent.RetryRecovery(RETRY))
        assertEquals(RETRY, assertIs<RotationRuntimeState.Loading>(retry.state).operationId)
        val stale = dispatch(retry.state, RotationEvent.Loaded(LOAD, RotationLoadOutcome.Ready(createSnapshot())))
        assertSame(retry.state, stale.state)
        assertTrue(stale.commands.isEmpty())
        val recovered = dispatch(retry.state, RotationEvent.Loaded(RETRY, RotationLoadOutcome.Ready(createSnapshot())))
        assertIs<RotationRuntimeState.Waiting>(recovered.state)
    }

    @Test
    fun `loaded durable recovery remains visible despite a fresh session and eligible observations`() {
        val fault = RuntimeFault.DurableRecovery("journal-needs-recovery")
        val before =
            createSnapshot().let { value ->
                value.copy(checkpoint = value.checkpoint.copy(recoveryFault = fault), consecutiveFailures = 2)
            }
        val loaded = load(RotationLoadOutcome.Ready(before))
        val failed = assertIs<RotationRuntimeState.Faulted.Loaded>(loaded.state)
        assertSame(fault, failed.fault)
        assertSame(before.checkpoint, failed.snapshot.checkpoint)
        val observed = dispatch(failed, RotationEvent.AvailabilityObserved(1, before.inputs.availability, NEXT))
        assertIs<RotationRuntimeState.Faulted.Loaded>(observed.state)
        assertTrue(observed.commands.isEmpty())
    }

    @Test
    fun `recovered prepared work resolves the same journal before any timer or new candidate`() {
        val before = createSnapshot()
        val candidate =
            assertIs<RotationStepPlan.Ready>(
                RotationStepPlanner.planStep(before.checkpoint, before.inputs, NEXT, null),
            ).candidate
        val pending = RotationStepPreparation(NEXT, candidate)
        val resolution =
            RotationOutcomeResolution(
                pending,
                dev.ayuislands.studio.model
                    .ApplyFailure("startup-recovery"),
                RETRY,
            )
        val loaded = load(RotationLoadOutcome.Recovering(before, resolution))
        val recovering = assertIs<RotationRuntimeState.Applying>(loaded.state)
        assertSame(resolution, recovering.operation)
        assertSame(resolution, assertIs<RotationCommand.ResolveStoreOutcome>(loaded.commands.single()).operation)
        assertSame(before.checkpoint, recovering.snapshot.checkpoint)
    }

    @Test
    fun `a proven accepted crash restores its exact checkpoint and only cleanup precedes the remaining timer`() {
        val before = createSnapshot()
        val commit = RotationScheduleCommit(NEXT, before.checkpoint, sampleClock(2))
        val settlement = RotationScheduleSettlement(commit, RotationSettlementProgress.CLEANUP_PENDING, RETRY)
        val loaded = load(RotationLoadOutcome.Settling(before, settlement))
        val applying = assertIs<RotationRuntimeState.Applying>(loaded.state)
        assertSame(before.checkpoint, applying.snapshot.checkpoint)
        val publish = assertIs<RotationCommand.PublishAccepted>(loaded.commands.single())
        assertEquals(RotationSettlementProgress.CLEANUP_PENDING, publish.progress)
        assertEquals(NEXT, publish.operationId)
        assertEquals(RETRY, publish.operation.completionId)
        val settled =
            dispatch(
                applying,
                RotationEvent.CommitResolved(NEXT, RotationCommitOutcome.Settled, RETRY),
                hour = 3,
            )
        val waiting = assertIs<RotationRuntimeState.Waiting>(settled.state)
        assertSame(before.checkpoint, waiting.snapshot.checkpoint)
        assertEquals(3 * H, waiting.ticket.remainingMillis)
        assertEquals(waiting.ticket, assertIs<RotationCommand.ArmSchedule>(settled.commands.single()).ticket)
    }

    @Test
    fun `disposal cancels one ticket retains unresolved ownership and never reacts to late callbacks`() {
        val before = createSnapshot()
        val waiting = assertIs<RotationRuntimeState.Waiting>(load(RotationLoadOutcome.Ready(before)).state)
        val candidate =
            assertIs<RotationStepPlan.Ready>(
                RotationStepPlanner.planStep(before.checkpoint, before.inputs, NEXT, null),
            ).candidate
        val pending = RotationStepPreparation(NEXT, candidate)
        val unresolved =
            RotationOutcomeResolution(
                pending,
                dev.ayuislands.studio.model
                    .ApplyFailure("restore-pending"),
            )
        val disabled = before.copy(inputs = rotationInputs(RotationIntent(false, "PRESET", 1)))
        val paused =
            before.copy(
                inputs = before.inputs.copy(availability = before.inputs.availability.copy(lastDefinitivePro = false)),
            )
        val live =
            listOf(
                RotationRuntimeState.Loading(),
                RotationRuntimeState.Off(disabled),
                RotationRuntimeState.Paused(paused),
                waiting,
                RotationRuntimeState.Applying(before, pending),
                RotationRuntimeState.Faulted.Loaded(
                    before,
                    RuntimeFault.DurableRecovery("restore-pending"),
                    unresolved,
                ),
            )
        for (state in live) {
            val result = dispatch(state, RotationEvent.Dispose)
            val disposed = assertIs<RotationRuntimeState.Disposed>(result.state)
            if (state is RotationRuntimeState.Waiting) {
                assertEquals(state.ticket, assertIs<RotationCommand.CancelSchedule>(result.commands.single()).ticket)
            } else {
                assertTrue(result.commands.isEmpty())
            }
            if (state is RotationRuntimeState.Loading) assertNull(disposed.snapshot)
            if (state is RotationRuntimeState.Applying) assertSame(pending, disposed.pendingOperation)
            if (state is RotationRuntimeState.Faulted.Loaded) assertSame(unresolved, disposed.pendingOperation)
            for (event in listOf(
                RotationEvent.Dispose,
                RotationEvent.Initialize(LOAD),
                RotationEvent.Loaded(LOAD, RotationLoadOutcome.Ready(before)),
                RotationEvent.AvailabilityObserved(1, before.inputs.availability, RETRY),
                RotationEvent.TimerDue(waiting.ticket, NEXT),
                RotationEvent.Prepared(NEXT),
                RotationEvent.EffectsSettled(NEXT, sampleClock(3)),
                RotationEvent.CommitResolved(NEXT, RotationCommitOutcome.Accepted),
                RotationEvent.RetryRecovery(RETRY),
            )) {
                val ignored = dispatch(disposed, event, hour = 3)
                assertSame(disposed, ignored.state)
                assertTrue(ignored.commands.isEmpty())
            }
        }
    }

    private fun load(
        outcome: RotationLoadOutcome,
        hour: Long = 2,
    ): RotationTransition {
        val loading = dispatch(RotationRuntimeState.Loading(), RotationEvent.Initialize(LOAD), hour).state
        return dispatch(loading, RotationEvent.Loaded(LOAD, outcome), hour)
    }

    private fun dispatch(
        state: RotationRuntimeState,
        event: RotationEvent,
        hour: Long = 2,
    ): RotationTransition = RotationRuntimeMachine.transition(state, event, sampleClock(hour))

    private fun createSnapshot(inputs: RuntimeInputs = rotationInputs()): RotationSnapshot =
        RotationSnapshot(
            RotationRuntimeCheckpoint(
                owner = inputs.owner,
                sequenceCursorRaw = Int.MIN_VALUE,
                successfulStepSerial = 12,
                lastSuccessfulStepEpochMillis = 0,
                output = RotationOutput(inputs.owner, AyuVariant.MIRAGE, "#aB01Ef"),
                schedule = RotationSchedule(0, 6 * H, 6 * H, 7),
            ),
            inputs,
        )

    private fun sampleClock(hour: Long): ClockSample =
        ClockSample(TimeUnit.HOURS.toMillis(hour), TimeUnit.HOURS.toNanos(hour))

    private companion object {
        val H: Long = TimeUnit.HOURS.toMillis(1)
        val LOAD: UUID = UUID.fromString("18d2f5a7-8f08-4a80-a899-ea9d15b4c001")
        val NEXT: UUID = UUID.fromString("18d2f5a7-8f08-4a80-a899-ea9d15b4c002")
        val RETRY: UUID = UUID.fromString("18d2f5a7-8f08-4a80-a899-ea9d15b4c003")
    }
}
