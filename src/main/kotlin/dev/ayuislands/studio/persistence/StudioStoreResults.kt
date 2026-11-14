package dev.ayuislands.studio.persistence

import dev.ayuislands.studio.model.StudioDocument

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
        val journal: PendingJournal,
    ) : PrepareResult {
        val token: PreparedToken get() = journal.token
    }

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
