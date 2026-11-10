package dev.ayuislands.rotation

import dev.ayuislands.accent.AyuVariant
import dev.ayuislands.studio.model.AppearanceSnapshot
import dev.ayuislands.studio.model.ApplyFailure
import dev.ayuislands.studio.model.StudioAccentContinuation
import dev.ayuislands.studio.model.StudioList
import dev.ayuislands.studio.model.StudioNativeChange
import dev.ayuislands.studio.persistence.CommitResolution
import java.util.UUID

/** Meaning of the accepted user command, independent of its UI entrypoint. */
internal enum class RotationAcceptanceOrigin {
    EXPLICIT_ROTATION_ENABLE,
    LOCAL_ROTATION_EDIT,
    LOCAL_APPEARANCE_EDIT,
    PROFILE_ACTIVATION,
    SHARED_POLICY_EDIT,
    CATALOG_ONLY,
    MIGRATION,
}

internal enum class RotationPauseReason { FOLLOW_SYSTEM, NO_PRO, EXTERNAL_THEME, TARGET_UNAVAILABLE, RECOVERY_PENDING }

/** Parallel gates are observations, never changes to the user's rotation or Follow preferences. */
internal data class RuntimeAvailability(
    val lastDefinitivePro: Boolean?,
    val effectiveFollowSystem: Boolean,
    val variant: AyuVariant?,
    val targetValid: Boolean,
    val recoveryPending: Boolean,
)

internal data class RotationIntent(
    val enabled: Boolean,
    val modeRaw: String?,
    val intervalHoursRaw: Int,
)

internal data class RuntimeInputs(
    val owner: RuntimeOwner,
    val appearance: AppearanceSnapshot,
    val availability: RuntimeAvailability,
)

internal data class ClockSample(
    val epochMillis: Long,
    val monotonicNanos: Long,
)

internal data class RotationSnapshot(
    val checkpoint: RotationRuntimeCheckpoint,
    val inputs: RuntimeInputs,
    val observationGeneration: Long = 0,
    val consecutiveFailures: Int = 0,
    val focusGeneration: Long = 0,
)

internal data class RotationTicket(
    val owner: RuntimeOwner,
    val scheduleSerial: Long,
    val delayMillis: Long,
    val startedAtMonotonicNanos: Long,
    val remainingMillis: Long = delayMillis,
)

internal sealed interface RotationOperation {
    val operationId: UUID

    val completionId: UUID get() = operationId
}

/** Operations which own a store journal, before their durable outcome has been qualified. */
internal sealed interface RotationJournalOperation : RotationOperation

internal sealed interface RotationCheckpointCommit : RotationJournalOperation {
    val checkpoint: RotationRuntimeCheckpoint
    val startedAt: ClockSample
}

internal data class RotationScheduleCommit(
    override val operationId: UUID,
    override val checkpoint: RotationRuntimeCheckpoint,
    override val startedAt: ClockSample,
) : RotationCheckpointCommit

internal data class RotationStepCommit(
    override val operationId: UUID,
    override val checkpoint: RotationRuntimeCheckpoint,
    override val startedAt: ClockSample,
    val candidate: RotationCandidate,
) : RotationCheckpointCommit

internal enum class RotationSettlementProgress { PUBLICATION_PENDING, CLEANUP_PENDING }

internal sealed interface RotationSettlement : RotationOperation {
    val commit: RotationCheckpointCommit
    val progress: RotationSettlementProgress
    val restoredWait: RotationTicket?

    override val operationId: UUID get() = commit.operationId
}

/** The durable checkpoint is already accepted; this operation retains only unfinished settlement. */
internal data class RotationStepSettlement(
    override val commit: RotationStepCommit,
    override val progress: RotationSettlementProgress,
    override val completionId: UUID = commit.operationId,
    override val restoredWait: RotationTicket? = null,
) : RotationSettlement

internal data class RotationScheduleSettlement(
    override val commit: RotationScheduleCommit,
    override val progress: RotationSettlementProgress,
    override val completionId: UUID = commit.operationId,
    override val restoredWait: RotationTicket? = null,
) : RotationSettlement

internal data class RotationOutcomeResolution(
    val pending: RotationJournalOperation,
    val failure: ApplyFailure,
    override val completionId: UUID = pending.operationId,
) : RotationOperation {
    override val operationId: UUID get() = pending.operationId
}

internal data class RotationRestore(
    val pending: RotationJournalOperation,
    val failure: ApplyFailure,
    override val completionId: UUID = pending.operationId,
) : RotationOperation {
    override val operationId: UUID get() = pending.operationId
}

/** A failed checkpoint write whose journal and owned effects have already been restored. */
internal data class RotationCheckpointRetry(
    val commit: RotationCheckpointCommit,
) : RotationOperation {
    override val operationId: UUID get() = commit.operationId
}

