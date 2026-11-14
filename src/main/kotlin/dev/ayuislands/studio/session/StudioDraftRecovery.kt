package dev.ayuislands.studio.session

import dev.ayuislands.studio.model.DraftRestore
import dev.ayuislands.studio.model.DraftWriteIssue
import dev.ayuislands.studio.model.DraftWritePlan
import dev.ayuislands.studio.model.SessionAvailability
import dev.ayuislands.studio.model.StudioDocument
import dev.ayuislands.studio.model.StudioList
import dev.ayuislands.studio.model.StudioSessionRecord
import dev.ayuislands.studio.model.TargetPresence

internal object StudioDraftRecovery {
    // A crash can leave a persisted LIVE record without a registered owner.
    fun offers(
        document: StudioDocument,
        attached: Set<String>,
    ): List<StudioSessionRecord> =
        document.sessions.filter { it.id !in attached && it.availability != SessionAvailability.RETIRED }

    fun restore(
        document: StudioDocument,
        request: DraftRestore,
        attached: Set<String>,
    ): DraftWritePlan {
        val source =
            document.sessions.singleOrNull { it.id == request.source }
                ?: return DraftWritePlan.Rejected(DraftWriteIssue.MISSING)
        if (source.id in attached ||
            request.session in attached ||
            document.sessions.any { it.id == request.session }
        ) {
            return DraftWritePlan.Rejected(DraftWriteIssue.CONFLICT)
        }
        if (source.availability == SessionAvailability.RETIRED) return DraftWritePlan.Rejected(DraftWriteIssue.RETIRED)
        if (request.target.target != source.target ||
            request.target.presence == TargetPresence.UNAVAILABLE ||
            StudioTargets.current(document, source.target) == null
        ) {
            return DraftWritePlan.Rejected(DraftWriteIssue.TARGET_UNAVAILABLE)
        }
        val restored = source.copy(id = request.session, generation = 0, availability = SessionAvailability.LIVE)
        val plan = StudioJournalPolicy.save(document, restored, request.transaction)
        if (plan !is DraftWritePlan.Write) return plan
        val retired = source.copy(generation = source.generation + 1, availability = SessionAvailability.RETIRED)
        return plan.copy(
            candidate =
                plan.candidate.copy(
                    sessions = StudioList(plan.candidate.sessions.map { if (it.id == source.id) retired else it }),
                ),
        )
    }

    fun discard(
        document: StudioDocument,
        source: String,
        transactionId: String,
        attached: Set<String>,
    ): DraftWritePlan {
        val record =
            document.sessions.singleOrNull { it.id == source }
                ?: return DraftWritePlan.Rejected(DraftWriteIssue.MISSING)
        if (record.id in attached) return DraftWritePlan.Rejected(DraftWriteIssue.CONFLICT)
        if (record.availability ==
            SessionAvailability.RETIRED
        ) {
            return DraftWritePlan.Unchanged(document.header.revision)
        }
        val retired = record.copy(generation = record.generation + 1, availability = SessionAvailability.RETIRED)
        return StudioJournalPolicy.save(document, retired, transactionId)
    }
}
