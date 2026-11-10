package dev.ayuislands.rotation

import dev.ayuislands.studio.model.StudioList
import dev.ayuislands.studio.model.StudioNativeChange
import java.util.UUID

/** Native work borrows the existing transaction lifecycle without advancing rotation history. */
internal data class RotationNativeCommit(
    override val operationId: UUID,
    override val checkpoint: RotationRuntimeCheckpoint,
    override val startedAt: ClockSample,
    val before: RotationSnapshot,
    val change: StudioNativeChange,
    val previous: RotationRuntimeState.Loaded? = null,
) : RotationCheckpointCommit {
    val waiting: RotationTicket? get() = (previous as? RotationRuntimeState.Waiting)?.ticket
}

internal data class RotationNativeSettlement(
    override val commit: RotationNativeCommit,
    override val progress: RotationSettlementProgress,
    override val completionId: UUID = commit.operationId,
    override val restoredWait: RotationTicket? = commit.waiting,
) : RotationSettlement

internal object RotationNativeChange {
    fun prepare(
        state: RotationRuntimeState.Loaded,
        event: RotationEvent.NativeChange,
        clock: ClockSample,
    ): RotationTransition {
        if (state is RotationRuntimeState.Applying) return RotationTransition(state)
        if (state is RotationRuntimeState.Faulted.Loaded &&
            state.pendingOperation != null
        ) {
            return RotationTransition(state)
        }
        val commit =
            RotationNativeCommit(
                event.operationId,
                state.snapshot.checkpoint,
                clock,
                state.snapshot,
                event.change,
                state,
            )
        val commands =
            buildList {
                if (state is RotationRuntimeState.Waiting) add(RotationCommand.CancelSchedule(state.ticket))
                add(RotationCommand.PersistCheckpoint(commit))
            }
        return RotationTransition(RotationRuntimeState.Applying(state.snapshot, commit), StudioList(commands))
    }

    fun retry(
        commit: RotationNativeCommit,
        operationId: UUID,
        clock: ClockSample,
    ): RotationTransition {
        val retry = commit.copy(operationId = operationId, startedAt = clock)
        return RotationTransition(
            RotationRuntimeState.Applying(commit.before, retry),
            StudioList(listOf(RotationCommand.PersistCheckpoint(retry))),
        )
    }
}
