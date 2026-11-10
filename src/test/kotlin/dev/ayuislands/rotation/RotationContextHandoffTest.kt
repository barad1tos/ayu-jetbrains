package dev.ayuislands.rotation

import dev.ayuislands.accent.AyuVariant
import dev.ayuislands.studio.model.StudioProjectKey
import dev.ayuislands.studio.model.StudioTarget
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class RotationContextHandoffTest {
    @Test
    fun `continuous focus preserves the exact appointment and cursor before and after durable acceptance`() {
        val before = waiting()
        val incoming = rotationInputs(RotationIntent(true, "RANDOM", 1)).copy(owner = OWNER_B)

        val prepared = focus(before, incoming, 2, FIRST, 12 * HOUR)

        val operation = assertIs<RotationCommand.PersistCheckpoint>(prepared.commands.last())
        assertEquals(before.ticket, assertIs<RotationCommand.CancelSchedule>(prepared.commands.first()).ticket)
        assertEquals(OWNER_B, operation.checkpoint.owner)
        assertSame(before.snapshot.checkpoint.schedule, operation.checkpoint.schedule)
        assertEquals(Int.MIN_VALUE, operation.checkpoint.sequenceCursorRaw)
        assertEquals(7L, operation.checkpoint.successfulStepSerial)
        assertNull(operation.checkpoint.output)
        assertEquals(
            before.snapshot.checkpoint,
            assertIs<RotationRuntimeState.Applying>(prepared.state).snapshot.checkpoint,
        )
        assertTrue(
            prepared.commands.none {
                it is RotationCommand.PrepareStep ||
                    it is RotationCommand.RequestRandomColor
            },
        )

        val accepted = completeRotationCommit(prepared.state, FIRST, clock(13 * HOUR))
        val after = assertIs<RotationRuntimeState.Waiting>(accepted.state)
        assertEquals(5 * HOUR, after.ticket.remainingMillis)
        assertSame(before.snapshot.checkpoint.schedule, after.snapshot.checkpoint.schedule)
        assertEquals(incoming, after.snapshot.inputs)
        assertEquals(2L, after.snapshot.focusGeneration)
        assertEquals(1, accepted.commands.filterIsInstance<RotationCommand.ArmSchedule>().size)
    }

    @Test
    fun `an Off or unavailable handoff requires a full interval even when focus returns to its old project`() {
        val alternatives =
            listOf(
                rotationInputs(RotationIntent(false, "PRESET", 1)),
                rotationInputs().let { it.copy(availability = it.availability.copy(lastDefinitivePro = false)) },
            )
        for (unavailable in alternatives) {
            val before = waiting()
            val stopped = focus(before, unavailable.copy(owner = OWNER_B), 2, FIRST, 12 * HOUR)
            val committed = completeRotationCommit(stopped.state, FIRST, clock(12 * HOUR))
            assertTrue(committed.commands.none { it is RotationCommand.ArmSchedule })
            val retained = assertIs<RotationRuntimeState.Loaded>(committed.state).snapshot.checkpoint
            assertEquals(RotationResume.FullInterval(FullIntervalReason.INTERRUPTED_HANDOFF), retained.resume)
            assertEquals(Int.MIN_VALUE, retained.sequenceCursorRaw)

            val returned = focus(committed.state, rotationInputs().copy(owner = OWNER_A), 3, SECOND, 14 * HOUR)
            val after = completeRotationCommit(returned.state, SECOND, clock(14 * HOUR))
            val waiting = assertIs<RotationRuntimeState.Waiting>(after.state)
            assertEquals(RotationSchedule(14 * HOUR, 15 * HOUR, HOUR, 6), waiting.snapshot.checkpoint.schedule)
            assertEquals(7L, waiting.snapshot.checkpoint.successfulStepSerial)
            assertEquals(Int.MIN_VALUE, waiting.snapshot.checkpoint.sequenceCursorRaw)
            assertEquals(HOUR, waiting.ticket.remainingMillis)
            assertTrue(returned.commands.none { it is RotationCommand.PrepareStep })
        }
    }

    @Test
    fun `last project continuity rebinds provenance and output without a new appointment or color step`() {
        val before = waiting()
        val global = before.snapshot.inputs.copy(owner = RuntimeOwner(StudioTarget.Global, 41))
        val prepared =
            RotationRuntimeMachine.transition(
                before,
                RotationEvent.ContinuityTransfer(FIRST, 2, global),
                clock(12 * HOUR),
            )
        val operation = assertIs<RotationCommand.PersistCheckpoint>(prepared.commands.last())
        assertEquals(global.owner, operation.checkpoint.owner)
        assertSame(before.snapshot.checkpoint.schedule, operation.checkpoint.schedule)
        assertEquals(
            before.snapshot.checkpoint.output
                ?.copy(owner = global.owner),
            operation.checkpoint.output,
        )
        assertEquals(before.snapshot.checkpoint.sequenceCursorRaw, operation.checkpoint.sequenceCursorRaw)
        assertEquals(before.snapshot.checkpoint.resume, operation.checkpoint.resume)
        assertTrue(
            prepared.commands.none {
                it is RotationCommand.PrepareStep ||
                    it is RotationCommand.RequestRandomColor
            },
        )

        val accepted = completeRotationCommit(prepared.state, FIRST, clock(13 * HOUR))
        val after = assertIs<RotationRuntimeState.Waiting>(accepted.state)
        assertEquals(5 * HOUR, after.ticket.remainingMillis)
        assertEquals(global.owner, after.ticket.owner)
        assertSame(before.snapshot.inputs.appearance, after.snapshot.inputs.appearance)
        assertEquals(7L, after.snapshot.checkpoint.successfulStepSerial)
    }

    @Test
    fun `stale focus generations duplicate accepted focus and disposed callbacks cannot create another operation`() {
        val before = waiting()
        val incoming = rotationInputs().copy(owner = OWNER_B)
        val prepared = focus(before, incoming, 2, FIRST, 12 * HOUR)
        val accepted = completeRotationCommit(prepared.state, FIRST, clock(12 * HOUR))
        for ((generation, inputs) in listOf(1L to before.snapshot.inputs, 2L to incoming)) {
            val ignored = focus(accepted.state, inputs, generation, SECOND, 13 * HOUR)
            assertSame(accepted.state, ignored.state)
            assertTrue(ignored.commands.isEmpty())
        }
        val disposed = RotationRuntimeMachine.transition(accepted.state, RotationEvent.Dispose, clock(13 * HOUR))
        val ignored = focus(disposed.state, before.snapshot.inputs, 3, SECOND, 14 * HOUR)
        assertSame(disposed.state, ignored.state)
        assertTrue(ignored.commands.isEmpty())
    }

    @Test
    fun `continuity preserves dormant and paused obligations without introducing an interrupted handoff`() {
        val running = waiting()
        val owner = RuntimeOwner(StudioTarget.Global, 41)
        val cases =
            listOf(
                RotationRuntimeState.Off(
                    running.snapshot.copy(
                        checkpoint =
                            running.snapshot.checkpoint.copy(
                                schedule = null,
                                resume = RotationResume.NoHistory,
                            ),
                        inputs = rotationInputs(RotationIntent(false, "PRESET", 6)).copy(owner = OWNER_A),
                    ),
                ),
                RotationRuntimeState.Paused(
                    running.snapshot.copy(
                        checkpoint =
                            running.snapshot.checkpoint.copy(
                                resume = RotationResume.FullInterval(FullIntervalReason.FOLLOW_SYSTEM),
                            ),
                        inputs =
                            running.snapshot.inputs.copy(
                                availability =
                                    running.snapshot.inputs.availability
                                        .copy(effectiveFollowSystem = true),
                            ),
                    ),
                ),
            )
        for (before in cases) {
            val prepared =
                RotationRuntimeMachine.transition(
                    before,
                    RotationEvent.ContinuityTransfer(FIRST, 2, before.snapshot.inputs.copy(owner = owner)),
                    clock(13 * HOUR),
                )
            val committed = completeRotationCommit(prepared.state, FIRST, clock(13 * HOUR))
            val after = assertIs<RotationRuntimeState.Loaded>(committed.state)
            assertEquals(
                before.snapshot.checkpoint.copy(
                    owner = owner,
                    output =
                        before.snapshot.checkpoint.output
                            ?.copy(owner = owner),
                ),
                after.snapshot.checkpoint,
            )
            assertEquals(before.snapshot.inputs.availability, after.snapshot.inputs.availability)
            assertEquals(before::class, after::class)
            assertTrue(committed.commands.none { it is RotationCommand.ArmSchedule })
        }
    }

    @Test
    fun `continuity retains a durable fault instead of silently restarting rotation`() {
        val running = waiting()
        val fault = RuntimeFault.DurableRecovery("unresolved-native-owner")
        val before =
            RotationRuntimeState.Faulted.Loaded(
                running.snapshot.copy(checkpoint = running.snapshot.checkpoint.copy(recoveryFault = fault)),
                fault,
            )
        val owner = RuntimeOwner(StudioTarget.Global, 41)
        val prepared =
            RotationRuntimeMachine.transition(
                before,
                RotationEvent.ContinuityTransfer(FIRST, 2, before.snapshot.inputs.copy(owner = owner)),
                clock(13 * HOUR),
            )
        val committed = completeRotationCommit(prepared.state, FIRST, clock(13 * HOUR))
        val after = assertIs<RotationRuntimeState.Faulted.Loaded>(committed.state)

        assertEquals(fault, after.fault)
        assertEquals(fault, after.snapshot.checkpoint.recoveryFault)
        assertEquals(owner, after.snapshot.inputs.owner)
        assertEquals(before.snapshot.checkpoint.schedule, after.snapshot.checkpoint.schedule)
        assertTrue(committed.commands.none { it is RotationCommand.ArmSchedule || it is RotationCommand.PrepareStep })
    }

    @Test
    fun `continuity retains a paused frozen retry and its deferred resume under the new owner`() {
        val running = waiting()
        val candidate =
            RotationCandidate(
                SECOND,
                OWNER_A,
                running.snapshot.inputs.intent,
                AccentRotationMode.PRESET,
                AyuVariant.MIRAGE,
                "#ABC123",
                8,
            )
        val deferred =
            RotationResumeRequest(
                SECOND,
                RotationResume.FullInterval(FullIntervalReason.FOLLOW_SYSTEM),
                clock(12 * HOUR),
            )
        val retry = RotationRetryRequest(SECOND, candidate)
        val before =
            RotationRuntimeState.Paused(
                running.snapshot.copy(
                    checkpoint = running.snapshot.checkpoint.copy(retryCandidate = candidate),
                    inputs =
                        running.snapshot.inputs.copy(
                            availability =
                                running.snapshot.inputs.availability
                                    .copy(effectiveFollowSystem = true),
                        ),
                ),
                deferred,
                retry,
            )
        val owner = RuntimeOwner(StudioTarget.Global, 41)
        val prepared =
            RotationRuntimeMachine.transition(
                before,
                RotationEvent.ContinuityTransfer(FIRST, 2, before.snapshot.inputs.copy(owner = owner)),
                clock(13 * HOUR),
            )
        val committed = completeRotationCommit(prepared.state, FIRST, clock(13 * HOUR))
        val after = assertIs<RotationRuntimeState.Paused>(committed.state)

        assertEquals(deferred, after.deferredResume)
        assertEquals(retry.copy(candidate = candidate.copy(owner = owner)), after.pendingRetry)
        assertEquals(candidate.copy(owner = owner), after.snapshot.checkpoint.retryCandidate)
        assertEquals(before.snapshot.inputs.availability, after.snapshot.inputs.availability)
        assertTrue(committed.commands.none { it is RotationCommand.ArmSchedule || it is RotationCommand.PrepareStep })
    }

    private fun waiting(): RotationRuntimeState.Waiting {
        val inputs = rotationInputs(RotationIntent(true, "PRESET", 6)).copy(owner = OWNER_A)
        val checkpoint =
            RotationRuntimeCheckpoint(
                OWNER_A,
                Int.MIN_VALUE,
                7,
                lastSuccessfulStepEpochMillis = 12 * HOUR,
                output = RotationOutput(OWNER_A, AyuVariant.MIRAGE, "#ABC123"),
                schedule = RotationSchedule(12 * HOUR, 18 * HOUR, 6 * HOUR, 5),
            )
        return RotationRuntimeState.Waiting(
            RotationSnapshot(checkpoint, inputs, focusGeneration = 1),
            RotationTicket(OWNER_A, 5, 6 * HOUR, clock(12 * HOUR).monotonicNanos),
        )
    }

    private fun focus(
        state: RotationRuntimeState,
        inputs: RuntimeInputs,
        generation: Long,
        operation: UUID,
        now: Long,
    ): RotationTransition =
        RotationRuntimeMachine.transition(
            state,
            RotationEvent.FocusChanged(operation, generation, inputs),
            clock(now),
        )

    private fun clock(now: Long): ClockSample = ClockSample(now, TimeUnit.MILLISECONDS.toNanos(now))

    private companion object {
        const val HOUR = 3_600_000L
        val OWNER_A = RuntimeOwner(StudioTarget.Project(StudioProjectKey("project-a")), 11)
        val OWNER_B = RuntimeOwner(StudioTarget.Project(StudioProjectKey("project-b")), 23)
        val FIRST = UUID(0, 31)
        val SECOND = UUID(0, 32)
    }
}
