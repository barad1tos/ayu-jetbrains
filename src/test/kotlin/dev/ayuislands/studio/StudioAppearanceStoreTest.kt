package dev.ayuislands.studio

import dev.ayuislands.studio.migration.MigrationResult
import dev.ayuislands.studio.migration.StudioLegacyMigration
import dev.ayuislands.studio.model.AcceptanceObservation
import dev.ayuislands.studio.model.AcceptancePlan
import dev.ayuislands.studio.model.ApplyContext
import dev.ayuislands.studio.model.ApplyRequest
import dev.ayuislands.studio.model.AuthorityStatus
import dev.ayuislands.studio.model.DraftWriteResult
import dev.ayuislands.studio.model.EffectLimitation
import dev.ayuislands.studio.model.EffectObservation
import dev.ayuislands.studio.model.EffectOwner
import dev.ayuislands.studio.model.LegacyArchive
import dev.ayuislands.studio.model.LegacyFile
import dev.ayuislands.studio.model.LegacyStore
import dev.ayuislands.studio.model.LimitationReason
import dev.ayuislands.studio.model.MigrationSource
import dev.ayuislands.studio.model.RawValue
import dev.ayuislands.studio.model.StudioDocument
import dev.ayuislands.studio.model.StudioList
import dev.ayuislands.studio.model.StudioProjectKey
import dev.ayuislands.studio.model.StudioProjectRecord
import dev.ayuislands.studio.model.StudioSessionRecord
import dev.ayuislands.studio.model.StudioTarget
import dev.ayuislands.studio.model.TargetObservation
import dev.ayuislands.studio.model.TargetPresence
import dev.ayuislands.studio.persistence.CommitResolution
import dev.ayuislands.studio.persistence.CommitResult
import dev.ayuislands.studio.persistence.JournalResult
import dev.ayuislands.studio.persistence.LegacyCapture
import dev.ayuislands.studio.persistence.NativeFieldIdentity
import dev.ayuislands.studio.persistence.NativeValue
import dev.ayuislands.studio.persistence.PortableReceipt
import dev.ayuislands.studio.persistence.PrepareResult
import dev.ayuislands.studio.persistence.PreparedToken
import dev.ayuislands.studio.persistence.RecoveryPlan
import dev.ayuislands.studio.persistence.RecoveryPurpose
import dev.ayuislands.studio.persistence.RestoreStatus
import dev.ayuislands.studio.persistence.StoreIssue
import dev.ayuislands.studio.persistence.StoreLoad
import dev.ayuislands.studio.persistence.StudioAppearanceStore
import dev.ayuislands.studio.persistence.StudioDraftStore
import dev.ayuislands.studio.persistence.StudioFiles
import dev.ayuislands.studio.persistence.StudioLegacyCapture
import dev.ayuislands.studio.persistence.openStudioFiles
import dev.ayuislands.studio.session.StudioAcceptancePlanner
import dev.ayuislands.studio.session.StudioApplyCandidate
import org.junit.jupiter.api.io.TempDir
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull

class StudioAppearanceStoreTest {
    @TempDir
    lateinit var directory: Path

    @Test
    fun `prepared project Apply is recoverable before commit and reopens with its accepted draft baseline`() {
        val initial = catalogTestDocument()
        val project = StudioProjectRecord(StudioProjectKey("/ayu"), "Ayu", initial.currents.global)
        val document = initial.copy(currents = initial.currents.copy(projects = StudioList(listOf(project))))
        val edit = editedJournalRecord(document).copy(target = StudioTarget.Project(project.key))
        val advisory = EffectLimitation(EffectOwner.GLOW, LimitationReason.PRO_REQUIRED, blocksEffect = false)
        val accepted =
            open().use { store ->
                initialize(store, document)
                assertIs<DraftWriteResult.Saved>(StudioDraftStore(store).save(edit, "edited-project"))
                val latest = assertIs<StoreLoad.Ready>(store.load()).document
                val plan = applyPlan(latest, edit, EffectObservation(1, limitations = StudioList(listOf(advisory))))
                val prepared = assertIs<PrepareResult.Prepared>(store.prepare(plan))
                val pending = assertIs<StoreLoad.Recovering>(store.load())
                assertEquals(latest, pending.document)
                assertEquals(prepared.journal, pending.journal)
                assertEquals(edit.target, prepared.journal.recovery.target)
                assertEquals(RecoveryPurpose.UserAcceptance, prepared.journal.recovery.purpose)
                assertEquals(listOf(EffectOwner.GLOW), prepared.journal.recovery.owners)
                assertEquals(listOf(advisory), prepared.journal.recovery.limitations)
                assertEquals(CommitResolution.NotCommitted, store.resolveOutcome(prepared.token))
                assertEquals(CommitResult.Committed(plan.document), store.commit(prepared.token, plan.document.runtime))
                prepared.journal
            }
        open().use { reopened ->
            val loaded = assertIs<StoreLoad.Recovering>(reopened.load())
            assertEquals(CommitResolution.Committed, reopened.resolveOutcome(accepted.token))
            val persisted = requireNotNull(loaded.document)
            val selected = persisted.currents.projects.single()
            val draft = persisted.sessions.single()
            assertEquals(document.currents.global, persisted.currents.global)
            assertEquals(
                edit.work.drafts
                    .single()
                    .appearance.glow,
                selected.current.appearance.glow,
            )
            assertEquals(
                selected.current,
                draft.work.drafts
                    .single()
                    .base,
            )
            assertFalse(StudioApplyCandidate.hasPending(draft.work))
            assertEquals(JournalResult.Acknowledged, reopened.finishAccepted(accepted.token))
            assertEquals(StoreLoad.Ready(persisted), reopened.load())
        }
    }

