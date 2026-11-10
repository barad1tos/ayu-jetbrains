package dev.ayuislands.studio

import dev.ayuislands.studio.migration.MigrationResult
import dev.ayuislands.studio.migration.StudioLegacyMigration
import dev.ayuislands.studio.model.EffectOwner
import dev.ayuislands.studio.model.RawValue
import dev.ayuislands.studio.model.StudioDocument
import dev.ayuislands.studio.model.StudioList
import dev.ayuislands.studio.model.StudioTarget
import dev.ayuislands.studio.persistence.CommitResolution
import dev.ayuislands.studio.persistence.CommitResult
import dev.ayuislands.studio.persistence.DecodeResult
import dev.ayuislands.studio.persistence.JournalResult
import dev.ayuislands.studio.persistence.NativeFieldIdentity
import dev.ayuislands.studio.persistence.NativeValue
import dev.ayuislands.studio.persistence.PortableReceipt
import dev.ayuislands.studio.persistence.PrepareResult
import dev.ayuislands.studio.persistence.PreparedToken
import dev.ayuislands.studio.persistence.RecoveryPlan
import dev.ayuislands.studio.persistence.RestoreStatus
import dev.ayuislands.studio.persistence.StoreIssue
import dev.ayuislands.studio.persistence.StoreLoad
import dev.ayuislands.studio.persistence.StudioAppearanceStore
import dev.ayuislands.studio.persistence.StudioFiles
import dev.ayuislands.studio.persistence.StudioStateCodec
import dev.ayuislands.studio.persistence.openStudioFiles
import org.junit.jupiter.api.io.TempDir
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class StudioStoreBoundaryTest {
    @TempDir
    lateinit var directory: Path

    @Test
    fun `every commit IO fault preserves a provable old or accepted generation`() {
        val base = document()
        val candidate = base.copy(header = base.header.copy(revision = 1, transactionId = "next"))
        val trace = commitTrace(base, candidate)
        assertTrue(trace.any { it.operation == IoOperation.FORCE })
        assertTrue(trace.any { it.operation == IoOperation.REPLACE && it.name == "state.previous.xml" })
        for ((index, event) in trace.withIndex()) {
            val root = directory.resolve("commit-$index")
            seed(root, base)
            val files = FaultingStudioFiles(openStudioFiles(root))
            StudioAppearanceStore(files).use { store ->
                store.load()
                val token = assertIs<PrepareResult.Prepared>(store.prepare(0, candidate, recovery())).token
                files.armAt(event, trace.take(index + 1).count { it == event })
                val result = store.commit(token, candidate.runtime)
                assertEquals(0, files.pendingFailures, "Fault not reached: $index $event")
                val actual = readDocument(root)
                assertTrue(actual == base || actual == candidate, "Invalid authority at $index $event")
                val committed = actual == candidate
                assertFalse(committed && result is CommitResult.NotCommitted, "Accepted state misreported at $event")
                assertFalse(!committed && result is CommitResult.Committed, "Unaccepted state misreported at $event")
                store.load()
                val expected = if (committed) CommitResolution.Committed else CommitResolution.NotCommitted
                assertEquals(expected, store.resolveOutcome(token), "Unresolved $index $event")
                val finished = if (committed) store.finishAccepted(token) else store.finishRollback(token)
                assertEquals(JournalResult.Acknowledged, finished)
                assertEquals(actual, readDocument(root))
            }
        }
    }

    @Test
    fun `every prepare IO fault leaves accepted state and recoverable journal unchanged in meaning`() {
        val base = document()
        val candidate = base.copy(header = base.header.copy(revision = 1, transactionId = "next"))
        val traceRoot = directory.resolve("prepare-trace")
        seed(traceRoot, base)
        val tracing = FaultingStudioFiles(openStudioFiles(traceRoot))
        val trace =
            StudioAppearanceStore(tracing).use { store ->
                store.load()
                tracing.arm()
                assertIs<PrepareResult.Prepared>(store.prepare(0, candidate, recovery()))
                tracing.observed.toList()
            }
        for ((index, event) in trace.withIndex()) {
            val root = directory.resolve("prepare-$index")
            seed(root, base)
            val files = FaultingStudioFiles(openStudioFiles(root))
            StudioAppearanceStore(files).use { store ->
                store.load()
                files.armAt(event, trace.take(index + 1).count { it == event })
                assertIs<PrepareResult.Rejected>(store.prepare(0, candidate, recovery()))
                assertEquals(0, files.pendingFailures, "Fault not reached: $index $event")
                assertEquals(base, readDocument(root))
                when (val loaded = store.load()) {
                    is StoreLoad.Ready -> assertEquals(base, loaded.document)
                    is StoreLoad.Recovering -> {
                        assertEquals(base, loaded.document)
                        assertEquals(candidate, loaded.journal.candidate)
                        assertEquals(JournalResult.Acknowledged, store.finishRollback(loaded.journal.token))
                    }
                    else -> error("Unexpected recovery at $index $event: $loaded")
                }
            }
        }
    }

    @Test
    fun `atomic replacement unavailability never falls back to a non atomic write`() {
        val candidate = document()
        val actual = openStudioFiles(directory)
        val unsupported =
            object : StudioFiles by actual {
                override fun replace(
                    source: String,
                    destination: String,
                ) {
                    if (destination ==
                        "state.xml"
                    ) {
                        throw AtomicMoveNotSupportedException(source, destination, "test provider")
                    }
                    actual.replace(source, destination)
                }
            }
        StudioAppearanceStore(unsupported).use { store ->
            store.load()
            store.preserveSource(candidate.header.migration)
            val token = assertIs<PrepareResult.Prepared>(store.prepare(-1, candidate, recovery())).token
            assertEquals(
                CommitResult.NotCommitted(StoreIssue.ATOMIC_MOVE_UNSUPPORTED),
                store.commit(token, candidate.runtime),
            )
            assertFalse(Files.exists(directory.resolve("state.xml")))
            assertEquals(CommitResolution.NotCommitted, store.resolveOutcome(token))
            assertEquals(JournalResult.Acknowledged, store.finishRollback(token))
        }
    }

    @Test
    fun `journal acknowledgements require exact staged and replaced bytes`() {
        val candidate = document()
        for (isStaged in listOf(true, false)) {
            val actual = openStudioFiles(directory.resolve("readback-$isStaged"))
            var wasRead = false
            val changed =
                object : StudioFiles by actual {
                    override fun read(name: String): ByteArray? {
                        val bytes: ByteArray = actual.read(name) ?: return null
                        val isSelected = if (isStaged) name.startsWith("pending.xml.tmp.") else name == "pending.xml"
                        if (!isSelected) return bytes
                        wasRead = true
                        // Valid XML with changed bytes must still fail the durable acknowledgement.
                        return bytes + ' '.code.toByte()
                    }
                }
            StudioAppearanceStore(changed).use { store ->
                store.load()
                store.preserveSource(candidate.header.migration)
                assertEquals(PrepareResult.Rejected(StoreIssue.IO), store.prepare(-1, candidate, recovery()))
                assertTrue(wasRead)
            }
        }
    }

    @Test
    fun `every archive IO failure retains source evidence without accepting a document`() {
        val candidate = document()
        val tracing = FaultingStudioFiles(openStudioFiles(directory.resolve("archive-trace")))
        val trace =
            StudioAppearanceStore(tracing).use { store ->
                store.load()
                tracing.arm()
                assertEquals(JournalResult.Acknowledged, store.preserveSource(candidate.header.migration))
                tracing.observed.toList()
            }
        assertTrue(trace.any { it.operation == IoOperation.CREATE && it.name.endsWith("manifest.xml") })
        for ((index, event) in trace.withIndex()) {
            val root = directory.resolve("archive-$index")
            val files = FaultingStudioFiles(openStudioFiles(root))
            StudioAppearanceStore(files).use { store ->
                store.load()
                files.armAt(event, trace.take(index + 1).count { it == event })
                assertIs<JournalResult.Rejected>(store.preserveSource(candidate.header.migration))
                assertEquals(0, files.pendingFailures, "Fault not reached: $index $event")
                assertFalse(Files.exists(root.resolve("state.xml")))
                assertFalse(Files.exists(root.resolve("pending.xml")))
                val archived =
                    Files.walk(root).use { paths ->
                        paths
                            .filter { Files.isRegularFile(it) && it.fileName.toString() != "store.lock" }
                            .toList()
                            .associateWith { path ->
                                Files.newInputStream(path).use { it.readAllBytes() }.toList()
                            }
                    }
                val newSource = candidate.header.migration.copy(id = "retry-migration")
                assertEquals(JournalResult.Acknowledged, store.preserveSource(newSource))
                for ((path, before) in archived) {
                    assertEquals(before, Files.newInputStream(path).use { it.readAllBytes() }.toList())
                }
            }
        }
    }

    @Test
    fun `every cleanup IO failure is reported while accepted state remains authoritative`() {
        val candidate = document()
        val tracing = FaultingStudioFiles(openStudioFiles(directory.resolve("cleanup-trace")))
        val trace =
            StudioAppearanceStore(tracing).use { store ->
                val token = accept(store, candidate)
                tracing.arm()
                assertEquals(JournalResult.Acknowledged, store.finishAccepted(token))
                tracing.observed.toList()
            }
        assertTrue(trace.any { it.operation == IoOperation.REMOVE })
        for ((index, event) in trace.withIndex()) {
            val root = directory.resolve("cleanup-$index")
            val files = FaultingStudioFiles(openStudioFiles(root))
            StudioAppearanceStore(files).use { store ->
                val token = accept(store, candidate)
                files.armAt(event, trace.take(index + 1).count { it == event })
                assertIs<JournalResult.Rejected>(store.finishAccepted(token), "Ignored IO fault: $index $event")
                assertEquals(0, files.pendingFailures, "Fault not reached: $index $event")
                assertEquals(candidate, readDocument(root))
                store.load()
                assertEquals(JournalResult.Acknowledged, store.finishAccepted(token))
                assertEquals(StoreLoad.Ready(candidate), store.load())
            }
        }
    }

    @Test
    fun `every receipt journal IO fault retains its immutable before image`() {
        val candidate = document()
        val receipt =
            PortableReceipt(
                "font-size",
                EffectOwner.FONT,
                NativeFieldIdentity("scheme", "size", "owner"),
                NativeValue.Present(RawValue.Integer(14)),
                NativeValue.Present(RawValue.Integer(16)),
                RestoreStatus.PENDING,
            )
        val tracing = FaultingStudioFiles(openStudioFiles(directory.resolve("receipt-trace")))
        val trace =
            StudioAppearanceStore(tracing).use { store ->
                val token = prepareRecovery(store, candidate, receipt)
                tracing.arm()
                assertEquals(
                    JournalResult.Acknowledged,
                    store.recordRecovery(token, listOf(receipt.copy(status = RestoreStatus.OWNED))),
                )
                tracing.observed.toList()
            }
        for ((index, event) in trace.withIndex()) {
            val root = directory.resolve("receipt-$index")
            val files = FaultingStudioFiles(openStudioFiles(root))
            StudioAppearanceStore(files).use { store ->
                val token = prepareRecovery(store, candidate, receipt)
                files.armAt(event, trace.take(index + 1).count { it == event })
                assertIs<JournalResult.Rejected>(
                    store.recordRecovery(token, listOf(receipt.copy(status = RestoreStatus.OWNED))),
                )
                assertEquals(0, files.pendingFailures, "Fault not reached: $index $event")
                val recorded = assertIs<StoreLoad.Recovering>(store.load()).journal.receipts.single()
                assertEquals(receipt, recorded.copy(status = RestoreStatus.PENDING))
                assertTrue(recorded.status in setOf(RestoreStatus.PENDING, RestoreStatus.OWNED))
                assertFalse(Files.exists(root.resolve("state.xml")))
                assertEquals(
                    JournalResult.Acknowledged,
                    store.recordRecovery(token, listOf(recorded.copy(status = RestoreStatus.FOREIGN))),
                )
                assertEquals(JournalResult.Acknowledged, store.finishRollback(token))
            }
        }
    }

    private fun prepareRecovery(
        store: StudioAppearanceStore,
        candidate: StudioDocument,
        receipt: PortableReceipt,
    ): PreparedToken {
        store.load()
        store.preserveSource(candidate.header.migration)
        val recoveryPlan = RecoveryPlan(StudioTarget.Global, StudioList(listOf(EffectOwner.FONT)))
        val token = assertIs<PrepareResult.Prepared>(store.prepare(-1, candidate, recoveryPlan)).token
        assertEquals(JournalResult.Acknowledged, store.recordRecovery(token, listOf(receipt)))
        return token
    }

    private fun commitTrace(
        base: StudioDocument,
        candidate: StudioDocument,
    ): List<IoEvent> {
        val root = directory.resolve("commit-trace")
        seed(root, base)
        val files = FaultingStudioFiles(openStudioFiles(root))
        return StudioAppearanceStore(files).use { store ->
            store.load()
            val token = assertIs<PrepareResult.Prepared>(store.prepare(0, candidate, recovery())).token
            files.arm()
            assertIs<CommitResult.Committed>(store.commit(token, candidate.runtime))
            files.observed.toList()
        }
    }

    private fun seed(
        root: Path,
        document: StudioDocument,
    ) = StudioAppearanceStore(openStudioFiles(root)).use { store ->
        val token = accept(store, document)
        assertEquals(JournalResult.Acknowledged, store.finishAccepted(token))
    }

    private fun accept(
        store: StudioAppearanceStore,
        document: StudioDocument,
    ): PreparedToken {
        store.load()
        store.preserveSource(document.header.migration)
        val token = assertIs<PrepareResult.Prepared>(store.prepare(-1, document, recovery())).token
        assertIs<CommitResult.Committed>(store.commit(token, document.runtime))
        return token
    }

    private fun readDocument(root: Path): StudioDocument =
        assertIs<DecodeResult.Ready>(
            StudioStateCodec.decode(
                Files.newInputStream(root.resolve("state.xml")).use {
                    it.readAllBytes()
                },
            ),
        ).document

    private fun document(): StudioDocument =
        assertIs<MigrationResult.Ready>(StudioLegacyMigration.plan(StudioFixtures.load("unknown-and-ordered"))).document

    private fun recovery(): RecoveryPlan = RecoveryPlan(StudioTarget.Global, StudioList(emptyList()))
}
