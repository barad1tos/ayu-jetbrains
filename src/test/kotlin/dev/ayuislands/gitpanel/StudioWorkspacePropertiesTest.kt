package dev.ayuislands.gitpanel

import com.intellij.ide.util.PropertiesComponent
import dev.ayuislands.integration.participantInput
import dev.ayuislands.studio.model.StudioList
import dev.ayuislands.studio.model.StudioProcess
import dev.ayuislands.studio.persistence.RestoreStatus
import dev.ayuislands.studio.session.StudioEffectRuntime
import dev.ayuislands.studio.session.StudioNativeEvidence
import dev.ayuislands.studio.session.StudioNativeFacts
import dev.ayuislands.studio.session.StudioWorkspaceHost
import dev.ayuislands.studio.session.StudioWorkspaceParticipant
import dev.ayuislands.studio.session.StudioWorkspaceProperties
import dev.ayuislands.studio.session.WorkspaceReferences
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlinx.coroutines.runBlocking
import java.time.Instant
import javax.swing.SwingUtilities
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class StudioWorkspacePropertiesTest {
    @BeforeTest
    fun prepareProperties() = mockkStatic(PropertiesComponent::class)

    @AfterTest
    fun releaseProperties() = unmockkAll()

    @Test
    fun `participant rebinds Git properties only after proven process loss`() =
        withProperties { storage ->
            val input = participantInput()
            val process = StudioProcess(73, Instant.EPOCH)
            storage.values[KEY] = "original"
            val batch =
                StudioWorkspaceProperties(
                    WorkspaceReferences(input.candidate.header.transactionId, process),
                    storage.properties,
                )
            val property = batch.capture(KEY)
            val splitter = PersistedSplitter(KEY, 0.4f, 0.5f)
            val geometry = GitSplitterCheckpoint(splitter, 0.7f, { true }, property)
            batch.sealCapture()
            geometry.apply()
            batch.observe()
            val applied = batch.receipts
            val host = mockk<StudioWorkspaceHost> { every { properties() } returns storage.properties }
            val participant = StudioWorkspaceParticipant(host, EmptyCoroutineContext)
            val writes = storage.writes

            runBlocking {
                for (current in listOf(process, process.copy(startedAt = null), null)) {
                    val runtime =
                        mockk<StudioEffectRuntime> { every { native } returns StudioNativeFacts(true, current) }
                    val lost = participant.recover(input, applied, runtime)

                    assertTrue(lost.receipts.all { it.status == RestoreStatus.UNAVAILABLE })
                    lost.prepareRestore(StudioList(emptyList()))
                    lost.restore(StudioList(emptyList()))
                    assertEquals("0.7", storage.values[KEY])
                    assertEquals(writes, storage.writes)
                    val committed = participant.recover(input, applied, runtime)
                    committed.accept()
                    assertEquals(writes, storage.writes)
                }

                val restarted =
                    mockk<StudioEffectRuntime> {
                        every { native } returns StudioNativeFacts(true, process.copy(pid = 74))
                    }
                val recovered = participant.recover(input, applied, restarted)
                recovered.prepareRestore(StudioList(emptyList()))
                recovered.restore(StudioList(emptyList()))

                assertEquals("original", storage.values[KEY])
                assertEquals(0.7f, splitter.proportion)
                assertTrue(recovered.receipts.all { StudioNativeEvidence.isTerminal(it.status) })
            }
        }

    @Test
    fun `the ToolWindow restart exception cannot complete a persisted Git property`() =
        withProperties { storage ->
            val batch = batch(storage)
            batch.capture(KEY)
            batch.sealCapture()
            val invalid = batch.receipts.map { it.copy(status = RestoreStatus.KEPT_AFTER_RESTART) }

            assertFailsWith<IllegalArgumentException> { StudioWorkspaceProperties.recover(invalid, storage.properties) }
            assertEquals(0, storage.writes)
        }

    @Test
    fun `shared key rollback acknowledges immutable native outcomes before geometry and raw restoration`() =
        withProperties { storage ->
            storage.values[KEY] = "0.420000"
            val batch = batch(storage)
            val property = batch.capture(KEY)
            val first = PersistedSplitter(KEY, 0.42f, 0.5f)
            val second = PersistedSplitter(KEY, 0.42f, 0.5f)
            val firstWrite = GitSplitterCheckpoint(first, 0.6f, { true }, property)
            val secondWrite = GitSplitterCheckpoint(second, 0.7f, { true }, batch.capture(KEY))
            batch.sealCapture()
            val prepared = batch.receipts

            firstWrite.apply()
            secondWrite.apply()
            batch.observe()
            val applied = batch.receipts
            assertTrue(applied.size > prepared.size)
            assertEquals(
                prepared.map { it.before to it.lastWritten },
                applied.take(prepared.size).map {
                    it.before to
                        it.lastWritten
                },
            )
            batch.prepareRestore(emptyList())
            val restoring = batch.receipts
            assertTrue(restoring.size > applied.size)
            assertEquals(
                applied.map { it.before to it.lastWritten },
                restoring.take(applied.size).map {
                    it.before to
                        it.lastWritten
                },
            )

            secondWrite.restore()
            firstWrite.restore()
            batch.observe()
            assertEquals("0.42", storage.values[KEY])
            batch.restore(emptyList())

            assertEquals("0.420000", storage.values[KEY])
            assertEquals(0.42f, first.proportion)
            assertEquals(0.42f, second.proportion)
            assertTrue(batch.receipts.all { StudioNativeEvidence.isTerminal(it.status) })
        }

    @Test
    fun `cold recovery recognizes acknowledged rollback outcomes without native splitter lookup`() =
        withProperties { storage ->
            storage.values[KEY] = "0.420000"
            val batch = batch(storage)
            val property = batch.capture(KEY)
            val splitter = PersistedSplitter(KEY, 0.42f, 0.5f)
            val geometry = GitSplitterCheckpoint(splitter, 0.7f, { true }, property)
            batch.sealCapture()
            geometry.apply()
            batch.observe()
            batch.prepareRestore(emptyList())
            val acknowledged = batch.receipts
            geometry.restore()
            val writes = splitter.writes

            val recovered = StudioWorkspaceProperties.recover(acknowledged, storage.properties)
            recovered.prepareRestore(emptyList())
            recovered.restore(emptyList())

            assertEquals("0.420000", storage.values[KEY])
            assertEquals(writes, splitter.writes)
            assertTrue(recovered.receipts.all { StudioNativeEvidence.isTerminal(it.status) })
        }

    @Test
    fun `foreign persisted values survive recovery and partial raw restore retries do not replay setters`() =
        withProperties { storage ->
            storage.values[KEY] = "original"
            val batch = batch(storage)
            val property = batch.capture(KEY)
            val splitter = PersistedSplitter(KEY, 0.4f, 0.5f)
            val geometry = GitSplitterCheckpoint(splitter, 0.7f, { true }, property)
            batch.sealCapture()
            geometry.apply()
            batch.observe()
            val applied = batch.receipts
            storage.values[KEY] = "foreign"
            val foreign = StudioWorkspaceProperties.recover(applied, storage.properties)
            foreign.prepareRestore(emptyList())
            foreign.restore(emptyList())
            assertEquals("foreign", storage.values[KEY])
            assertTrue(foreign.receipts.all { it.status == RestoreStatus.FOREIGN })

            storage.values[KEY] = "0.7"
            val interrupted = StudioWorkspaceProperties.recover(applied, storage.properties)
            interrupted.prepareRestore(emptyList())
            storage.failAfterWrite = true
            assertFailsWith<IllegalStateException> { interrupted.restore(emptyList()) }
            assertEquals("original", storage.values[KEY])
            val writes = storage.writes
            interrupted.prepareRestore(emptyList())
            interrupted.restore(emptyList())
            assertEquals(writes, storage.writes)
            assertTrue(interrupted.receipts.all { StudioNativeEvidence.isTerminal(it.status) })
        }

    private fun batch(storage: ProportionStorage): StudioWorkspaceProperties =
        StudioWorkspaceProperties(
            WorkspaceReferences("workspace-properties", StudioProcess(73, Instant.EPOCH)),
            storage.properties,
        )

    private fun withProperties(action: (ProportionStorage) -> Unit) {
        var result: Result<Unit>? = null
        SwingUtilities.invokeAndWait {
            result =
                runCatching {
                    val storage = ProportionStorage()
                    every { PropertiesComponent.getInstance() } returns storage.properties
                    action(storage)
                }
        }
        checkNotNull(result).getOrThrow()
    }

    private companion object {
        const val KEY = "Git.Log.Workspace.Proportion"
    }
}
