package dev.ayuislands.studio

import dev.ayuislands.studio.migration.LegacySnapshot
import dev.ayuislands.studio.migration.MigrationResult
import dev.ayuislands.studio.migration.StudioLegacyMigration
import dev.ayuislands.studio.model.EffectOwner
import dev.ayuislands.studio.model.MigrationSource
import dev.ayuislands.studio.model.StudioDocument
import dev.ayuislands.studio.model.StudioList
import dev.ayuislands.studio.model.StudioTarget
import dev.ayuislands.studio.persistence.CommitResolution
import dev.ayuislands.studio.persistence.CommitResult
import dev.ayuislands.studio.persistence.JournalResult
import dev.ayuislands.studio.persistence.LegacyCapture
import dev.ayuislands.studio.persistence.LegacyCaptureIssue
import dev.ayuislands.studio.persistence.PrepareResult
import dev.ayuislands.studio.persistence.RecoveryPlan
import dev.ayuislands.studio.persistence.RecoveryPurpose
import dev.ayuislands.studio.persistence.StoreDataException
import dev.ayuislands.studio.persistence.StoreIssue
import dev.ayuislands.studio.persistence.StoreLoad
import dev.ayuislands.studio.persistence.StudioAppearanceStore
import dev.ayuislands.studio.persistence.StudioLegacyCapture
import dev.ayuislands.studio.persistence.readLegacyFiles
import java.io.IOException
import java.nio.file.Path

internal sealed interface StudioBootstrapResult {
    data class Ready(
        val document: StudioDocument,
    ) : StudioBootstrapResult

    data class Recovering(
        val state: StoreLoad.Recovering,
    ) : StudioBootstrapResult

    data class ReadOnly(
        val state: StoreLoad.ReadOnly,
    ) : StudioBootstrapResult

    /** The first write was rolled back. This candidate can be shown read-only while awaiting Retry. */
    data class LegacyReadOnlyRecovery(
        val document: StudioDocument,
        val issue: StoreIssue?,
    ) : StudioBootstrapResult
}