    @Test
    fun `a prepared acceptance cannot replace a newer durable draft generation`() {
        val initial = catalogTestDocument()
        val edit = editedJournalRecord(initial)
        open().use { store ->
            initialize(store, initial)
            val plan = applyPlan(initial, edit)
            val newer = edit.copy(generation = edit.generation + 1)
            assertIs<DraftWriteResult.Saved>(StudioDraftStore(store).save(newer, "newer-draft"))
            val latest = assertIs<StoreLoad.Ready>(store.load())
            assertEquals(PrepareResult.Rejected(StoreIssue.STALE), store.prepare(plan))
            assertEquals(latest, store.load())
            assertEquals(newer, latest.document.sessions.single())
        }
    }

    @Test
    fun `lost prepared Apply acknowledgement leaves recoverable work but does not accept it`() {
        val document = catalogTestDocument()
        val files = openStudioFiles(directory)
        var losePrepare = false
        val fault =
            object : StudioFiles by files {
                override fun replace(
                    source: String,
                    destination: String,
                ) {
                    files.replace(source, destination)
                    if (losePrepare && destination == "pending.xml") {
                        losePrepare = false
                        throw IOException("Lost Apply journal acknowledgement")
                    }
                }
            }
        StudioAppearanceStore(fault).use { store ->
            initialize(store, document)
            val edit = editedJournalRecord(document)
            assertIs<DraftWriteResult.Saved>(StudioDraftStore(store).save(edit, "edited"))
            val latest = assertIs<StoreLoad.Ready>(store.load()).document
            val plan = applyPlan(latest, edit)
            losePrepare = true
            assertEquals(PrepareResult.Rejected(StoreIssue.IO), store.prepare(plan))
            val pending = assertIs<StoreLoad.Recovering>(store.load())
            assertEquals(latest, pending.document)
            assertEquals(plan.document, pending.journal.candidate)
            assertEquals(CommitResolution.NotCommitted, store.resolveOutcome(pending.journal.token))
            assertEquals(JournalResult.Acknowledged, store.finishRollback(pending.journal.token))
            assertEquals(StoreLoad.Ready(latest), store.load())
        }
    }

    @Test
    fun `prepare keeps current untouched and accepted state survives reopen before cleanup`() {
        val candidate = document()
        val token =
            open().use { store ->
                val prepared = prepareFirst(store, candidate)
                val pending = assertIs<StoreLoad.Recovering>(store.load())
                assertNull(pending.document)
                assertEquals(prepared, pending.journal.token)
                assertEquals(CommitResult.Committed(candidate), store.commit(prepared, candidate.runtime))
                prepared
            }
        open().use { reopened ->
            val recovering = assertIs<StoreLoad.Recovering>(reopened.load())
            assertEquals(candidate, recovering.document)
            assertEquals(token, recovering.journal.token)
            assertEquals(CommitResolution.Committed, reopened.resolveOutcome(token))
            assertEquals(JournalResult.Rejected(StoreIssue.RECOVERY_REQUIRED), reopened.finishRollback(token))
            assertEquals(JournalResult.Acknowledged, reopened.finishAccepted(token))
            assertEquals(StoreLoad.Ready(candidate), reopened.load())
        }
    }

