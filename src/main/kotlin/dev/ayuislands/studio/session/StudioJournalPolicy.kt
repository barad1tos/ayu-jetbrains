package dev.ayuislands.studio.session

import dev.ayuislands.studio.model.DraftWriteIssue
import dev.ayuislands.studio.model.DraftWritePlan
import dev.ayuislands.studio.model.SessionAvailability
import dev.ayuislands.studio.model.StudioDocument
import dev.ayuislands.studio.model.StudioList
import dev.ayuislands.studio.model.StudioSessionRecord

internal object StudioJournalPolicy {
    /** Only the acknowledged record may enter Apply; catalog name and link reconciliation also applies on save. */
    fun acknowledgedDraft(
        latest: StudioDocument,
        request: StudioSessionRecord,
    ): StudioSessionRecord? {
        val saved = latest.sessions.singleOrNull { it.id == request.id }
        return saved?.takeIf {
            it.availability == SessionAvailability.LIVE && it == reconcileCatalog(latest, request, saved)
        }
    }

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
        val reconciled = reconcileCatalog(latest, record, previous)
        if (previous != null) {
            compare(previous, reconciled)?.let { return DraftWritePlan.Rejected(it) }
            if (previous == reconciled) return DraftWritePlan.Unchanged(latest.header.revision)
        }
        if (transactionId.isBlank() || transactionId == latest.header.transactionId) {
            return DraftWritePlan.Rejected(DraftWriteIssue.INVALID)
        }
        val sessions =
            if (previous ==
                null
            ) {
                latest.sessions + reconciled
            } else {
                latest.sessions.map { if (it.id == record.id) reconciled else it }
            }
        return DraftWritePlan.Write(
            latest.copy(
                header = latest.header.copy(revision = latest.header.revision + 1, transactionId = transactionId),
                sessions = StudioList(sessions),
            ),
        )
    }

    private fun reconcileCatalog(
        latest: StudioDocument,
        record: StudioSessionRecord,
        previous: StudioSessionRecord?,
    ): StudioSessionRecord {
        if (previous?.availability == SessionAvailability.RETIRED) return record
        val work = StudioCatalogPolicy.rebaseWork(latest, record.work)
        val linkedIds =
            record.work.drafts
                .filter { it.source != null }
                .map { it.id }
                .toSet()
        val detachedNames =
            previous?.work?.drafts.orEmpty().filter { it.source == null }.associate {
                it.id to
                    it.sourceName
            }
        val drafts =
            work.drafts.map { draft ->
                val retainedName = detachedNames[draft.id]
                if (draft.source == null && draft.id in linkedIds && retainedName != null) {
                    draft.copy(sourceName = retainedName)
                } else {
                    draft
                }
            }
        return record.copy(work = work.copy(drafts = StudioList(drafts)))
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
