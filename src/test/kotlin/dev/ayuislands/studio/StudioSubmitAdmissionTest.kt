package dev.ayuislands.studio

import dev.ayuislands.studio.model.AcceptanceBlock
import dev.ayuislands.studio.model.AcceptanceObservation
import dev.ayuislands.studio.model.AcceptancePlan
import dev.ayuislands.studio.model.ApplyContext
import dev.ayuislands.studio.model.ApplyRequest
import dev.ayuislands.studio.model.AuthorityStatus
import dev.ayuislands.studio.model.DraftWriteResult
import dev.ayuislands.studio.model.EffectObservation
import dev.ayuislands.studio.model.StudioDocument
import dev.ayuislands.studio.model.StudioList
import dev.ayuislands.studio.model.StudioSessionRecord
import dev.ayuislands.studio.model.StudioTarget
import dev.ayuislands.studio.model.TargetObservation
import dev.ayuislands.studio.model.TargetPresence
import dev.ayuislands.studio.persistence.CommitResult
import dev.ayuislands.studio.persistence.JournalResult
import dev.ayuislands.studio.persistence.PrepareResult
import dev.ayuislands.studio.persistence.RecoveryPlan
import dev.ayuislands.studio.persistence.StoreIssue
import dev.ayuislands.studio.persistence.StoreLoad
import dev.ayuislands.studio.persistence.StudioAppearanceStore
import dev.ayuislands.studio.persistence.StudioDraftStore
import dev.ayuislands.studio.persistence.openStudioFiles
import dev.ayuislands.studio.session.ApplyInputs
import dev.ayuislands.studio.session.StudioSubmitAdmission
import dev.ayuislands.studio.session.Submission
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class StudioSubmitAdmissionTest {
    @TempDir
    lateinit var directory: Path

    @Test
    fun `flush can take the shared queue and the confirmed plan uses the newer document`() {
        val initial = catalogTestDocument()
        val edit = editedJournalRecord(initial)
        open(initial).use { store ->
            val queue = Mutex()
            val drafts = StudioDraftStore(store)
            val admission =
                StudioSubmitAdmission(store, queue, { record ->
                    queue.withLock {
                        val saved = assertIs<DraftWriteResult.Saved>(drafts.save(record, "draft"))
                        assertIs<DraftWriteResult.Saved>(drafts.save(edit.copy(id = "other"), "other-draft"))
                        saved
                    }
                }, { request, latest ->
                    assertEquals(edit, request.session)
                    assertTrue(queue.isLocked)
                    assertEquals(listOf(edit.id, "other"), latest.sessions.map { it.id })
                    inputs(request.session)
                })
            val result =
                submit(admission, edit) { plan ->
                    assertTrue(queue.isLocked)
                    assertEquals(2L, plan.lease.documentRevision)
                    assertIs<PrepareResult.Prepared>(store.prepare(plan)).token.transactionId
                }
            assertEquals(Submission.Admitted(operation.toString()), result)
            assertFalse(queue.isLocked)
        }
    }

    @Test
    fun `a flush acknowledgement cannot admit missing or superseded durable work`() {
        val initial = catalogTestDocument()
        val edit = editedJournalRecord(initial)
        open(initial).use { store ->
            val queue = Mutex()
            val admission =
                StudioSubmitAdmission(
                    store,
                    queue,
                    { DraftWriteResult.Saved(0) },
                    { _, _ -> error("Observed stale work") },
                )
            assertEquals(
                Submission.Blocked(AcceptanceBlock.STALE_SESSION),
                submit(admission, edit) {
                    error("Prepared stale work")
                },
            )
            val afterSave =
                StudioSubmitAdmission(store, queue, { record ->
                    val drafts = StudioDraftStore(store)
                    val saved = assertIs<DraftWriteResult.Saved>(drafts.save(record, "draft"))
                    assertIs<DraftWriteResult.Saved>(
                        drafts.save(record.copy(generation = record.generation + 1), "newer"),
                    )
                    saved
                }, { _, _ -> error("Observed superseded work") })
            assertEquals(
                Submission.Blocked(AcceptanceBlock.STALE_SESSION),
                submit(afterSave, edit) {
                    error("Prepared stale work")
                },
            )
        }
    }

    @Test
    fun `a pending journal blocks before flush and if it appears after flush`() {
        val initial = catalogTestDocument()
        val edit = editedJournalRecord(initial)
        open(initial).use { store ->
            val queue = Mutex()

            fun interrupt() =
                assertIs<PrepareResult.Prepared>(
                    store.prepare(
                        0,
                        initial.copy(header = initial.header.copy(revision = 1, transactionId = "pending")),
                        recovery(),
                    ),
                )
            val pending = interrupt()
            val admission =
                StudioSubmitAdmission(
                    store,
                    queue,
                    { error("Flushed while recovering") },
                    { _, _ -> error("Observed pending work") },
                )
            assertEquals(
                Submission.Recovery(pending.token.transactionId),
                submit(admission, edit) {
                    error("Prepared pending work")
                },
            )
            assertEquals(JournalResult.Acknowledged, store.finishRollback(pending.token))
            val afterFlush =
                StudioSubmitAdmission(store, queue, {
                    interrupt()
                    DraftWriteResult.Saved(0)
                }, { _, _ -> error("Observed pending work") })
            assertEquals(
                Submission.Recovery(pending.token.transactionId),
                submit(afterFlush, edit) {
                    error("Prepared pending work")
                },
            )
        }
    }

    @Test
    fun `a failed flush reports the foreign journal that prevented its save`() {
        val initial = catalogTestDocument()
        val edit = editedJournalRecord(initial)
        open(initial).use { store ->
            val queue = Mutex()
            val drafts = StudioDraftStore(store)
            assertIs<DraftWriteResult.Saved>(drafts.save(edit, "draft"))
            val accepted = assertIs<StoreLoad.Ready>(store.load()).document
            val stateFile = directory.resolve("state.xml")
            val savedBytes = Files.readAllBytes(stateFile)
            val newerEdit = edit.copy(generation = edit.generation + 1)
            val revision = accepted.header.revision
            val nextHeader = accepted.header.copy(revision = revision + 1, transactionId = "foreign")
            val interrupted = accepted.copy(header = nextHeader)
            val admission =
                StudioSubmitAdmission(store, queue, { record ->
                    queue.withLock {
                        assertIs<PrepareResult.Prepared>(
                            store.prepare(revision, interrupted, recovery()),
                        )
                        assertIs<DraftWriteResult.Failed>(drafts.save(record, "newer-draft"))
                    }
                }, { _, _ -> error("Observed pending work") })
            assertEquals(
                Submission.Recovery("foreign"),
                submit(admission, newerEdit) { error("Prepared pending work") },
            )
            val recovering = assertIs<StoreLoad.Recovering>(store.load())
            assertEquals(StudioList(listOf(edit)), recovering.document?.sessions)
            assertTrue(Files.readAllBytes(stateFile).contentEquals(savedBytes))
        }
    }

    @Test
    fun `flush failure leaves current preferences untouched and never calls the effect boundary`() {
        val initial = catalogTestDocument()
        open(initial).use { store ->
            val admission =
                StudioSubmitAdmission(store, Mutex(), {
                    DraftWriteResult.Failed("save-unconfirmed")
                }, { _, _ -> error("Observed unsaved work") })
            assertEquals(
                Submission.FlushFailed("save-unconfirmed"),
                submit(admission, editedJournalRecord(initial)) { error("Prepared unsaved work") },
            )
            assertEquals(StoreLoad.Ready(initial), store.load())
        }
    }

    @Test
    fun `unreadable settings are distinguishable from an uninitialized store`() {
        val edit = editedJournalRecord(catalogTestDocument())
        StudioAppearanceStore(openStudioFiles(directory)).use { store ->
            val admission =
                StudioSubmitAdmission(
                    store,
                    Mutex(),
                    { error("Flushed without readable settings") },
                    { _, _ -> error("Observed without readable settings") },
                )
            assertEquals(
                Submission.Blocked(AcceptanceBlock.READ_ONLY),
                submit(admission, edit) { error("Prepared without settings") },
            )
            val corrupted = byteArrayOf(1)
            val stateFile = directory.resolve("state.xml")
            Files.write(stateFile, corrupted)
            assertEquals(
                Submission.StoreUnavailable(StoreIssue.INVALID_DATA),
                submit(admission, edit) { error("Prepared unreadable settings") },
            )
            assertTrue(Files.readAllBytes(stateFile).contentEquals(corrupted))
        }
    }

    private fun open(document: StudioDocument): StudioAppearanceStore =
        StudioAppearanceStore(openStudioFiles(directory)).also { store ->
            assertEquals(StoreLoad.Empty, store.load())
            assertEquals(JournalResult.Acknowledged, store.preserveSource(document.header.migration))
            val token = assertIs<PrepareResult.Prepared>(store.prepare(-1, document, recovery())).token
            assertIs<CommitResult.Committed>(store.commit(token, document.runtime))
            assertEquals(JournalResult.Acknowledged, store.finishAccepted(token))
        }

    private fun recovery(): RecoveryPlan = RecoveryPlan(StudioTarget.Global, StudioList(emptyList()))

    private fun inputs(record: StudioSessionRecord): ApplyInputs =
        ApplyInputs(
            ApplyContext(
                AcceptanceObservation(
                    AuthorityStatus.READY,
                    TargetObservation(record.target, 1, TargetPresence.ACTIVE),
                ),
                StudioList(emptyList()),
            ),
            EffectObservation(1),
        )

    private fun submit(
        admission: StudioSubmitAdmission,
        edit: StudioSessionRecord,
        consume: suspend (AcceptancePlan.Commit) -> String,
    ): Submission<String> =
        runBlocking {
            withTimeout(5.seconds) { admission.submit(ApplyRequest(edit, 1), operation, consume) }
        }

    private val operation = UUID.fromString("0b4677ae-29f5-4c0d-993d-63e0122c2839")
}
