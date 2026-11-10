package dev.ayuislands.studio.persistence

import dev.ayuislands.rotation.RotationCandidate
import dev.ayuislands.rotation.RotationHandoffKind
import dev.ayuislands.studio.model.EffectLimitation
import dev.ayuislands.studio.model.EffectOwner
import dev.ayuislands.studio.model.RawValue
import dev.ayuislands.studio.model.StudioBytes
import dev.ayuislands.studio.model.StudioDocument
import dev.ayuislands.studio.model.StudioList
import dev.ayuislands.studio.model.StudioNativeChange
import dev.ayuislands.studio.model.StudioTarget
import java.util.UUID

internal data class RecoveryPlan(
    val target: StudioTarget,
    val owners: StudioList<EffectOwner>,
    val limitations: StudioList<EffectLimitation> = StudioList(emptyList()),
    val rotationStep: RotationCandidate? = null,
    val purpose: RecoveryPurpose = RecoveryPurpose.UserAcceptance,
)

/** Distinguishes automatic work without inferring meaning from opaque transaction identifiers. */
internal sealed interface RecoveryPurpose {
    data object UserAcceptance : RecoveryPurpose

    data object Metadata : RecoveryPurpose

    sealed interface Automatic : RecoveryPurpose {
        val operationId: UUID
    }

    data class RotationStep(
        override val operationId: UUID,
    ) : Automatic

    data class NativeChange(
        override val operationId: UUID,
        val change: StudioNativeChange,
    ) : Automatic

    data class ContextHandoff(
        override val operationId: UUID,
        val kind: RotationHandoffKind,
    ) : Automatic

    data class RotationSchedule(
        override val operationId: UUID,
    ) : Automatic
}

internal enum class RestoreStatus { PENDING, OWNED, RESTORED, FOREIGN, UNAVAILABLE, KEPT_AFTER_RESTART }

internal data class NativeFieldIdentity(
    val resource: String,
    val field: String,
    val ownerToken: String,
)

internal sealed interface NativeValue {
    data object Absent : NativeValue

    data class Present(
        val value: RawValue,
    ) : NativeValue
}

/** Portable before/planned-write pair; the named surface owner alone interprets and restores it. */
internal data class PortableReceipt(
    val id: String,
    val owner: EffectOwner,
    val identity: NativeFieldIdentity,
    val before: NativeValue,
    val lastWritten: NativeValue,
    val status: RestoreStatus,
)

internal data class PreparedToken(
    val transactionId: String,
    val baseRevision: Long,
    val baseDigest: String?,
    val targetRevision: Long,
)

internal data class PendingJournal(
    val token: PreparedToken,
    val base: StudioBytes?,
    val candidate: StudioDocument,
    val recovery: RecoveryPlan,
    val receipts: StudioList<PortableReceipt>,
)

internal enum class StoreIssue {
    IO,
    ATOMIC_MOVE_UNSUPPORTED,
    LOCKED,
    NOT_LOADED,
    INVALID_DATA,
    FUTURE_SCHEMA,
    STALE,
    BUSY,
    INVALID_CANDIDATE,
    CONFLICT,
    RECOVERY_REQUIRED,
    CLOSED,
}

internal sealed interface StoreLoad {
    data object Empty : StoreLoad

    data class Ready(
        val document: StudioDocument,
    ) : StoreLoad

    data class Recovering(
        val document: StudioDocument?,
        val journal: PendingJournal,
    ) : StoreLoad

    data class ReadOnly(
        val issue: StoreIssue,
        val document: StudioDocument? = null,
    ) : StoreLoad
}

internal sealed interface PrepareResult {
    data class Prepared(
        val token: PreparedToken,
    ) : PrepareResult

    data class Rejected(
        val issue: StoreIssue,
    ) : PrepareResult
}

internal sealed interface JournalResult {
    data object Acknowledged : JournalResult

    data class Rejected(
        val issue: StoreIssue,
    ) : JournalResult
}

internal sealed interface CommitResult {
    data class Committed(
        val document: StudioDocument,
    ) : CommitResult

    data class NotCommitted(
        val issue: StoreIssue,
    ) : CommitResult

    data class OutcomeUnknown(
        val issue: StoreIssue,
    ) : CommitResult
}

internal sealed interface CommitResolution {
    data object Committed : CommitResolution

    data object NotCommitted : CommitResolution

    data object Unknown : CommitResolution
}

internal sealed interface CommitEvidence {
    data object Absent : CommitEvidence

    data class Valid(
        val transactionId: String,
        val revision: Long,
        val digest: String,
    ) : CommitEvidence

    data object Unreadable : CommitEvidence
}

/** A failed IO call is deliberately absent: only verified bytes can decide acceptance. */
internal fun resolveCommit(
    token: PreparedToken,
    candidateDigest: String,
    evidence: CommitEvidence,
): CommitResolution =
    when (evidence) {
        CommitEvidence.Absent -> {
            if (token.baseRevision == -1L && token.baseDigest == null) {
                CommitResolution.NotCommitted
            } else {
                CommitResolution.Unknown
            }
        }

        CommitEvidence.Unreadable -> {
            CommitResolution.Unknown
        }

        is CommitEvidence.Valid -> {
            when {
                evidence.transactionId == token.transactionId &&
                    evidence.revision == token.targetRevision &&
                    evidence.digest == candidateDigest -> {
                    CommitResolution.Committed
                }

                evidence.revision == token.baseRevision && evidence.digest == token.baseDigest -> {
                    CommitResolution.NotCommitted
                }

                else -> {
                    CommitResolution.Unknown
                }
            }
        }
    }