    @Test
    fun `a stale or competing writer cannot replace accepted settings`() {
        val candidate = document()
        open().use { owner ->
            val token = prepareFirst(owner, candidate)
            assertEquals(CommitResult.Committed(candidate), owner.commit(token, candidate.runtime))
            assertEquals(JournalResult.Acknowledged, owner.finishAccepted(token))
            val bytes = Files.readAllBytes(directory.resolve("state.xml"))
            val next = candidate.copy(header = candidate.header.copy(revision = 1, transactionId = "next"))
            open().use { contender ->
                assertEquals(StoreLoad.ReadOnly(StoreIssue.LOCKED, candidate), contender.load())
                assertEquals(PrepareResult.Rejected(StoreIssue.LOCKED), contender.prepare(0, next, recovery()))
            }
            assertEquals(PrepareResult.Rejected(StoreIssue.STALE), owner.prepare(-1, next, recovery()))
            assertContentEquals(bytes, Files.readAllBytes(directory.resolve("state.xml")))
        }
    }

    @Test
    fun `read only recovery is distinct from a fresh store even if only a backup remains`() {
        val foreign = byteArrayOf(0, -1, 3)
        Files.write(directory.resolve("state.previous.xml"), foreign)
        open().use { store ->
            assertEquals(StoreLoad.ReadOnly(StoreIssue.INVALID_DATA), store.load())
            assertEquals(
                JournalResult.Rejected(StoreIssue.INVALID_DATA),
                store.preserveSource(document().header.migration),
            )
            assertContentEquals(foreign, Files.readAllBytes(directory.resolve("state.previous.xml")))
        }
    }

    @Test
    fun `a lost commit acknowledgement is resolved using the published generation`() {
        val candidate = document()
        val files = openStudioFiles(directory)
        var failRead = false
        val fault =
            object : StudioFiles by files {
                override fun replace(
                    source: String,
                    destination: String,
                ) {
                    files.replace(source, destination)
                    if (destination == "state.xml") {
                        failRead = true
                        throw IOException("Lost publication acknowledgement")
                    }
                }

                override fun read(name: String): ByteArray? {
                    if (name == "state.xml" && failRead) {
                        failRead = false
                        throw IOException("Publication evidence is unavailable")
                    }
                    return files.read(name)
                }
            }
        StudioAppearanceStore(fault).use { store ->
            val token = prepareFirst(store, candidate)
            assertIs<CommitResult.OutcomeUnknown>(store.commit(token, candidate.runtime))
            assertEquals(CommitResolution.Committed, store.resolveOutcome(token))
            assertEquals(JournalResult.Rejected(StoreIssue.RECOVERY_REQUIRED), store.finishRollback(token))
            assertEquals(JournalResult.Acknowledged, store.finishAccepted(token))
        }
    }

    @Test
    fun `unsupported atomic replacement is known not committed and can be rolled back`() {
        val candidate = document()
        val files = openStudioFiles(directory)
        val fault =
            object : StudioFiles by files {
                override fun replace(
                    source: String,
                    destination: String,
                ) {
                    if (destination == "state.xml") {
                        throw AtomicMoveNotSupportedException(source, destination, "No atomic replacement")
                    }
                    files.replace(source, destination)
                }
            }
        StudioAppearanceStore(fault).use { store ->
            val token = prepareFirst(store, candidate)
            assertEquals(
                CommitResult.NotCommitted(StoreIssue.ATOMIC_MOVE_UNSUPPORTED),
                store.commit(token, candidate.runtime),
            )
            assertEquals(CommitResolution.NotCommitted, store.resolveOutcome(token))
            assertEquals(JournalResult.Acknowledged, store.finishRollback(token))
            assertEquals(StoreLoad.Empty, store.load())
        }
    }

    @Test
    fun `rollback retains portable receipt ownership until resolution even across reopen`() {
        val candidate = document()
        val receipt =
            PortableReceipt(
                "font:size",
                EffectOwner.FONT,
                NativeFieldIdentity("editor", "size", "scheme"),
                NativeValue.Present(RawValue.Integer(13)),
                NativeValue.Present(RawValue.Integer(15)),
                RestoreStatus.OWNED,
            )
        val token =
            open().use { store ->
                val prepared = prepareFirst(store, candidate, StudioList(listOf(EffectOwner.FONT)))
                assertEquals(JournalResult.Acknowledged, store.recordRecovery(prepared, listOf(receipt)))
                prepared
            }
        open().use { store ->
            val recovery = assertIs<StoreLoad.Recovering>(store.load())
            assertEquals(listOf(receipt), recovery.journal.receipts)
            assertEquals(JournalResult.Rejected(StoreIssue.CONFLICT), store.recordRecovery(token, emptyList()))
            assertEquals(JournalResult.Rejected(StoreIssue.RECOVERY_REQUIRED), store.finishRollback(token))
            val restored = receipt.copy(status = RestoreStatus.RESTORED)
            assertEquals(JournalResult.Acknowledged, store.recordRecovery(token, listOf(restored)))
            assertEquals(JournalResult.Acknowledged, store.finishRollback(token))
            assertEquals(JournalResult.Acknowledged, store.finishRollback(token))
        }
    }

