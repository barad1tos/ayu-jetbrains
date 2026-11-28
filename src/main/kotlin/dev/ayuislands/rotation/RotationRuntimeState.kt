package dev.ayuislands.rotation

internal data class ClockSample(
    val epochMillis: Long,
    val monotonicNanos: Long,
)

/** The current accepted appointment and its already resolved eligibility. */
internal data class RotationRuntimeSnapshot(
    val checkpoint: RotationRuntimeCheckpoint,
    val intent: RotationIntent,
    val availability: RotationAvailability,
    val generation: RotationTimerGeneration,
)

internal sealed interface RotationRuntimeState {
    data object Loading : RotationRuntimeState

    data object Disposed : RotationRuntimeState

    sealed interface Loaded : RotationRuntimeState {
        val current: RotationRuntimeSnapshot
    }

    data class Off(
        override val current: RotationRuntimeSnapshot,
    ) : Loaded

    data class Paused(
        override val current: RotationRuntimeSnapshot,
    ) : Loaded

    data class Waiting(
        override val current: RotationRuntimeSnapshot,
        val ticket: RotationWaitTicket,
    ) : Loaded

    /** One command has been handed off to the transaction owner for this accepted snapshot. */
    data class Applying(
        override val current: RotationRuntimeSnapshot,
    ) : Loaded

    data class Faulted(
        override val current: RotationRuntimeSnapshot,
        val fault: RuntimeFault,
    ) : Loaded
}

internal sealed interface RotationRuntimeEvent {
    /** Supply the authoritative snapshot after any in-flight command has settled. */
    data class Reconcile(
        val current: RotationRuntimeSnapshot,
    ) : RotationRuntimeEvent

    data class TimerDue(
        val ticket: RotationWaitTicket,
    ) : RotationRuntimeEvent

    data class ArmRejected(
        val ticket: RotationWaitTicket,
    ) : RotationRuntimeEvent

    data object Dispose : RotationRuntimeEvent
}

internal sealed interface RotationRuntimeCommand {
    data class CancelSchedule(
        val ticket: RotationWaitTicket,
    ) : RotationRuntimeCommand

    data class ArmSchedule(
        val ticket: RotationWaitTicket,
    ) : RotationRuntimeCommand

    data class PersistCheckpoint(
        val source: RotationRuntimeSnapshot,
        val proposed: RotationTimingPlan.Persist,
    ) : RotationRuntimeCommand

    data class StepDue(
        val source: RotationRuntimeSnapshot,
    ) : RotationRuntimeCommand

    data class ReportFault(
        val fault: RuntimeFault,
    ) : RotationRuntimeCommand
}

internal data class RotationTransition(
    val state: RotationRuntimeState,
    val commands: List<RotationRuntimeCommand> = emptyList(),
)