/** Opens preference authority before runtime activation. The caller serializes loading off the UI thread. */
internal class StudioBootstrap(
    private val store: StudioAppearanceStore,
    private val optionsDirectory: Path,
    private val userHome: String,
) {
    fun load(transactionId: String): StudioBootstrapResult =
        when (val loaded = store.load()) {
            StoreLoad.Empty -> migrate(transactionId)
            is StoreLoad.Ready -> StudioBootstrapResult.Ready(loaded.document)
            is StoreLoad.Recovering ->
                when (loaded.journal.recovery.purpose) {
                    is RecoveryPurpose.RotationSchedule, is RecoveryPurpose.RotationStep -> recoverRotation(loaded)
                    else -> recoverInitial(loaded)
                }
            is StoreLoad.ReadOnly -> StudioBootstrapResult.ReadOnly(loaded)
        }

    private fun migrate(transactionId: String): StudioBootstrapResult {
        val source =
            try {
                val archives = readLegacyFiles(optionsDirectory)
                store.findSource(archives) ?: MigrationSource(transactionId, archives)
            } catch (failure: IOException) {
                return readOnly((failure as? StoreDataException)?.issue ?: StoreIssue.IO)
            }
        val archived = store.preserveSource(source)
        if (archived is JournalResult.Rejected) return readOnly(archived.issue)

        return when (val captured = StudioLegacyCapture.read(source, transactionId, userHome)) {
            is LegacyCapture.Ready -> acceptMigration(captured.snapshot)
            is LegacyCapture.Blocked -> {
                val issue =
                    when (captured.issue) {
                        LegacyCaptureIssue.INVALID_DATA -> StoreIssue.INVALID_DATA
                        LegacyCaptureIssue.PATH_CONFLICT -> StoreIssue.CONFLICT
                    }
                readOnly(issue)
            }
        }
    }

    private fun acceptMigration(snapshot: LegacySnapshot): StudioBootstrapResult {
        val planned = StudioLegacyMigration.plan(snapshot)
        if (planned !is MigrationResult.Ready) return readOnly(StoreIssue.INVALID_CANDIDATE)
        return when (val prepared = store.prepare(EMPTY_REVISION, planned.document, initialRecovery)) {
            is PrepareResult.Prepared -> commitInitial(StoreLoad.Recovering(null, prepared.journal))
            is PrepareResult.Rejected -> observeRejectedPreparation(prepared.issue)
        }
    }

    private fun observeRejectedPreparation(issue: StoreIssue): StudioBootstrapResult =
        when (val loaded = store.load()) {
            StoreLoad.Empty -> readOnly(issue)
            is StoreLoad.Ready -> StudioBootstrapResult.Ready(loaded.document)
            is StoreLoad.Recovering -> recoverInitial(loaded, issue)
            is StoreLoad.ReadOnly -> StudioBootstrapResult.ReadOnly(loaded)
        }

    private fun commitInitial(state: StoreLoad.Recovering): StudioBootstrapResult {
        val journal = state.journal
        return when (val committed = store.commit(journal.token, journal.candidate.runtime)) {
            is CommitResult.Committed -> finishAccepted(state.copy(document = committed.document))
            is CommitResult.NotCommitted -> finishRollback(state, committed.issue)
            is CommitResult.OutcomeUnknown -> StudioBootstrapResult.Recovering(state)
        }
    }

    private fun recoverInitial(
        state: StoreLoad.Recovering,
        prepareIssue: StoreIssue? = null,
    ): StudioBootstrapResult {
        val journal = state.journal
        if (journal.token.baseRevision != EMPTY_REVISION ||
            journal.recovery != initialRecovery ||
            journal.receipts.isNotEmpty()
        ) {
            return StudioBootstrapResult.Recovering(state)
        }
        return when (store.resolveOutcome(journal.token)) {
            CommitResolution.Committed -> finishAccepted(state)
            CommitResolution.NotCommitted -> finishRollback(state, prepareIssue)
            CommitResolution.Unknown -> StudioBootstrapResult.Recovering(state)
        }
    }

    private fun recoverRotation(state: StoreLoad.Recovering): StudioBootstrapResult {
        val journal = state.journal
        val document = state.document ?: return StudioBootstrapResult.Recovering(state)
        // Native writes require an acknowledged receipt. Leave those journals to the effect owner.
        if (journal.receipts.isNotEmpty() || !canRecoverRotation(journal.recovery)) {
            return StudioBootstrapResult.Recovering(state)
        }
        val result =
            when (store.resolveOutcome(journal.token)) {
                CommitResolution.Committed -> {
                    if (document != journal.candidate) return StudioBootstrapResult.Recovering(state)
                    store.finishAccepted(journal.token)
                }
                CommitResolution.NotCommitted -> store.finishRollback(journal.token)
                CommitResolution.Unknown -> return StudioBootstrapResult.Recovering(state)
            }
        return when (result) {
            JournalResult.Acknowledged -> StudioBootstrapResult.Ready(document)
            is JournalResult.Rejected -> StudioBootstrapResult.Recovering(state)
        }
    }

    private fun canRecoverRotation(plan: RecoveryPlan): Boolean =
        when (plan.purpose) {
            is RecoveryPurpose.RotationSchedule -> plan.owners.isEmpty() && plan.rotationStep == null
            is RecoveryPurpose.RotationStep ->
                plan.owners.singleOrNull() == EffectOwner.ACCENT &&
                    plan.rotationStep != null
            else -> false
        }

    private fun finishAccepted(state: StoreLoad.Recovering): StudioBootstrapResult =
        when (store.finishAccepted(state.journal.token)) {
            JournalResult.Acknowledged -> StudioBootstrapResult.Ready(state.journal.candidate)
            is JournalResult.Rejected -> StudioBootstrapResult.Recovering(state)
        }

    private fun finishRollback(
        state: StoreLoad.Recovering,
        issue: StoreIssue?,
    ): StudioBootstrapResult =
        when (store.finishRollback(state.journal.token)) {
            JournalResult.Acknowledged -> StudioBootstrapResult.LegacyReadOnlyRecovery(state.journal.candidate, issue)
            is JournalResult.Rejected -> StudioBootstrapResult.Recovering(state)
        }

    private fun readOnly(issue: StoreIssue): StudioBootstrapResult.ReadOnly =
        StudioBootstrapResult.ReadOnly(StoreLoad.ReadOnly(issue))

    private companion object {
        const val EMPTY_REVISION = -1L
        val initialRecovery = RecoveryPlan(StudioTarget.Global, StudioList(emptyList()))
    }
}