internal data class RotationColorRequest(
    override val operationId: UUID,
    val variant: AyuVariant,
) : RotationOperation

internal data class RotationStepPreparation(
    override val operationId: UUID,
    val candidate: RotationCandidate,
) : RotationJournalOperation

internal data class RotationStepEffects(
    override val operationId: UUID,
    val candidate: RotationCandidate,
) : RotationJournalOperation

/** A proven restored candidate waiting for permission to persist its next appointment. */
internal data class RotationRetryRequest(
    override val operationId: UUID,
    val candidate: RotationCandidate,
) : RotationOperation

internal data class RotationResumeRequest(
    val operationId: UUID,
    val resume: RotationResume.FullInterval,
    val observedAt: ClockSample,
)

internal sealed interface RotationRuntimeState {
    sealed interface Loaded : RotationRuntimeState {
        val snapshot: RotationSnapshot
    }

    data class Loading(
        val operationId: UUID? = null,
        val observation: RotationEvent.AvailabilityObserved? = null,
    ) : RotationRuntimeState

    data class Off(
        override val snapshot: RotationSnapshot,
    ) : Loaded

    data class Paused(
        override val snapshot: RotationSnapshot,
        val deferredResume: RotationResumeRequest? = null,
        val pendingRetry: RotationRetryRequest? = null,
    ) : Loaded

    data class Waiting(
        override val snapshot: RotationSnapshot,
        val ticket: RotationTicket,
    ) : Loaded

    data class Applying(
        override val snapshot: RotationSnapshot,
        val operation: RotationOperation,
        val deferredResume: RotationResumeRequest? = null,
    ) : Loaded

    sealed interface Faulted : RotationRuntimeState {
        val fault: RuntimeFault

        data class Loaded(
            override val snapshot: RotationSnapshot,
            override val fault: RuntimeFault,
            val pendingOperation: RotationOperation? = null,
            val deferredResume: RotationResumeRequest? = null,
        ) : Faulted,
            RotationRuntimeState.Loaded

        data class Unloaded(
            override val fault: RuntimeFault.DurableRecovery,
            val observation: RotationEvent.AvailabilityObserved? = null,
        ) : Faulted
    }

    data class Disposed(
        val snapshot: RotationSnapshot?,
        val pendingOperation: RotationOperation?,
    ) : RotationRuntimeState
}

/** Authoritative load facts are qualified by the coordinator; the machine never reads the store. */
internal sealed interface RotationLoadOutcome {
    data class Ready(
        val snapshot: RotationSnapshot,
    ) : RotationLoadOutcome

    data class Unavailable(
        val fault: RuntimeFault.DurableRecovery,
    ) : RotationLoadOutcome

    data class Recovering(
        val snapshot: RotationSnapshot,
        val operation: RotationOutcomeResolution,
    ) : RotationLoadOutcome

    data class Settling(
        val snapshot: RotationSnapshot,
        val operation: RotationSettlement,
    ) : RotationLoadOutcome
}

internal sealed interface RotationCommitOutcome {
    data object Accepted : RotationCommitOutcome

    data object RejectedRestored : RotationCommitOutcome

    data class NotCommitted(
        val failure: ApplyFailure,
    ) : RotationCommitOutcome

    data class OutcomeUnknown(
        val failure: ApplyFailure,
    ) : RotationCommitOutcome

    data class Readback(
        val resolution: CommitResolution,
    ) : RotationCommitOutcome

    /** Qualified acknowledgement that publication, owner release, and journal cleanup have completed. */
    data object Settled : RotationCommitOutcome

    data class AcceptedCleanupPending(
        val progress: RotationSettlementProgress,
        val failure: RuntimeFault.DurableRecovery,
    ) : RotationCommitOutcome
}

internal sealed interface RotationPreparationOutcome {
    data object Ready : RotationPreparationOutcome

    data class RejectedRestored(
        val nextOperationId: UUID,
        val failure: ApplyFailure,
    ) : RotationPreparationOutcome

    data class OutcomeUnknown(
        val failure: ApplyFailure,
    ) : RotationPreparationOutcome
}

internal sealed interface RotationEffectOutcome {
    data object Applied : RotationEffectOutcome

    data class Failed(
        val failure: ApplyFailure,
    ) : RotationEffectOutcome

    data class RejectedRestored(
        val nextOperationId: UUID,
    ) : RotationEffectOutcome

    data class RecoveryPending(
        val failure: ApplyFailure,
    ) : RotationEffectOutcome
}

internal sealed interface RotationEvent {
    data class Initialize(
        val operationId: UUID,
    ) : RotationEvent

    data class Loaded(
        val operationId: UUID,
        val outcome: RotationLoadOutcome,
    ) : RotationEvent