    @Test
    fun `cleanup retries cannot remove a later transaction`() {
        val candidate = document()
        val files = openStudioFiles(directory)
        var loseAcknowledgement = true
        val fault =
            object : StudioFiles by files {
                override fun remove(name: String) {
                    files.remove(name)
                    if (name == "pending.xml" && loseAcknowledgement) {
                        loseAcknowledgement = false
                        throw IOException("Lost cleanup acknowledgement")
                    }
                }
            }
        StudioAppearanceStore(fault).use { store ->
            val token = prepareFirst(store, candidate)
            assertEquals(candidate.header.migration, store.findSource(candidate.header.migration.archives))
            assertIs<CommitResult.Committed>(store.commit(token, candidate.runtime))
            assertEquals(JournalResult.Rejected(StoreIssue.IO), store.finishAccepted(token))
            assertEquals(JournalResult.Acknowledged, store.finishAccepted(token))
            val next = candidate.copy(header = candidate.header.copy(revision = 1, transactionId = "next"))
            val prepared = assertIs<PrepareResult.Prepared>(store.prepare(0, next, recovery())).token
            assertEquals(PrepareResult.Rejected(StoreIssue.BUSY), store.prepare(0, next, recovery()))
            assertEquals(JournalResult.Rejected(StoreIssue.CONFLICT), store.finishAccepted(token))
            assertEquals(prepared, assertIs<StoreLoad.Recovering>(store.load()).journal.token)
        }
    }

    @Test
    fun `a backup that appears before initial commit prevents treating missing state as known empty`() {
        val candidate = document()
        open().use { store ->
            val token = prepareFirst(store, candidate)
            Files.write(directory.resolve("state.previous.xml"), byteArrayOf(1))
            assertEquals(CommitResolution.Unknown, store.resolveOutcome(token))
            assertIs<CommitResult.OutcomeUnknown>(store.commit(token, candidate.runtime))
            assertEquals(JournalResult.Rejected(StoreIssue.RECOVERY_REQUIRED), store.finishRollback(token))
        }
    }

    private fun open(): StudioAppearanceStore = StudioAppearanceStore(openStudioFiles(directory))

    private fun initialize(
        store: StudioAppearanceStore,
        document: StudioDocument,
    ) {
        val token = prepareFirst(store, document)
        assertEquals(CommitResult.Committed(document), store.commit(token, document.runtime))
        assertEquals(JournalResult.Acknowledged, store.finishAccepted(token))
    }

    private fun applyPlan(
        latest: StudioDocument,
        edit: StudioSessionRecord,
        effects: EffectObservation = EffectObservation(1),
    ): AcceptancePlan.Commit =
        assertIs<AcceptancePlan.Commit>(
            StudioAcceptancePlanner.plan(
                ApplyRequest(edit, 1),
                latest,
                ApplyContext(
                    AcceptanceObservation(
                        AuthorityStatus.READY,
                        TargetObservation(edit.target, 1, TargetPresence.ACTIVE),
                    ),
                    StudioList(emptyList()),
                ),
                effects,
                UUID.fromString("9e1bbb9a-7376-451d-9f0f-4897c1142add"),
            ),
        )

    private fun recovery(owners: StudioList<EffectOwner> = StudioList(emptyList())): RecoveryPlan =
        RecoveryPlan(StudioTarget.Global, owners)

    private fun prepareFirst(
        store: StudioAppearanceStore,
        candidate: StudioDocument,
        owners: StudioList<EffectOwner> = StudioList(emptyList()),
    ): PreparedToken {
        assertEquals(StoreLoad.Empty, store.load())
        assertEquals(JournalResult.Acknowledged, store.preserveSource(candidate.header.migration))
        return assertIs<PrepareResult.Prepared>(store.prepare(-1, candidate, recovery(owners))).token
    }

    private fun document(): StudioDocument {
        val source =
            MigrationSource("archive", StudioList(LegacyStore.entries.map { LegacyArchive(it, LegacyFile.Absent) }))
        val capture = assertIs<LegacyCapture.Ready>(StudioLegacyCapture.read(source, "initial", "/work"))
        return assertIs<MigrationResult.Ready>(StudioLegacyMigration.plan(capture.snapshot)).document
    }
}
