package dev.ayuislands.studio.session

import dev.ayuislands.studio.model.DraftWriteIssue
import dev.ayuislands.studio.model.DraftWritePlan
import dev.ayuislands.studio.model.SessionAvailability
import dev.ayuislands.studio.model.StudioDocument
import dev.ayuislands.studio.model.StudioList
import dev.ayuislands.studio.model.StudioSessionRecord

internal object StudioJournalPolicy {
    fun save(
        latest: StudioDocument,
        record: StudioSessionRecord,
        transactionId: String,
    ): DraftWritePlan {
        try {
            StudioSessionValidation.validate(record)
        } catch (_: IllegalArgumentException) {
            return DraftWritePlan.Rejected(DraftWriteIssue.INVALID)
        }
        val previous = latest.sessions.singleOrNull { it.id == record.id }
        if (record.id.isBlank() || record.generation < 0 || record.baseDocumentRevision > latest.header.revision) {
            return DraftWritePlan.Rejected(DraftWriteIssue.INVALID)
        }
        if (previous != null) {
            compare(previous, record)?.let { return DraftWritePlan.Rejected(it) }
            if (previous == record) return DraftWritePlan.Unchanged(latest.header.revision)
        }
        if (transactionId.isBlank() || transactionId == latest.header.transactionId) {
            return DraftWritePlan.Rejected(DraftWriteIssue.INVALID)
        }
        val sessions =
            if (previous ==
                null
            ) {
                latest.sessions + record
            } else {
                latest.sessions.map { if (it.id == record.id) record else it }
            }
        return DraftWritePlan.Write(
            latest.copy(
                header = latest.header.copy(revision = latest.header.revision + 1, transactionId = transactionId),
                sessions = StudioList(sessions),
            ),
        )
    }

    private fun compare(
        previous: StudioSessionRecord,
        requested: StudioSessionRecord,
    ): DraftWriteIssue? {
        val isRetired =
            when (previous.availability) {
                SessionAvailability.LIVE -> false
                SessionAvailability.RECOVERED -> requested.availability == SessionAvailability.LIVE
                SessionAvailability.RETIRED -> requested.availability != SessionAvailability.RETIRED
            }
        return when {
            isRetired -> DraftWriteIssue.RETIRED
            previous.target != requested.target -> DraftWriteIssue.CONFLICT
            previous.generation > requested.generation -> DraftWriteIssue.STALE
            previous.generation == requested.generation && previous != requested -> DraftWriteIssue.CONFLICT
            else -> null
        }
    }
}
