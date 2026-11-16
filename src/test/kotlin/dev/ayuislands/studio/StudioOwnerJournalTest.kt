package dev.ayuislands.studio

import dev.ayuislands.studio.model.EffectOwner
import dev.ayuislands.studio.model.RawValue
import dev.ayuislands.studio.model.StudioDocument
import dev.ayuislands.studio.model.StudioList
import dev.ayuislands.studio.model.StudioTarget
import dev.ayuislands.studio.persistence.CommitResolution
import dev.ayuislands.studio.persistence.CommitResult
import dev.ayuislands.studio.persistence.JournalResult
import dev.ayuislands.studio.persistence.NativeFieldIdentity
import dev.ayuislands.studio.persistence.NativeValue
import dev.ayuislands.studio.persistence.PendingJournal
import dev.ayuislands.studio.persistence.PortableReceipt
import dev.ayuislands.studio.persistence.PrepareResult
import dev.ayuislands.studio.persistence.RecoveryPlan
import dev.ayuislands.studio.persistence.RestoreStatus
import dev.ayuislands.studio.persistence.StoreIssue
import dev.ayuislands.studio.persistence.StoreLoad
import dev.ayuislands.studio.persistence.StudioAppearanceStore
import dev.ayuislands.studio.persistence.StudioFiles
import dev.ayuislands.studio.persistence.openStudioFiles
import dev.ayuislands.studio.session.ApplySettlement
import dev.ayuislands.studio.session.OwnerAcceptance
import dev.ayuislands.studio.session.OwnerApply
import dev.ayuislands.studio.session.OwnerRollback
import dev.ayuislands.studio.session.RestoreContinuation
import dev.ayuislands.studio.session.SettlementProblem
import dev.ayuislands.studio.session.StudioApplySettlement
import dev.ayuislands.studio.session.StudioNativeWrite
import dev.ayuislands.studio.session.StudioOwnerJournal
import dev.ayuislands.studio.session.StudioOwnerOperation
import dev.ayuislands.studio.session.StudioOwnerWork
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.io.TempDir
import java.io.IOException
import java.nio.file.Path
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class StudioOwnerJournalTest {
    @TempDir
    lateinit var directory: Path

    private val ioDispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()

    @AfterTest
    fun closeDispatcher() = ioDispatcher.close()

    @Test
    fun `routed owners write only after their receipts are durable in order`() {
        val owners = listOf(EffectOwner.GLOW, EffectOwner.WORKSPACE)
        val initial = catalogTestDocument()
        StudioAppearanceStore(openStudioFiles(directory)).use { store ->
            val work = StudioOwnerWork(prepare(store, initial, owners))
            val written = mutableListOf<EffectOwner>()
            val runner = ownerJournal(store)
            val result =
                runBlocking {
                    runner.apply(work, qualifies = { true }) { owner ->
                        assertEquals(owners.take(written.size), written)
                        effect(owner) {
                            val pending = assertIs<StoreLoad.Recovering>(store.load())
                            assertEquals(written + owner, pending.journal.receipts.map { it.owner })
                            assertEquals(
                                RestoreStatus.PENDING,
                                pending.journal.receipts
                                    .last()
                                    .status,
                            )
                            assertEquals(initial, pending.document)
                            written.add(owner)
                        }
                    }
                }
            assertEquals(OwnerApply.Completed, result)
            assertEquals(owners, written)
            assertEquals(owners, work.operations.keys.toList())
            val pending = assertIs<StoreLoad.Recovering>(store.load())
            assertTrue(pending.journal.receipts.all { it.status == RestoreStatus.OWNED })
            assertEquals(initial, pending.document)
        }
    }

    @Test
    fun `a receipt write rejected by the store prevents the native setter`() {
        val owner = EffectOwner.GLOW
        val files = FailingJournalFiles(openStudioFiles(directory))
        StudioAppearanceStore(files).use { store ->
            val initial = catalogTestDocument()
            val work = StudioOwnerWork(prepare(store, initial, listOf(owner)))
            files.failNextReplace = true
            val result =
                runBlocking {
                    ownerJournal(store).apply(work, qualifies = { true }) {
                        effect(owner) { error("A setter ran without a durable receipt") }
                    }
                }
            assertEquals(OwnerApply.ReceiptFailed(owner, StoreIssue.IO), result)
            val pending = assertIs<StoreLoad.Recovering>(store.load())
            assertTrue(pending.journal.receipts.isEmpty())
            assertEquals(initial, pending.document)
        }
    }

    @Test
    fun `a lease lost during receipt persistence keeps the setter from running`() {
        val owner = EffectOwner.GLOW
        val initial = catalogTestDocument()
        StudioAppearanceStore(openStudioFiles(directory)).use { store ->
            val work = StudioOwnerWork(prepare(store, initial, listOf(owner)))
            val result =
                runBlocking {
                    val qualified = { assertIs<StoreLoad.Recovering>(store.load()).journal.receipts.isEmpty() }
                    ownerJournal(store).apply(work, qualified) {
                        effect(owner) { error("A setter ran after the lease was lost") }
                    }
                }
            assertEquals(OwnerApply.LeaseLost, result)
            val pending = assertIs<StoreLoad.Recovering>(store.load())
            assertEquals(
                RestoreStatus.PENDING,
                pending.journal.receipts
                    .single()
                    .status,
            )
            assertEquals(initial, pending.document)
        }
    }

    @Test
    fun `a setter that writes then throws retains its handle and before-image for rollback`() {
        val owner = EffectOwner.GLOW
        val initial = catalogTestDocument()
        StudioAppearanceStore(openStudioFiles(directory)).use { store ->
            val work = StudioOwnerWork(prepare(store, initial, listOf(owner, EffectOwner.WORKSPACE)))
            var native = false
            val failure = IllegalStateException("Native refresh failed after writing")
            val operation =
                effect(owner) {
                    native = true
                    throw failure
                }
            val result =
                runBlocking {
                    ownerJournal(store).apply(work, qualifies = { true }) {
                        assertEquals(owner, it)
                        operation
                    }
                }
            val failed = assertIs<OwnerApply.OwnerFailed>(result)
            assertEquals(owner, failed.owner)
            assertTrue(generateSequence<Throwable>(failed.cause) { it.cause }.any { it === failure })
            assertTrue(native)
            assertEquals(mapOf(owner to operation), work.operations)
            val pending = assertIs<StoreLoad.Recovering>(store.load())
            assertEquals(nativeReceipt(owner), pending.journal.receipts.single())
            assertEquals(initial, pending.document)
        }
    }

    @Test
    fun `an operation without receipts cannot enter the native setter`() {
        val initial = catalogTestDocument()
        StudioAppearanceStore(openStudioFiles(directory)).use { store ->
            val work = StudioOwnerWork(prepare(store, initial, listOf(EffectOwner.GLOW)))
            val unrecorded =
                object : StudioOwnerOperation {
                    override val receipts = emptyList<PortableReceipt>()

                    override suspend fun apply(writes: StudioNativeWrite): Boolean = error("Unrecorded setter ran")

                    override suspend fun restore(blocked: List<PortableReceipt>) = error("Unrecorded restore ran")

                    override suspend fun accept() = error("Unrecorded acceptance ran")
                }

            assertFailsWith<IllegalArgumentException> {
                runBlocking { ownerJournal(store).apply(work, qualifies = { true }) { unrecorded } }
            }
            assertTrue(work.operations.isEmpty())
            assertEquals(initial, assertIs<StoreLoad.Recovering>(store.load()).document)
        }
    }

    @Test
    fun `a lease observation failure reaches the transaction caller without running a setter`() {
        val initial = catalogTestDocument()
        StudioAppearanceStore(openStudioFiles(directory)).use { store ->
            val work = StudioOwnerWork(prepare(store, initial, listOf(EffectOwner.GLOW)))
            val failure = IllegalStateException("Native lease observation failed")
            val observed =
                assertFailsWith<IllegalStateException> {
                    runBlocking {
                        ownerJournal(store).apply(work, qualifies = { throw failure }) {
                            error("Owner captured without a valid lease")
                        }
                    }
                }
            assertSame(failure, observed)
            assertTrue(work.operations.isEmpty())
            assertEquals(initial, assertIs<StoreLoad.Recovering>(store.load()).document)
        }
    }

    @Test
    fun `a no-op owner leaves no receipt and later owners still apply`() {
        val initial = catalogTestDocument()
        val owner = EffectOwner.WORKSPACE
        StudioAppearanceStore(openStudioFiles(directory)).use { store ->
            val work = StudioOwnerWork(prepare(store, initial, listOf(EffectOwner.GLOW, owner)))
            var applied = false
            val result =
                runBlocking {
                    ownerJournal(store).apply(work, qualifies = { true }) {
                        if (it == owner) effect(owner) { applied = true } else null
                    }
                }
            assertEquals(OwnerApply.Completed, result)
            assertTrue(applied)
            assertEquals(setOf(owner), work.operations.keys)
            val journal = assertIs<StoreLoad.Recovering>(store.load()).journal
            assertEquals(listOf(nativeReceipt(owner).copy(status = RestoreStatus.OWNED)), journal.receipts)
        }
    }

    @Test
    fun `cancellation after journaling stops the setter and retains rollback evidence`() {
        val initial = catalogTestDocument()
        val owner = EffectOwner.GLOW
        StudioAppearanceStore(openStudioFiles(directory)).use { store ->
            val work = StudioOwnerWork(prepare(store, initial, listOf(owner)))
            assertFailsWith<CancellationException> {
                runBlocking {
                    ownerJournal(store).apply(
                        work,
                        qualifies = {
                            val journal = assertIs<StoreLoad.Recovering>(store.load()).journal
                            if (journal.receipts.isNotEmpty()) this@runBlocking.cancel("Apply cancelled")
                            true
                        },
                    ) { effect(owner) { error("A setter ran after cancellation") } }
                }
            }
            assertEquals(setOf(owner), work.operations.keys)
            val pending = assertIs<StoreLoad.Recovering>(store.load())
            assertEquals(listOf(nativeReceipt(owner)), pending.journal.receipts)
            assertEquals(initial, pending.document)
        }
    }

    @Test
    fun `a failed readback receipt write retains the applied owner for rollback`() {
        val initial = catalogTestDocument()
        val owner = EffectOwner.GLOW
        val files = FailingJournalFiles(openStudioFiles(directory))
        StudioAppearanceStore(files).use { store ->
            val work = StudioOwnerWork(prepare(store, initial, listOf(owner)))
            var native = false
            val operation =
                effect(owner) {
                    native = true
                    files.failNextReplace = true
                }
            val result = runBlocking { ownerJournal(store).apply(work, qualifies = { true }) { operation } }
            assertEquals(OwnerApply.ReceiptFailed(owner, StoreIssue.IO), result)
            assertTrue(native)
            assertSame(operation, work.operations[owner])
            val pending = assertIs<StoreLoad.Recovering>(store.load())
            assertEquals(listOf(nativeReceipt(owner)), pending.journal.receipts)
            assertEquals(initial, pending.document)
        }
    }

    @Test
    fun `terminal prepared evidence never reaches the native setter`() {
        val owner = EffectOwner.GLOW
        StudioAppearanceStore(openStudioFiles(directory)).use { store ->
            val work = StudioOwnerWork(prepare(store, catalogTestDocument(), listOf(owner)))
            var wrote = false
            val operation =
                object : StudioOwnerOperation {
                    override val receipts = listOf(nativeReceipt(owner).copy(status = RestoreStatus.RESTORED))

                    override suspend fun apply(writes: StudioNativeWrite): Boolean =
                        writes.execute(ioDispatcher) { wrote = true }

                    override suspend fun restore(blocked: List<PortableReceipt>) = error("Unexpected restore")

                    override suspend fun accept() = error("Unexpected acceptance")
                }
            assertFailsWith<IllegalArgumentException> {
                runBlocking { ownerJournal(store).apply(work, qualifies = { true }) { operation } }
            }
            assertFalse(wrote)
            assertTrue(work.operations.isEmpty())
            assertTrue(assertIs<StoreLoad.Recovering>(store.load()).journal.receipts.isEmpty())
        }
    }

    @Test
    fun `readback cannot append evidence after the native write`() {
        val owner = EffectOwner.GLOW
        StudioAppearanceStore(openStudioFiles(directory)).use { store ->
            val work = StudioOwnerWork(prepare(store, catalogTestDocument(), listOf(owner)))
            val prepared = nativeReceipt(owner)
            val operation =
                object : StudioOwnerOperation {
                    override var receipts = listOf(prepared)
                        private set

                    override suspend fun apply(writes: StudioNativeWrite): Boolean =
                        writes.execute(ioDispatcher) {
                            receipts =
                                listOf(
                                    prepared.copy(status = RestoreStatus.OWNED),
                                    prepared.copy(
                                        id = "unexpected",
                                        identity = prepared.identity.copy(field = "late-field"),
                                    ),
                                )
                        }

                    override suspend fun restore(blocked: List<PortableReceipt>) = error("Unexpected restore")

                    override suspend fun accept() = error("Unexpected acceptance")
                }
            val result = runBlocking { ownerJournal(store).apply(work, qualifies = { true }) { operation } }
            assertEquals(OwnerApply.ReceiptFailed(owner, StoreIssue.CONFLICT), result)
            assertEquals(listOf(prepared), assertIs<StoreLoad.Recovering>(store.load()).journal.receipts)
            assertSame(operation, work.operations[owner])
        }
    }

    @Test
    fun `losing qualification on the native dispatcher prevents the setter`() {
        val owner = EffectOwner.GLOW
        StudioAppearanceStore(openStudioFiles(directory)).use { store ->
            val work = StudioOwnerWork(prepare(store, catalogTestDocument(), listOf(owner)))
            var qualified = true
            var wrote = false
            val nativeDispatcher =
                Executor { command ->
                    qualified = false
                    command.run()
                }.asCoroutineDispatcher()
            val operation =
                object : StudioOwnerOperation {
                    override val receipts = listOf(nativeReceipt(owner))

                    override suspend fun apply(writes: StudioNativeWrite): Boolean =
                        writes.execute(nativeDispatcher) { wrote = true }

                    override suspend fun restore(blocked: List<PortableReceipt>) = error("Unexpected restore")

                    override suspend fun accept() = error("Unexpected acceptance")
                }
            val result = runBlocking { ownerJournal(store).apply(work, qualifies = { qualified }) { operation } }
            assertEquals(OwnerApply.LeaseLost, result)
            assertFalse(wrote)
            assertEquals(operation.receipts, assertIs<StoreLoad.Recovering>(store.load()).journal.receipts)
        }
    }

    private fun ownerJournal(store: StudioAppearanceStore): StudioOwnerJournal = StudioOwnerJournal(store, ioDispatcher)

    @Test
    fun `an interrupted Apply restores captured owners in reverse order and preserves foreign state`() {
        val initial = catalogTestDocument()
        val owners = listOf(EffectOwner.GLOW, EffectOwner.WORKSPACE)
        val native = mutableMapOf<EffectOwner, Boolean?>(EffectOwner.GLOW to false, EffectOwner.WORKSPACE to false)
        val restored = mutableListOf<EffectOwner>()
        StudioAppearanceStore(openStudioFiles(directory)).use { store ->
            val work = StudioOwnerWork(prepare(store, initial, owners))
            val runner = ownerJournal(store)
            runBlocking {
                val applied =
                    runner.apply(work, qualifies = { true }) { owner ->
                        effect(
                            owner,
                            restoreStatus = { blocked ->
                                assertTrue(blocked.isEmpty())
                                restored.add(owner)
                                if (native[owner] == true) {
                                    native[owner] = false
                                    RestoreStatus.RESTORED
                                } else {
                                    RestoreStatus.FOREIGN
                                }
                            },
                        ) { native[owner] = true }
                    }
                assertEquals(OwnerApply.Completed, applied)
                native[EffectOwner.WORKSPACE] = null
                assertEquals(OwnerRollback.Settled, runner.rollback(work))
            }
            assertEquals(owners.reversed(), restored)
            assertEquals(mapOf(EffectOwner.GLOW to false, EffectOwner.WORKSPACE to null), native)
            val pending = assertIs<StoreLoad.Recovering>(store.load())
            assertEquals(
                listOf(RestoreStatus.RESTORED, RestoreStatus.FOREIGN),
                pending.journal.receipts.map { it.status },
            )
            assertEquals(initial, pending.document)
            assertEquals(JournalResult.Acknowledged, store.finishRollback(work.journal.token))
        }
    }

    @Test
    fun `a committed candidate cannot run a rollback setter`() {
        val initial = catalogTestDocument()
        val owner = EffectOwner.GLOW
        StudioAppearanceStore(openStudioFiles(directory)).use { store ->
            val work = StudioOwnerWork(prepare(store, initial, listOf(owner)))
            val runner = ownerJournal(store)
            runBlocking {
                assertEquals(OwnerApply.Completed, runner.apply(work, qualifies = { true }) { effect(owner) {} })
                val accepted = assertIs<CommitResult.Committed>(store.commit(work.journal.token, initial.runtime))
                assertEquals(OwnerRollback.Decision(CommitResolution.Committed), runner.rollback(work))
                val pending = assertIs<StoreLoad.Recovering>(store.load())
                assertEquals(accepted.document, pending.document)
                assertEquals(
                    RestoreStatus.OWNED,
                    pending.journal.receipts
                        .single()
                        .status,
                )
            }
        }
    }

    @Test
    fun `partial rollback preparation is journaled before an earlier owner runs`() {
        val initial = catalogTestDocument()
        val older = EffectOwner.GLOW
        val newer = EffectOwner.WORKSPACE
        val failure = IllegalStateException("Preparation failed after capture")
        val evidence = nativeReceipt(newer).copy(id = "workspace-restore", identity = nativeReceipt(older).identity)
        StudioAppearanceStore(openStudioFiles(directory)).use { store ->
            val work = StudioOwnerWork(prepare(store, initial, listOf(older, newer)))
            val runner = ownerJournal(store)
            val result =
                runBlocking {
                    runner
                        .apply(work, qualifies = { true }) { owner ->
                            if (owner == newer) {
                                rollbackOperation(owner, prepare = {
                                    it.add(evidence)
                                    throw failure
                                })
                            } else {
                                effect(owner, restoreStatus = { blocked ->
                                    assertTrue(evidence in blocked)
                                    assertTrue(
                                        evidence in assertIs<StoreLoad.Recovering>(store.load()).journal.receipts,
                                    )
                                    RestoreStatus.UNAVAILABLE
                                }) {}
                            }
                        }.also { assertEquals(OwnerApply.Completed, it) }
                    runner.rollback(work)
                }
            assertEquals(OwnerRollback.OwnerFailed(newer, failure), result)
            val pending = assertIs<StoreLoad.Recovering>(store.load())
            assertEquals(initial, pending.document)
            assertTrue(evidence in pending.journal.receipts)
        }
    }

    @Test
    fun `a colliding rollback receipt stops restoration before any setter`() {
        val initial = catalogTestDocument()
        val older = EffectOwner.GLOW
        val newer = EffectOwner.WORKSPACE
        val restored = mutableListOf<EffectOwner>()
        StudioAppearanceStore(openStudioFiles(directory)).use { store ->
            val work = StudioOwnerWork(prepare(store, initial, listOf(older, newer)))
            val runner = ownerJournal(store)
            val result =
                runBlocking {
                    val applied =
                        runner.apply(work, qualifies = { true }) { owner ->
                            rollbackOperation(
                                owner,
                                prepare = { receipts ->
                                    if (owner == newer) receipts.add(nativeReceipt(newer).copy(id = older.name))
                                },
                                restore = { _, _ ->
                                    restored.add(owner)
                                    RestoreContinuation.COMPLETE
                                },
                            )
                        }
                    assertEquals(OwnerApply.Completed, applied)
                    runner.rollback(work)
                }
            assertEquals(OwnerRollback.ReceiptFailed(newer, StoreIssue.CONFLICT), result)
            assertTrue(restored.isEmpty())
            val pending = assertIs<StoreLoad.Recovering>(store.load())
            assertEquals(listOf(older.name, newer.name), pending.journal.receipts.map { it.id })
            assertEquals(initial, pending.document)
        }
    }

    @Test
    fun `cancellation after a native restore preserves its journal evidence`() {
        val owner = EffectOwner.GLOW
        StudioAppearanceStore(openStudioFiles(directory)).use { store ->
            val work = StudioOwnerWork(prepare(store, catalogTestDocument(), listOf(owner)))
            val runner = ownerJournal(store)
            runBlocking {
                val applied =
                    runner.apply(work, qualifies = { true }) {
                        rollbackOperation(owner, restore = { receipts, _ ->
                            receipts[0] = receipts.single().copy(status = RestoreStatus.RESTORED)
                            currentCoroutineContext().cancel(CancellationException("Cancelled after restore"))
                            RestoreContinuation.COMPLETE
                        })
                    }
                assertEquals(OwnerApply.Completed, applied)
                val rollback = launch { runner.rollback(work) }
                rollback.join()
                assertTrue(rollback.isCancelled)
            }
            val pending = assertIs<StoreLoad.Recovering>(store.load())
            val receipt = pending.journal.receipts.single()
            assertEquals(RestoreStatus.RESTORED, receipt.status)
        }
    }

    @Test
    fun `failed receipt save during cancellation keeps evidence available for retry`() {
        val owner = EffectOwner.GLOW
        val cancellation = CancellationException("Cancelled after restore")
        val files = FailingJournalFiles(openStudioFiles(directory))
        StudioAppearanceStore(files).use { store ->
            val work = StudioOwnerWork(prepare(store, catalogTestDocument(), listOf(owner)))
            val runner = ownerJournal(store)
            var writes = 0
            runBlocking {
                val applied =
                    runner.apply(work, qualifies = { true }) {
                        rollbackOperation(owner, restore = { receipts, _ ->
                            val receipt = receipts.single()
                            if (receipt.status == RestoreStatus.OWNED) {
                                writes += 1
                                receipts[0] = receipt.copy(status = RestoreStatus.RESTORED)
                                files.failNextReplace = true
                                throw cancellation
                            }
                            RestoreContinuation.COMPLETE
                        })
                    }
                assertEquals(OwnerApply.Completed, applied)
                val failure = assertFailsWith<CancellationException> { runner.rollback(work) }
                assertSame(cancellation, failure)
                val saveFailure = assertIs<IllegalStateException>(failure.suppressed.single())
                assertTrue(saveFailure.message.orEmpty().endsWith(StoreIssue.IO.name))
                val unfinished = assertIs<StoreLoad.Recovering>(store.load())
                val unconfirmed = unfinished.journal.receipts.single()
                assertEquals(RestoreStatus.OWNED, unconfirmed.status)
                assertEquals(OwnerRollback.Settled, runner.rollback(work))
            }
            assertEquals(1, writes)
            val pending = assertIs<StoreLoad.Recovering>(store.load())
            val receipt = pending.journal.receipts.single()
            assertEquals(RestoreStatus.RESTORED, receipt.status)
        }
    }

    @Test
    fun `a pending newer owner does not hide the earlier restore failure`() {
        val initial = catalogTestDocument()
        val older = EffectOwner.GLOW
        val newer = EffectOwner.WORKSPACE
        val failure = IllegalStateException("Could not restore earlier owner")
        StudioAppearanceStore(openStudioFiles(directory)).use { store ->
            val work = StudioOwnerWork(prepare(store, initial, listOf(older, newer)))
            val runner = ownerJournal(store)
            val result =
                runBlocking {
                    val applied =
                        runner.apply(work, qualifies = { true }) { owner ->
                            rollbackOperation(owner, restore = { _, _ ->
                                if (owner == older) throw failure
                                RestoreContinuation.REQUIRED
                            })
                        }
                    assertEquals(OwnerApply.Completed, applied)
                    runner.rollback(work)
                }
            assertEquals(OwnerRollback.OwnerFailed(older, failure), result)
        }
    }

    @Test
    fun `a rejected rollback writeback can be retried without rewriting a restored field`() {
        val initial = catalogTestDocument()
        val owner = EffectOwner.GLOW
        val files = FailingJournalFiles(openStudioFiles(directory))
        StudioAppearanceStore(files).use { store ->
            val work = StudioOwnerWork(prepare(store, initial, listOf(owner)))
            val runner = ownerJournal(store)
            var writes = 0
            runBlocking {
                val applied =
                    runner.apply(work, qualifies = { true }) {
                        rollbackOperation(owner, restore = { receipts, _ ->
                            if (receipts.single().status == RestoreStatus.OWNED) {
                                writes += 1
                                receipts[0] = receipts[0].copy(status = RestoreStatus.RESTORED)
                                files.failNextReplace = true
                            }
                            RestoreContinuation.COMPLETE
                        })
                    }
                assertEquals(OwnerApply.Completed, applied)
                assertEquals(OwnerRollback.ReceiptFailed(owner, StoreIssue.IO), runner.rollback(work))
                val pending = assertIs<StoreLoad.Recovering>(store.load())
                assertEquals(
                    RestoreStatus.OWNED,
                    pending.journal.receipts
                        .single()
                        .status,
                )
                assertEquals(OwnerRollback.Settled, runner.rollback(work))
            }
            val pending = assertIs<StoreLoad.Recovering>(store.load())
            assertEquals(
                RestoreStatus.RESTORED,
                pending.journal.receipts
                    .single()
                    .status,
            )
            assertEquals(1, writes)
            assertEquals(initial, pending.document)
        }
    }

    @Test
    fun `owners accept only after the durable generation changes`() {
        val initial = catalogTestDocument()
        val owners = listOf(EffectOwner.GLOW, EffectOwner.WORKSPACE)
        StudioAppearanceStore(openStudioFiles(directory)).use { store ->
            val work = StudioOwnerWork(prepare(store, initial, owners))
            val accepted = mutableListOf<EffectOwner>()
            val runner = ownerJournal(store)
            runBlocking {
                assertEquals(
                    OwnerApply.Completed,
                    runner.apply(work, qualifies = { true }) { owner ->
                        effect(owner, accepted = { accepted.add(owner) }) {}
                    },
                )
                assertEquals(OwnerAcceptance.Decision(CommitResolution.NotCommitted), runner.accept(work))
                assertTrue(accepted.isEmpty())
                assertEquals(initial, assertIs<StoreLoad.Recovering>(store.load()).document)

                val commit = assertIs<CommitResult.Committed>(store.commit(work.journal.token, initial.runtime))
                assertEquals(OwnerAcceptance.Completed, runner.accept(work))
                assertEquals(OwnerAcceptance.Completed, runner.accept(work))
                assertEquals(owners, accepted)
                val pending = assertIs<StoreLoad.Recovering>(store.load())
                assertEquals(commit.document, pending.document)
                assertEquals(owners, pending.journal.receipts.map { it.owner })
            }
        }
    }

    @Test
    fun `retry acknowledges only the owners still awaiting acceptance`() {
        val initial = catalogTestDocument()
        val owners = listOf(EffectOwner.ACCENT, EffectOwner.FONT, EffectOwner.GLOW, EffectOwner.WORKSPACE)
        val failure = IllegalStateException("Font acknowledgement interrupted")
        StudioAppearanceStore(openStudioFiles(directory)).use { store ->
            val work = StudioOwnerWork(prepare(store, initial, owners))
            val accepted = mutableListOf<EffectOwner>()
            val written = mutableListOf<EffectOwner>()
            var fail = true
            val runner = ownerJournal(store)
            runBlocking {
                assertEquals(
                    OwnerApply.Completed,
                    runner.apply(work, qualifies = { true }) { owner ->
                        if (owner == EffectOwner.GLOW) {
                            null
                        } else {
                            effect(
                                owner,
                                accepted = {
                                    if (owner == EffectOwner.FONT && fail) throw failure
                                    accepted.add(owner)
                                },
                            ) { written.add(owner) }
                        }
                    },
                )
                val commit = assertIs<CommitResult.Committed>(store.commit(work.journal.token, initial.runtime))
                assertEquals(OwnerAcceptance.OwnerFailed(EffectOwner.FONT, failure), runner.accept(work))
                assertEquals(listOf(EffectOwner.ACCENT), accepted)
                assertEquals(commit.document, assertIs<StoreLoad.Recovering>(store.load()).document)

                fail = false
                assertEquals(OwnerAcceptance.Completed, runner.accept(work))
                val effects = listOf(EffectOwner.ACCENT, EffectOwner.FONT, EffectOwner.WORKSPACE)
                assertEquals(effects, accepted)
                assertEquals(effects, written)
            }
        }
    }

    @Test
    fun `accepted receipts without a live handle keep recovery pending`() {
        val initial = catalogTestDocument()
        val owner = EffectOwner.GLOW
        StudioAppearanceStore(openStudioFiles(directory)).use { store ->
            val work = StudioOwnerWork(prepare(store, initial, listOf(owner)))
            val receipt = nativeReceipt(owner).copy(status = RestoreStatus.OWNED)
            assertEquals(JournalResult.Acknowledged, store.recordRecovery(work.journal.token, listOf(receipt)))
            val commit = assertIs<CommitResult.Committed>(store.commit(work.journal.token, initial.runtime))
            assertEquals(OwnerAcceptance.HandleUnavailable(owner), runBlocking { ownerJournal(store).accept(work) })
            val pending = assertIs<StoreLoad.Recovering>(store.load())
            assertEquals(commit.document, pending.document)
            assertEquals(listOf(receipt), pending.journal.receipts)
        }
    }

    @Test
    fun `cleanup after accepted Apply retries only the unfinished step`() {
        val initial = catalogTestDocument()
        val owner = EffectOwner.GLOW
        val files = FailingJournalFiles(openStudioFiles(directory))
        var acknowledgements = 0
        var releases = 0
        val failure = IllegalStateException("Cannot release the native scope yet")

        StudioAppearanceStore(files).use { store ->
            val work = StudioOwnerWork(prepare(store, initial, listOf(owner)))
            val runner = ownerJournal(store)
            runBlocking {
                assertEquals(
                    OwnerApply.Completed,
                    runner.apply(work, qualifies = { true }) { effect(it, accepted = { acknowledgements++ }) {} },
                )
                val committed = assertIs<CommitResult.Committed>(store.commit(work.journal.token, initial.runtime))
                val settlement =
                    StudioApplySettlement(store, runner, ioDispatcher, work) { accepted ->
                        assertEquals(committed.document, accepted)
                        if (++releases == 1) throw failure
                    }
                assertEquals(
                    ApplySettlement.AcceptedCleanupPending(committed.document, SettlementProblem.Release(failure)),
                    settlement.settle(),
                )
                assertEquals(committed.document, assertIs<StoreLoad.Recovering>(store.load()).document)

                val cleanupPending =
                    ApplySettlement.AcceptedCleanupPending(
                        committed.document,
                        SettlementProblem.Journal(StoreIssue.IO),
                    )
                files.loseNextRemovalAcknowledgement = true
                assertEquals(cleanupPending, settlement.settle())
                assertEquals(committed.document, assertIs<StoreLoad.Ready>(store.load()).document)
                files.failNextStateRead = true
                assertEquals(cleanupPending, settlement.settle())
                assertEquals(ApplySettlement.Accepted(committed.document), settlement.settle())
                assertEquals(2, releases)
                assertEquals(1, acknowledgements)
            }
        }
    }

    @Test
    fun `cleanup after rollback cannot restore an already settled native field again`() {
        val initial = catalogTestDocument()
        val owner = EffectOwner.GLOW
        val files = FailingJournalFiles(openStudioFiles(directory))
        var native = false
        var restores = 0
        var releases = 0

        StudioAppearanceStore(files).use { store ->
            val work = StudioOwnerWork(prepare(store, initial, listOf(owner)))
            val runner = ownerJournal(store)
            runBlocking {
                assertEquals(
                    OwnerApply.Completed,
                    runner.apply(work, qualifies = { true }) {
                        effect(it, restoreStatus = {
                            restores++
                            native = false
                            RestoreStatus.RESTORED
                        }) { native = true }
                    },
                )
                assertTrue(native)
                val settlement =
                    StudioApplySettlement(store, runner, ioDispatcher, work) { accepted ->
                        assertNull(accepted)
                        releases++
                    }
                files.loseNextRemovalAcknowledgement = true
                assertEquals(
                    ApplySettlement.RecoveryRequired(SettlementProblem.Journal(StoreIssue.IO)),
                    settlement.settle(),
                )
                assertEquals(initial, assertIs<StoreLoad.Ready>(store.load()).document)
                assertFalse(native)

                // A user edit after restoration must survive a journal cleanup retry.
                native = true
                assertEquals(ApplySettlement.NotAccepted, settlement.settle())
                assertEquals(initial, assertIs<StoreLoad.Ready>(store.load()).document)
                assertTrue(native)
                assertEquals(1, restores)
                assertEquals(1, releases)
            }
        }
    }

    @Test
    fun `unreadable commit evidence prevents settlement of either outcome`() {
        val initial = catalogTestDocument()
        val files = FailingJournalFiles(openStudioFiles(directory))
        StudioAppearanceStore(files).use { store ->
            val work = StudioOwnerWork(prepare(store, initial, emptyList()))
            var releases = 0
            val settlement =
                StudioApplySettlement(store, ownerJournal(store), ioDispatcher, work) { accepted ->
                    assertNull(accepted)
                    releases++
                }
            files.failNextStateRead = true
            runBlocking {
                assertEquals(ApplySettlement.RecoveryRequired(SettlementProblem.Unconfirmed), settlement.settle())
                val pending = assertIs<StoreLoad.Recovering>(store.load())
                assertEquals(work.journal.token, pending.journal.token)
                assertEquals(initial, pending.document)
                assertEquals(0, releases)
                assertEquals(ApplySettlement.NotAccepted, settlement.settle())
                assertEquals(1, releases)
            }
        }
    }

    private fun prepare(
        store: StudioAppearanceStore,
        initial: StudioDocument,
        owners: List<EffectOwner>,
    ): PendingJournal {
        assertEquals(StoreLoad.Empty, store.load())
        assertEquals(JournalResult.Acknowledged, store.preserveSource(initial.header.migration))
        val initialPlan = RecoveryPlan(StudioTarget.Global, StudioList(emptyList()))
        val first = assertIs<PrepareResult.Prepared>(store.prepare(-1, initial, initialPlan)).token
        assertIs<CommitResult.Committed>(store.commit(first, initial.runtime))
        assertEquals(JournalResult.Acknowledged, store.finishAccepted(first))
        val candidate = initial.copy(header = initial.header.copy(revision = 1, transactionId = "apply"))
        val route = initialPlan.copy(owners = StudioList(owners))
        return assertIs<PrepareResult.Prepared>(store.prepare(0, candidate, route)).journal
    }

    private fun effect(
        owner: EffectOwner,
        restoreStatus: (List<PortableReceipt>) -> RestoreStatus = { error("Unexpected rollback setter") },
        accepted: () -> Unit = { error("Unexpected acceptance") },
        write: () -> Unit,
    ): StudioOwnerOperation =
        object : StudioOwnerOperation {
            private var receipt = nativeReceipt(owner)
            override val receipts: List<PortableReceipt> get() = listOf(receipt)

            override suspend fun apply(writes: StudioNativeWrite): Boolean =
                writes.execute(ioDispatcher) {
                    write()
                    receipt = receipt.copy(status = RestoreStatus.OWNED)
                }

            override suspend fun restore(blocked: List<PortableReceipt>): RestoreContinuation {
                receipt = receipt.copy(status = restoreStatus(blocked))
                return RestoreContinuation.COMPLETE
            }

            override suspend fun accept() = accepted()
        }

    private fun rollbackOperation(
        owner: EffectOwner,
        prepare: (MutableList<PortableReceipt>) -> Unit = {},
        restore: suspend (MutableList<PortableReceipt>, List<PortableReceipt>) -> RestoreContinuation = { _, _ ->
            error("Unexpected rollback setter")
        },
    ): StudioOwnerOperation =
        object : StudioOwnerOperation {
            private val current = mutableListOf(nativeReceipt(owner))
            override val receipts: List<PortableReceipt> get() = current.toList()

            override suspend fun apply(writes: StudioNativeWrite): Boolean =
                writes.execute(ioDispatcher) {
                    current[0] = current[0].copy(status = RestoreStatus.OWNED)
                }

            override suspend fun prepareRestore(blocked: List<PortableReceipt>) = prepare(current)

            override suspend fun restore(blocked: List<PortableReceipt>): RestoreContinuation =
                restore(current, blocked)

            override suspend fun accept() = error("Unexpected acceptance")
        }

    private fun nativeReceipt(owner: EffectOwner): PortableReceipt {
        val surface = if (owner == EffectOwner.GLOW) "Editor frame" else "Editor scrollbar"
        return PortableReceipt(
            id = owner.name,
            owner = owner,
            identity = NativeFieldIdentity(surface, "enabled", "live-editor"),
            before = NativeValue.Present(RawValue.BooleanValue(false)),
            lastWritten = NativeValue.Present(RawValue.BooleanValue(true)),
            status = RestoreStatus.PENDING,
        )
    }
}

private class FailingJournalFiles(
    private val files: StudioFiles,
) : StudioFiles by files {
    var failNextReplace = false
    var loseNextRemovalAcknowledgement = false
    var failNextStateRead = false

    override fun read(name: String): ByteArray? {
        if (failNextStateRead && name == "state.xml") {
            failNextStateRead = false
            throw IOException("Cannot read the current generation")
        }
        return files.read(name)
    }

    override fun remove(name: String) {
        files.remove(name)
        if (loseNextRemovalAcknowledgement && name == "pending.xml") {
            loseNextRemovalAcknowledgement = false
            throw IOException("Lost acknowledgement after deleting the pending journal")
        }
    }

    override fun replace(
        source: String,
        destination: String,
    ) {
        if (failNextReplace && destination == "pending.xml") {
            failNextReplace = false
            throw IOException("Cannot replace the pending journal")
        }
        files.replace(source, destination)
    }
}
