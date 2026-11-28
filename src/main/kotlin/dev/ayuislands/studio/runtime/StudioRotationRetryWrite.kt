package dev.ayuislands.studio.runtime

import dev.ayuislands.studio.persistence.CommitResult
import dev.ayuislands.studio.persistence.JournalResult
import dev.ayuislands.studio.persistence.PreparedToken
import dev.ayuislands.studio.persistence.StoreLoad
import dev.ayuislands.studio.persistence.StudioAppearanceStore
import dev.ayuislands.studio.persistence.hasRotationRetry
import dev.ayuislands.studio.session.ApplySettlement
import dev.ayuislands.studio.session.SettlementProblem

/** Publish retry metadata after the native rollback and release have settled. */
internal fun commitRotationRetry(
    store: StudioAppearanceStore,
    token: PreparedToken,
    admitRetry: Boolean,
    restoredAt: () -> Long,
): ApplySettlement {
    val observed = store.load()
    val journal = (observed as? StoreLoad.Recovering)?.journal
    if (journal?.token != token) return unconfirmedRetry()
    val prepared =
        if (journal.hasRotationRetry()) {
            journal
        } else {
            if (!admitRetry) return ApplySettlement.NotAccepted
            when (val result = store.recordRotationRetry(token, restoredAt())) {
                is JournalResult.Rejected ->
                    return ApplySettlement.RecoveryRequired(SettlementProblem.Journal(result.issue))
                JournalResult.Acknowledged ->
                    (store.load() as? StoreLoad.Recovering)?.journal ?: return unconfirmedRetry()
            }
        }
    if (prepared.token != token || !prepared.hasRotationRetry()) return unconfirmedRetry()
    return when (val result = store.commit(token, prepared.candidate.runtime)) {
        is CommitResult.Committed ->
            if (result.document == prepared.candidate) {
                ApplySettlement.Accepted(result.document)
            } else {
                unconfirmedRetry()
            }
        is CommitResult.NotCommitted -> ApplySettlement.RecoveryRequired(SettlementProblem.Journal(result.issue))
        is CommitResult.OutcomeUnknown -> ApplySettlement.RecoveryRequired(SettlementProblem.Journal(result.issue))
    }
}

private fun unconfirmedRetry(): ApplySettlement = ApplySettlement.RecoveryRequired(SettlementProblem.Unconfirmed)
