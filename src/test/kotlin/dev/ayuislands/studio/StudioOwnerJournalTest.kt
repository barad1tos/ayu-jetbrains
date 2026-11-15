package dev.ayuislands.studio

import dev.ayuislands.studio.model.EffectOwner
import dev.ayuislands.studio.model.RawValue
import dev.ayuislands.studio.model.StudioDocument
import dev.ayuislands.studio.model.StudioList
import dev.ayuislands.studio.model.StudioTarget
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
import dev.ayuislands.studio.session.OwnerApply
import dev.ayuislands.studio.session.StudioOwnerJournal
import dev.ayuislands.studio.session.StudioOwnerOperation
import dev.ayuislands.studio.session.StudioOwnerWork
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.io.TempDir
import java.io.IOException
import java.nio.file.Path
import java.util.concurrent.Executors
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
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
            assertEquals(OwnerApply.OwnerFailed(owner, failure), result)
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

                    override suspend fun apply() = error("Unrecorded setter ran")
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

    private fun ownerJournal(store: StudioAppearanceStore): StudioOwnerJournal = StudioOwnerJournal(store, ioDispatcher)

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
        write: () -> Unit,
    ): StudioOwnerOperation =
        object : StudioOwnerOperation {
            private var receipt = nativeReceipt(owner)
            override val receipts: List<PortableReceipt> get() = listOf(receipt)

            override suspend fun apply() {
                write()
                receipt = receipt.copy(status = RestoreStatus.OWNED)
            }
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