    /** A user transaction already committed its checkpoint; only its cleanup acknowledgement remains. */
    data class UserAccepted(
        val snapshot: RotationSnapshot,
        val settlement: RotationSettlement,
    ) : RotationEvent

    data class NativeChange(
        val operationId: UUID,
        val change: StudioNativeChange,
    ) : RotationEvent

    sealed interface ContextChange : RotationEvent {
        val operationId: UUID
        val generation: Long
        val inputs: RuntimeInputs
    }

    data class FocusChanged(
        override val operationId: UUID,
        override val generation: Long,
        override val inputs: RuntimeInputs,
    ) : ContextChange

    data class ContinuityTransfer(
        override val operationId: UUID,
        override val generation: Long,
        override val inputs: RuntimeInputs,
        val accent: StudioAccentContinuation? = null,
    ) : ContextChange

    data object Dispose : RotationEvent

    data object ViewOnly : RotationEvent

    data class TimerDue(
        val ticket: RotationTicket,
        val operationId: UUID,
    ) : RotationEvent

    data class RandomColorChosen(
        val operationId: UUID,
        val hex: String,
    ) : RotationEvent

    data class AvailabilityObserved(
        val generation: Long,
        val availability: RuntimeAvailability,
        val operationId: UUID,
    ) : RotationEvent

    /** The arbiter qualifies preparation, including restoration or an unresolved journal. */
    data class Prepared(
        val operationId: UUID,
        val outcome: RotationPreparationOutcome = RotationPreparationOutcome.Ready,
    ) : RotationEvent

    /** Applied is a qualified semantic boundary; failed effects and incomplete rollback remain explicit. */
    data class EffectsSettled(
        val operationId: UUID,
        val completedAt: ClockSample,
        val outcome: RotationEffectOutcome = RotationEffectOutcome.Applied,
        val completionId: UUID = operationId,
    ) : RotationEvent

    data class CommitResolved(
        val operationId: UUID,
        val outcome: RotationCommitOutcome,
        val completionId: UUID = operationId,
    ) : RotationEvent

    data class RetryRecovery(
        val operationId: UUID,
    ) : RotationEvent
}

internal sealed interface RotationCommand {
    data class RequestRandomColor(
        val operationId: UUID,
        val variant: AyuVariant,
    ) : RotationCommand

    data class PrepareStep(
        val operationId: UUID,
        val candidate: RotationCandidate,
        val checkpoint: RotationRuntimeCheckpoint,
    ) : RotationCommand

    data class ApplyPreparedEffects(
        val operationId: UUID,
        val candidate: RotationCandidate,
    ) : RotationCommand

    data class PersistCheckpoint(
        val operation: RotationCheckpointCommit,
    ) : RotationCommand {
        val operationId: UUID get() = operation.operationId
        val checkpoint: RotationRuntimeCheckpoint get() = operation.checkpoint
    }

    data class PublishAccepted(
        val operation: RotationSettlement,
    ) : RotationCommand {
        val operationId: UUID get() = operation.operationId
        val checkpoint: RotationRuntimeCheckpoint get() = operation.commit.checkpoint
        val progress: RotationSettlementProgress get() = operation.progress
    }

    data class ResolveStoreOutcome(
        val operation: RotationOutcomeResolution,
    ) : RotationCommand

    data class RestoreOwnedEffects(
        val operation: RotationRestore,
    ) : RotationCommand

    data class ArmSchedule(
        val ticket: RotationTicket,
    ) : RotationCommand

    data class CancelSchedule(
        val ticket: RotationTicket,
    ) : RotationCommand

    data class ReportFault(
        val fault: RuntimeFault,
    ) : RotationCommand
}

internal data class RotationTransition(
    val state: RotationRuntimeState,
    val commands: StudioList<RotationCommand> = StudioList(emptyList()),
)

internal enum class RotationStepBlock {
    DISABLED,
    INELIGIBLE,
    OWNER_CHANGED,
    EMPTY_CATALOG,
    SERIAL_OVERFLOW,
    DEADLINE_OVERFLOW,
    INVALID_COLOR,
    RECOVERY_PENDING,
    STALE_CANDIDATE,
}

internal sealed interface RotationStepPlan {
    data class Ready(
        val candidate: RotationCandidate,
        val intervalMillis: Long,
    ) : RotationStepPlan

    data class RandomRequired(
        val operationId: UUID,
        val variant: AyuVariant,
    ) : RotationStepPlan

    data class Blocked(
        val reason: RotationStepBlock,
    ) : RotationStepPlan
}

internal sealed interface RotationCompletionPlan {
    data class Ready(
        val checkpoint: RotationRuntimeCheckpoint,
    ) : RotationCompletionPlan

    data class Blocked(
        val reason: RotationStepBlock,
    ) : RotationCompletionPlan
}
