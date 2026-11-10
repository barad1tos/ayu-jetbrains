package dev.ayuislands.studio

import dev.ayuislands.studio.migration.MigrationResult
import dev.ayuislands.studio.migration.StudioLegacyMigration
import dev.ayuislands.studio.model.EffectOwner
import dev.ayuislands.studio.model.LegacyFile
import dev.ayuislands.studio.model.RawValue
import dev.ayuislands.studio.model.StudioDocument
import dev.ayuislands.studio.model.StudioList
import dev.ayuislands.studio.model.StudioTarget
import dev.ayuislands.studio.persistence.CommitResult
import dev.ayuislands.studio.persistence.JournalResult
import dev.ayuislands.studio.persistence.NativeFieldIdentity
import dev.ayuislands.studio.persistence.NativeValue
import dev.ayuislands.studio.persistence.PortableReceipt
import dev.ayuislands.studio.persistence.PrepareResult
import dev.ayuislands.studio.persistence.RecoveryPlan
import dev.ayuislands.studio.persistence.RestoreStatus
import dev.ayuislands.studio.persistence.StoreIssue
import dev.ayuislands.studio.persistence.StoreLoad
import dev.ayuislands.studio.persistence.StudioAppearanceStore
import dev.ayuislands.studio.persistence.StudioStateCodec
import dev.ayuislands.studio.persistence.openStudioFiles
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class StudioStoreContractTest {
    @TempDir
    lateinit var directory: Path

    @Test
    fun `unloaded store cannot mutate an existing transaction`() {
        val candidate = document()
        val token =
            StudioAppearanceStore(openStudioFiles(directory)).use { owner ->
                owner.load()
                owner.preserveSource(candidate.header.migration)
                assertIs<PrepareResult.Prepared>(owner.prepare(-1, candidate, recovery())).token
            }
        val journal = Files.readAllBytes(directory.resolve("pending.xml"))
        StudioAppearanceStore(openStudioFiles(directory)).use { unopened ->
            assertEquals(JournalResult.Rejected(StoreIssue.NOT_LOADED), unopened.recordRecovery(token, emptyList()))
            assertEquals(CommitResult.OutcomeUnknown(StoreIssue.NOT_LOADED), unopened.commit(token, candidate.runtime))
            assertEquals(JournalResult.Rejected(StoreIssue.NOT_LOADED), unopened.finishRollback(token))
            assertEquals(JournalResult.Rejected(StoreIssue.NOT_LOADED), unopened.finishAccepted(token))
        }
        assertContentEquals(journal, Files.readAllBytes(directory.resolve("pending.xml")))
        assertFalse(Files.exists(directory.resolve("state.xml")))
    }

    @Test
    fun `previous generation appearing after empty load forbids fresh initialization`() {
        val candidate = document()
        StudioAppearanceStore(openStudioFiles(directory)).use { store ->
            assertEquals(StoreLoad.Empty, store.load())
            store.preserveSource(candidate.header.migration)
            Files.write(directory.resolve("state.previous.xml"), StudioStateCodec.encode(candidate))
            assertIs<PrepareResult.Rejected>(store.prepare(-1, candidate, recovery()))
        }
        assertFalse(Files.exists(directory.resolve("pending.xml")))
    }

    @Test
    fun `source archive retains exact bytes and original filenames in immutable manifest`() {
        val source = document().header.migration
        StudioAppearanceStore(openStudioFiles(directory)).use { store ->
            store.load()
            assertEquals(JournalResult.Acknowledged, store.preserveSource(source))
            for ((storage, file) in source.archives) {
                val bytes = assertIs<LegacyFile.Present>(file).bytes.toByteArray()
                assertContentEquals(
                    bytes,
                    Files.readAllBytes(
                        directory.resolve("source-backup/${source.id}/${storage.name.lowercase()}.xml"),
                    ),
                )
            }
            val path = directory.resolve("source-backup/${source.id}/manifest.xml")
            val original = Files.readAllBytes(path)
            val text = original.decodeToString()
            for (name in listOf("ayuIslands.xml", "ayu-islands-syntax-intensity.xml", "ayuIslandsAccentMappings.xml")) {
                assertTrue(text.contains(name), "Missing source filename $name")
            }
            assertEquals(JournalResult.Acknowledged, store.preserveSource(source))
            assertContentEquals(original, Files.readAllBytes(path))
            assertIs<JournalResult.Rejected>(store.preserveSource(source.copy(archives = StudioList(emptyList()))))
            assertContentEquals(original, Files.readAllBytes(path))
        }
    }

    @Test
    fun `receipt before image survives restart and cannot be dropped or replaced`() {
        val candidate = document()
        val receipt =
            PortableReceipt(
                "font-size",
                EffectOwner.FONT,
                NativeFieldIdentity("scheme", "size", "owner-1"),
                NativeValue.Absent,
                NativeValue.Present(RawValue.Integer(16)),
                RestoreStatus.PENDING,
            )
        StudioAppearanceStore(openStudioFiles(directory)).use { store ->
            store.load()
            store.preserveSource(candidate.header.migration)
            val token = assertIs<PrepareResult.Prepared>(store.prepare(-1, candidate, recovery(EffectOwner.FONT))).token
            assertEquals(JournalResult.Acknowledged, store.recordRecovery(token, listOf(receipt)))
            assertIs<JournalResult.Rejected>(store.recordRecovery(token, emptyList()))
            assertIs<JournalResult.Rejected>(
                store.recordRecovery(token, listOf(receipt.copy(before = receipt.lastWritten))),
            )
            assertIs<JournalResult.Rejected>(store.finishRollback(token))
        }
        StudioAppearanceStore(openStudioFiles(directory)).use { reopened ->
            val pending = assertIs<StoreLoad.Recovering>(reopened.load()).journal
            assertEquals(listOf(receipt), pending.receipts)
            assertEquals(
                JournalResult.Acknowledged,
                reopened.recordRecovery(pending.token, listOf(receipt.copy(status = RestoreStatus.FOREIGN))),
            )
            assertEquals(JournalResult.Acknowledged, reopened.finishRollback(pending.token))
            assertEquals(StoreLoad.Empty, reopened.load())
        }
    }

    @Test
    fun `future state is preserved and never treated as an empty store`() {
        val future = "<ayu-studio schema=\"2\"/>".encodeToByteArray()
        Files.write(directory.resolve("state.xml"), future)
        StudioAppearanceStore(openStudioFiles(directory)).use { store ->
            assertEquals(StoreIssue.FUTURE_SCHEMA, assertIs<StoreLoad.ReadOnly>(store.load()).issue)
            assertIs<PrepareResult.Rejected>(store.prepare(-1, document(), recovery()))
            assertContentEquals(future, Files.readAllBytes(directory.resolve("state.xml")))
        }
    }

    @Test
    fun `approved retained geometry remains explicit in the journal and permits rollback completion`() {
        val candidate = document()
        val receipt =
            PortableReceipt(
                "window-width",
                EffectOwner.WORKSPACE,
                NativeFieldIdentity("window", "WIDTH_BOUNDS", "workspace/operation/0"),
                NativeValue.Present(RawValue.Integer(400)),
                NativeValue.Present(RawValue.Integer(300)),
                RestoreStatus.KEPT_AFTER_RESTART,
            )
        StudioAppearanceStore(openStudioFiles(directory)).use { store ->
            store.load()
            store.preserveSource(candidate.header.migration)
            val token =
                assertIs<PrepareResult.Prepared>(
                    store.prepare(-1, candidate, recovery(EffectOwner.WORKSPACE)),
                ).token
            assertEquals(JournalResult.Acknowledged, store.recordRecovery(token, listOf(receipt)))
        }
        StudioAppearanceStore(openStudioFiles(directory)).use { reopened ->
            val pending = assertIs<StoreLoad.Recovering>(reopened.load()).journal
            assertEquals(listOf(receipt), pending.receipts)
            assertEquals(JournalResult.Acknowledged, reopened.finishRollback(pending.token))
            assertEquals(StoreLoad.Empty, reopened.load())
        }
    }

    @Test
    fun `retained geometry status rejects wrong owners and persistent or unknown native fields`() {
        val candidate = document()
        val invalidFields =
            listOf(
                EffectOwner.GLOW to "WIDTH_BOUNDS",
                EffectOwner.WORKSPACE to "ROOT_PATH",
                EffectOwner.WORKSPACE to "GIT_PROPERTY",
                EffectOwner.WORKSPACE to "unknown-width",
            )
        for ((index, entry) in invalidFields.withIndex()) {
            val receipt =
                PortableReceipt(
                    "invalid-retention",
                    entry.first,
                    NativeFieldIdentity("native-target", entry.second, "operation/0"),
                    NativeValue.Present(RawValue.Integer(400)),
                    NativeValue.Present(RawValue.Integer(300)),
                    RestoreStatus.KEPT_AFTER_RESTART,
                )
            val location = directory.resolve(index.toString())
            StudioAppearanceStore(openStudioFiles(location)).use { store ->
                store.load()
                store.preserveSource(candidate.header.migration)
                val token = assertIs<PrepareResult.Prepared>(store.prepare(-1, candidate, recovery(entry.first))).token
                val before = Files.readAllBytes(location.resolve("pending.xml"))
                assertEquals(
                    JournalResult.Rejected(StoreIssue.INVALID_DATA),
                    store.recordRecovery(token, listOf(receipt)),
                )
                assertContentEquals(before, Files.readAllBytes(location.resolve("pending.xml")))
            }
        }
    }

    private fun document(): StudioDocument =
        assertIs<MigrationResult.Ready>(StudioLegacyMigration.plan(StudioFixtures.load("unknown-and-ordered"))).document

    private fun recovery(vararg owners: EffectOwner): RecoveryPlan =
        RecoveryPlan(StudioTarget.Global, StudioList(owners.toList()))
}
