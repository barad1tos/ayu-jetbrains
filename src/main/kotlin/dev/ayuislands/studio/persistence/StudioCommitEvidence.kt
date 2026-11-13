package dev.ayuislands.studio.persistence

internal data class PreparedToken(
    val transactionId: String,
    val baseRevision: Long,
    val baseDigest: String?,
    val targetRevision: Long,
)

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
