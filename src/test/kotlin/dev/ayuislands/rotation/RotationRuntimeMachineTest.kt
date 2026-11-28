package dev.ayuislands.rotation

import dev.ayuislands.accent.AyuVariant
import dev.ayuislands.licensing.LicenseEntitlement
import dev.ayuislands.studio.model.StudioProjectKey
import dev.ayuislands.studio.model.StudioTarget
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class RotationRuntimeMachineTest {
    private val owner = RuntimeOwner(StudioTarget.Global, 12)
    private val intent = RotationIntent(enabled = true, modeRaw = null, intervalHoursRaw = 6)
    private val availability = RotationAvailability(LicenseEntitlement.LICENSED, false, AyuVariant.MIRAGE, true, false)
    private val generation = RotationTimerGeneration(service = 4, target = 8)

    @Test
    fun `duplicate accepted observations keep the live countdown through a wall clock change`() {
        val current = snapshot()
        val waiting = start(current).state
        val first = assertIs<RotationRuntimeState.Waiting>(waiting).ticket

        val duplicate = reconcile(waiting, current, clock(epochHours = 48, elapsedHours = 2))
        assertEquals(waiting, duplicate.state)
        assertEquals(emptyList(), duplicate.commands)

        val due = wake(waiting, first, clock(epochHours = 2, elapsedHours = 6))
        assertEquals(listOf(RotationRuntimeCommand.StepDue(current)), due.commands)
        assertEquals(emptyList(), wake(due.state, first, clock(2, 6)).commands)
        assertEquals(emptyList(), reconcile(due.state, current, clock(48, 7)).commands)
    }

    @Test
    fun `eligible focus change rebinds the retained appointment and cancels the old callback`() {
        val current = snapshot()
        val oldWait = assertIs<RotationRuntimeState.Waiting>(start(current).state)
        val other =
            current.copy(
                checkpoint =
                    current.checkpoint.copy(
                        owner = RuntimeOwner(StudioTarget.Project(StudioProjectKey("/other")), 13),
                    ),
                generation = generation.copy(target = 9),
            )

        val rebound = reconcile(oldWait, other, clock(epochHours = 48, elapsedHours = 2))
        val next = assertIs<RotationRuntimeState.Waiting>(rebound.state).ticket
        assertEquals(other.checkpoint.owner, next.key.owner)
        assertEquals(4 * HOUR, next.delayMillis)
        assertEquals(
            listOf(RotationRuntimeCommand.CancelSchedule(oldWait.ticket), RotationRuntimeCommand.ArmSchedule(next)),
            rebound.commands,
        )
        assertEquals(emptyList(), wake(rebound.state, oldWait.ticket, clock(48, 6)).commands)
        assertEquals(listOf(RotationRuntimeCommand.StepDue(other)), wake(rebound.state, next, clock(48, 6)).commands)
    }

    @Test
    fun `replacement appointments and restarted services use their own wall-clock calculation`() {
        val current = snapshot()
        val oldWait = assertIs<RotationRuntimeState.Waiting>(start(current).state)
        val replacement =
            current.copy(
                checkpoint =
                    current.checkpoint.copy(
                        schedule =
                            RotationSchedule(
                                HOUR,
                                7 * HOUR,
                                6 * HOUR,
                                2,
                            ),
                    ),
            )
        val restarted = current.copy(generation = generation.copy(service = 5))

        for ((incoming, expected) in listOf(replacement to 4L, restarted to 3L)) {
            val replaced = reconcile(oldWait, incoming, clock(epochHours = 3, elapsedHours = 1))
            val next = assertIs<RotationRuntimeState.Waiting>(replaced.state).ticket
            assertEquals(expected * HOUR, next.delayMillis)
            assertEquals(
                listOf(RotationRuntimeCommand.CancelSchedule(oldWait.ticket), RotationRuntimeCommand.ArmSchedule(next)),
                replaced.commands,
            )
            assertEquals(emptyList(), wake(replaced.state, oldWait.ticket, clock(7, 6)).commands)
        }
    }

    @Test
    fun `stopping rotation cancels its ticket and retains the exact accepted values`() {
        val current = snapshot()
        val waiting = assertIs<RotationRuntimeState.Waiting>(start(current).state)
        val off = current.copy(intent = intent.copy(enabled = false))
        val bothPaused =
            current.copy(
                availability = availability.copy(entitlement = LicenseEntitlement.UNLICENSED, targetValid = false),
            )

        val stopped = reconcile(waiting, off, clock(1, 1))
        val paused = reconcile(waiting, bothPaused, clock(1, 1))
        assertEquals(RotationRuntimeState.Off(off), stopped.state)
        assertEquals(RotationRuntimeState.Paused(bothPaused), paused.state)
        for (transition in listOf(stopped, paused)) {
            assertEquals(listOf(RotationRuntimeCommand.CancelSchedule(waiting.ticket)), transition.commands)
            assertEquals(emptyList(), wake(transition.state, waiting.ticket, clock(6, 6)).commands)
        }

        val returned = reconcile(paused.state, current, clock(2, 2))
        val rearmed = assertIs<RotationRuntimeState.Waiting>(returned.state).ticket
        assertEquals(4 * HOUR, rearmed.delayMillis)
        assertEquals(listOf(RotationRuntimeCommand.ArmSchedule(rearmed)), returned.commands)
    }

    @Test
    fun `an interval after Follow starts once the replacement appointment is saved`() {
        val current = snapshot(resume = RotationResume.FullInterval(FullIntervalReason.FOLLOW_SYSTEM))
        val starting = start(current, clock(2, 1))
        val request = assertIs<RotationRuntimeCommand.PersistCheckpoint>(starting.commands.single())
        assertEquals(current, request.source)
        assertEquals(RotationResume.KeepDeadline, request.proposed.checkpoint.resume)
        assertEquals(
            8 * HOUR,
            request.proposed.checkpoint.schedule
                ?.deadlineEpochMillis,
        )
        assertEquals(emptyList(), reconcile(starting.state, current, clock(3, 2)).commands)

        val accepted = current.copy(checkpoint = request.proposed.checkpoint)
        val running = reconcile(starting.state, accepted, clock(3, 2))
        val ticket = assertIs<RotationRuntimeState.Waiting>(running.state).ticket
        assertEquals(5 * HOUR, ticket.delayMillis)
        assertEquals(listOf(RotationRuntimeCommand.ArmSchedule(ticket)), running.commands)
    }

    @Test
    fun `startup emits one immediate step when the saved intent or appointment requires it`() {
        for (resume in listOf(
            RotationResume.KeepDeadline,
            RotationResume.NoHistory,
            RotationResume.ImmediateEnable,
            RotationResume.ImmediateConfiguration,
        )) {
            val current = snapshot(resume)
            val starting = start(current, clock(7, 0))
            assertEquals(listOf(RotationRuntimeCommand.StepDue(current)), starting.commands)
            assertEquals(emptyList(), reconcile(starting.state, current, clock(9, 2)).commands)
        }
    }

    @Test
    fun `a long appointment replaces guard tickets and emits just one final step`() {
        val current =
            snapshot().let {
                it.copy(checkpoint = it.checkpoint.copy(schedule = RotationSchedule(0, 48 * HOUR, 48 * HOUR, 1)))
            }
        val first = assertIs<RotationRuntimeState.Waiting>(start(current).state)
        val guard = wake(first, first.ticket, clock(24, 24))
        val second = assertIs<RotationRuntimeState.Waiting>(guard.state).ticket
        assertEquals(24 * HOUR, second.delayMillis)
        assertEquals(listOf(RotationRuntimeCommand.ArmSchedule(second)), guard.commands)
        assertEquals(emptyList(), wake(guard.state, first.ticket, clock(48, 48)).commands)
        assertEquals(listOf(RotationRuntimeCommand.StepDue(current)), wake(guard.state, second, clock(48, 48)).commands)
    }

    @Test
    fun `disposal cancels the active appointment and prevents any later command`() {
        val current = snapshot()
        val waiting = assertIs<RotationRuntimeState.Waiting>(start(current).state)
        val disposed = transitionRotationRuntime(waiting, RotationRuntimeEvent.Dispose, clock(1, 1))
        assertEquals(RotationRuntimeState.Disposed, disposed.state)
        assertEquals(listOf(RotationRuntimeCommand.CancelSchedule(waiting.ticket)), disposed.commands)
        assertEquals(emptyList(), wake(disposed.state, waiting.ticket, clock(6, 6)).commands)
        assertEquals(emptyList(), reconcile(disposed.state, current, clock(6, 6)).commands)
        assertEquals(
            emptyList(),
            transitionRotationRuntime(disposed.state, RotationRuntimeEvent.Dispose, clock(6, 6)).commands,
        )
    }

    @Test
    fun `durable recovery and unrepresentable appointments stop scheduling and report their fault once`() {
        val current = snapshot()
        val waiting = assertIs<RotationRuntimeState.Waiting>(start(current).state)
        val recoveryFault = RuntimeFault.DurableRecovery("pending-rotation")
        val recovering = current.copy(checkpoint = current.checkpoint.copy(recoveryFault = recoveryFault))
        val blocked = reconcile(waiting, recovering, clock(1, 1))
        assertEquals(RotationRuntimeState.Faulted(recovering, recoveryFault), blocked.state)
        assertEquals(
            listOf(
                RotationRuntimeCommand.CancelSchedule(waiting.ticket),
                RotationRuntimeCommand.ReportFault(recoveryFault),
            ),
            blocked.commands,
        )
        assertEquals(emptyList(), reconcile(blocked.state, recovering, clock(2, 2)).commands)

        val overflow = snapshot(RotationResume.FullInterval(FullIntervalReason.FOLLOW_SYSTEM))
        val brokenClock = ClockSample(Long.MAX_VALUE, 0)
        val fault = start(overflow, brokenClock)
        assertEquals(
            listOf(RotationRuntimeCommand.ReportFault(RuntimeFault.UnrepresentableAppointment)),
            fault.commands,
        )
        assertIs<RotationRuntimeState.Faulted>(fault.state)
        assertEquals(emptyList(), reconcile(fault.state, overflow, clock(2, 2)).commands)
    }

    private fun snapshot(resume: RotationResume = RotationResume.KeepDeadline): RotationRuntimeSnapshot =
        RotationRuntimeSnapshot(
            checkpoint =
                RotationRuntimeCheckpoint(
                    owner,
                    5,
                    7,
                    schedule = RotationSchedule(0, 6 * HOUR, 6 * HOUR, 1),
                    resume = resume,
                ),
            intent = intent,
            availability = availability,
            generation = generation,
        )

    private fun start(
        snapshot: RotationRuntimeSnapshot,
        clock: ClockSample = clock(0, 0),
    ): RotationTransition = reconcile(RotationRuntimeState.Loading, snapshot, clock)

    private fun reconcile(
        state: RotationRuntimeState,
        snapshot: RotationRuntimeSnapshot,
        clock: ClockSample,
    ): RotationTransition = transitionRotationRuntime(state, RotationRuntimeEvent.Reconcile(snapshot), clock)

    private fun wake(
        state: RotationRuntimeState,
        ticket: RotationWaitTicket,
        clock: ClockSample,
    ): RotationTransition = transitionRotationRuntime(state, RotationRuntimeEvent.TimerDue(ticket), clock)

    private fun clock(
        epochHours: Long,
        elapsedHours: Long,
    ): ClockSample = ClockSample(epochHours * HOUR, TimeUnit.HOURS.toNanos(elapsedHours))

    private companion object {
        const val HOUR = 3_600_000L
    }
}
