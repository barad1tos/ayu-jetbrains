package dev.ayuislands.studio.model

internal enum class DraftWriteIssue { STALE, RETIRED, CONFLICT, INVALID, MISSING, TARGET_UNAVAILABLE }

internal sealed interface DraftWritePlan {
    data class Write(
        val candidate: StudioDocument,
    ) : DraftWritePlan

    data class Unchanged(
        val revision: Long,
    ) : DraftWritePlan

    data class Rejected(
        val issue: DraftWriteIssue,
    ) : DraftWritePlan
}

internal data class DraftRestore(
    val source: String,
    val session: String,
    val transaction: String,
    val target: TargetObservation,
)

internal sealed interface DraftWriteResult {
    data class Saved(
        val revision: Long,
    ) : DraftWriteResult

    data class Failed(
        val code: String,
    ) : DraftWriteResult
}
