package dev.ayuislands.studio

import dev.ayuislands.accent.AyuVariant
import dev.ayuislands.rotation.AccentRotationMode
import dev.ayuislands.rotation.FullIntervalReason
import dev.ayuislands.rotation.RotationCandidate
import dev.ayuislands.rotation.RotationIntent
import dev.ayuislands.rotation.RotationOutput
import dev.ayuislands.rotation.RotationResume
import dev.ayuislands.rotation.RotationRuntimeCheckpoint
import dev.ayuislands.rotation.RotationSchedule
import dev.ayuislands.rotation.RuntimeFault
import dev.ayuislands.rotation.RuntimeOwner
import dev.ayuislands.studio.model.StudioDocument
import dev.ayuislands.studio.model.StudioList
import dev.ayuislands.studio.model.StudioTarget
import dev.ayuislands.studio.persistence.CommitResult
import dev.ayuislands.studio.persistence.DecodeResult
import dev.ayuislands.studio.persistence.JournalResult
import dev.ayuislands.studio.persistence.PrepareResult
import dev.ayuislands.studio.persistence.RecoveryPlan
import dev.ayuislands.studio.persistence.StoreLoad
import dev.ayuislands.studio.persistence.StudioAppearanceStore
import dev.ayuislands.studio.persistence.StudioDocumentXml
import dev.ayuislands.studio.persistence.StudioStateCodec
import dev.ayuislands.studio.persistence.openStudioFiles
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class StudioRotationCheckpointCodecTest {
    @TempDir lateinit var directory: Path

    @Test
    fun `legacy aggregate with no rotation checkpoint preserves its absence and source evidence`() {
        val accepted = sessionDocument()
        val initial = accepted.copy(runtime = accepted.runtime.copy(rotation = null))
        val encoded = StudioStateCodec.encode(initial)
        val decoded = assertIs<DecodeResult.Ready>(StudioStateCodec.decode(encoded)).document

        assertNull(decoded.runtime.rotation)
        assertEquals(initial, decoded)
        assertContentEquals(encoded, StudioStateCodec.encode(decoded))
        assertNull(StudioDocumentXml.writeDocument(decoded).child("runtime").optionalChild("rotation"))
    }

    @Test
    fun `canonical checkpoint retains every existing obligation and raw retry value in the aggregate`() {
        val initial = sessionDocument()
        val obligations =
            listOf(
                RotationResume.KeepDeadline,
                RotationResume.NoHistory,
                RotationResume.ImmediateEnable,
                RotationResume.ImmediateConfiguration,
            ) +
                FullIntervalReason.entries.map { RotationResume.FullInterval(it) }
        for (resume in obligations) {
            val checkpoint = checkpoint().copy(resume = resume)
            val candidate = initial.copy(runtime = initial.runtime.copy(rotation = checkpoint))
            val encoded = StudioStateCodec.encode(candidate)
            val decoded = assertIs<DecodeResult.Ready>(StudioStateCodec.decode(encoded)).document
            val runtimeNode = StudioDocumentXml.writeDocument(decoded).child("runtime")

            assertEquals(candidate, decoded)
            assertEquals(checkpoint, decoded.runtime.rotation)
            assertEquals(initial.runtime.legacy, decoded.runtime.legacy)
            assertEquals(initial.header.migration, decoded.header.migration)
            assertEquals(1, runtimeNode.children.count { it.name == "rotation" })
            assertEquals("1", runtimeNode.child("rotation").attribute("schema"))
            assertContentEquals(encoded, StudioStateCodec.encode(decoded))
        }
    }

    @Test
    fun `prepared checkpoint survives restart and rollback restores the previous aggregate checkpoint`() {
        val initial = sessionDocument().let { it.copy(runtime = it.runtime.copy(rotation = checkpoint())) }
        val candidate =
            initial.copy(
                header = initial.header.copy(revision = 1, transactionId = "rotation-prepared"),
                runtime = initial.runtime.copy(rotation = checkpoint().copy(successfulStepSerial = 8)),
            )
        StudioAppearanceStore(openStudioFiles(directory)).use { store ->
            initialize(store, initial)
            assertIs<PrepareResult.Prepared>(store.prepare(0, candidate, recovery()))
        }
        StudioAppearanceStore(openStudioFiles(directory)).use { reopened ->
            val pending = assertIs<StoreLoad.Recovering>(reopened.load())
            assertEquals(candidate, pending.journal.candidate)
            val base =
                assertIs<DecodeResult.Ready>(
                    StudioStateCodec.decode(requireNotNull(pending.journal.base).toByteArray()),
                )
            assertEquals(initial, base.document)
            assertEquals(JournalResult.Acknowledged, reopened.finishRollback(pending.journal.token))
            assertEquals(initial, assertIs<StoreLoad.Ready>(reopened.load()).document)
        }
        assertStoreFiles(setOf("state.xml", "store.lock", "source-backup"))
    }

    @Test
    fun `committed checkpoint survives cleanup restart in the existing journal and previous generation`() {
        val initial = sessionDocument().let { it.copy(runtime = it.runtime.copy(rotation = checkpoint())) }
        val candidate =
            initial.copy(
                header = initial.header.copy(revision = 1, transactionId = "rotation-accepted"),
                runtime = initial.runtime.copy(rotation = checkpoint().copy(successfulStepSerial = 8)),
            )
        StudioAppearanceStore(openStudioFiles(directory)).use { store ->
            initialize(store, initial)
            val token = assertIs<PrepareResult.Prepared>(store.prepare(0, candidate, recovery())).token
            assertIs<CommitResult.Committed>(store.commit(token, candidate.runtime))
        }
        StudioAppearanceStore(openStudioFiles(directory)).use { reopened ->
            val pending = assertIs<StoreLoad.Recovering>(reopened.load())
            assertEquals(candidate, pending.document)
            assertEquals(candidate, pending.journal.candidate)
            assertEquals(JournalResult.Acknowledged, reopened.finishAccepted(pending.journal.token))
            assertEquals(candidate, assertIs<StoreLoad.Ready>(reopened.load()).document)
        }
        val previous =
            assertIs<DecodeResult.Ready>(
                StudioStateCodec.decode(Files.readAllBytes(directory.resolve("state.previous.xml"))),
            )
        assertEquals(initial, previous.document)
        assertStoreFiles(setOf("state.xml", "state.previous.xml", "store.lock", "source-backup"))
    }

    private fun checkpoint(): RotationRuntimeCheckpoint {
        val owner = RuntimeOwner(StudioTarget.Global, 0)
        return RotationRuntimeCheckpoint(
            owner = owner,
            sequenceCursorRaw = Int.MIN_VALUE,
            successfulStepSerial = 7,
            lastSuccessfulStepEpochMillis = 42,
            output = RotationOutput(owner, AyuVariant.MIRAGE, "#5cCFe6"),
            schedule = RotationSchedule(42, 84, 42, 9),
            retryCandidate =
                RotationCandidate(
                    UUID.fromString("10000000-0000-0000-0000-000000000001"),
                    owner,
                    RotationIntent(enabled = true, modeRaw = "future-mode", intervalHoursRaw = Int.MIN_VALUE),
                    AccentRotationMode.RANDOM,
                    AyuVariant.DARK,
                    "#aBcDeF",
                    Int.MAX_VALUE,
                ),
            recoveryFault = RuntimeFault.DurableRecovery("owned-recovery-pending"),
        )
    }

    private fun initialize(
        store: StudioAppearanceStore,
        document: StudioDocument,
    ) {
        assertEquals(StoreLoad.Empty, store.load())
        assertEquals(JournalResult.Acknowledged, store.preserveSource(document.header.migration))
        val token = assertIs<PrepareResult.Prepared>(store.prepare(-1, document, recovery())).token
        assertIs<CommitResult.Committed>(store.commit(token, document.runtime))
        assertEquals(JournalResult.Acknowledged, store.finishAccepted(token))
    }

    private fun recovery(): RecoveryPlan = RecoveryPlan(StudioTarget.Global, StudioList(emptyList()))

    private fun assertStoreFiles(expected: Set<String>) {
        val names = Files.list(directory).use { files -> files.map { it.fileName.toString() }.toList().toSet() }
        assertEquals(expected, names)
    }
}
