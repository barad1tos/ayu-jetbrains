package dev.ayuislands.rotation

import dev.ayuislands.accent.AyuVariant
import dev.ayuislands.studio.model.StudioTarget
import java.util.UUID

/** Identity of accepted Current, independent of a reusable profile definition. */
internal data class RuntimeOwner(
    val target: StudioTarget,
    val appearanceRevision: Long,
)

internal data class RotationOutput(
    val owner: RuntimeOwner,
    val variant: AyuVariant,
    val hex: String,
)

/** The interval belongs to this appointment; an incoming profile does not shorten it. */
internal data class RotationSchedule(
    val scheduledAtEpochMillis: Long,
    val deadlineEpochMillis: Long,
    val intervalMillisAtScheduling: Long,
    val scheduleSerial: Long,
)

/** A frozen choice keeps its original operation identity and color across later retry attempts. */
internal data class RotationCandidate(
    val operationId: UUID,
    val owner: RuntimeOwner,
    val intent: RotationIntent,
    val mode: AccentRotationMode,
    val variant: AyuVariant,
    val hex: String,
    val nextCursor: Int,
)

internal const val ROTATION_FAILURE_LIMIT = 3

/** Keeps the selected color and the number of attempts whose writes have been restored. */
internal data class RotationRetry(
    val candidate: RotationCandidate,
    val failures: Int = 0,
) {
    init {
        require(failures in 0..ROTATION_FAILURE_LIMIT) { "Invalid restored rotation failure count" }
    }
}

internal enum class FullIntervalReason { FOLLOW_SYSTEM, INTERRUPTED_HANDOFF, REPAIRED_SCHEDULE, RESTORED_RETRY }

internal sealed interface RotationResume {
    data object KeepDeadline : RotationResume

    data object NoHistory : RotationResume

    data object ImmediateEnable : RotationResume

    data object ImmediateConfiguration : RotationResume

    data object ExternalCadence : RotationResume

    data class FullInterval(
        val reason: FullIntervalReason,
    ) : RotationResume
}

internal sealed interface RuntimeFault {
    data object UnrepresentableAppointment : RuntimeFault

    data object SchedulerUnavailable : RuntimeFault

    data class DurableRecovery(
        val code: String,
    ) : RuntimeFault
}

/** Accepted runtime history; proposed appointments are kept separately until their storage acknowledgement. */
internal data class RotationRuntimeCheckpoint(
    val owner: RuntimeOwner,
    val sequenceCursorRaw: Int,
    val successfulStepSerial: Long,
    val lastSuccessfulStepEpochMillis: Long? = null,
    val output: RotationOutput? = null,
    val schedule: RotationSchedule? = null,
    val resume: RotationResume = RotationResume.KeepDeadline,
    val retry: RotationRetry? = null,
    val recoveryFault: RuntimeFault.DurableRecovery? = null,
) {
    val schemaVersion: Int get() = 1
}
