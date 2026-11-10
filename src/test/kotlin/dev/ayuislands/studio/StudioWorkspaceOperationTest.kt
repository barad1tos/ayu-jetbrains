package dev.ayuislands.studio

import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.project.Project
import dev.ayuislands.editor.EditorScrollbarManager
import dev.ayuislands.studio.model.StudioList
import dev.ayuislands.studio.model.StudioProcess
import dev.ayuislands.studio.persistence.RestoreStatus
import dev.ayuislands.studio.session.StudioNativeEvidence
import dev.ayuislands.studio.session.StudioRestoreContinuation
import dev.ayuislands.studio.session.StudioWorkspaceFields
import dev.ayuislands.studio.session.StudioWorkspaceOperation
import dev.ayuislands.studio.session.WorkspaceReferences
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlinx.coroutines.runBlocking
import java.awt.Dimension
import java.time.Instant
import javax.swing.JScrollBar
import javax.swing.JScrollPane
import javax.swing.SwingUtilities
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class StudioWorkspaceOperationTest {
    @BeforeTest
    fun prepareFactory() = mockkStatic(EditorFactory::class)

    @AfterTest
    fun releaseFactory() = unmockkAll()

    @Test
    fun `capture is readonly and repeated acceptance never replays native writes`() =
        workspaceEdt {
            val fixture = WorkspaceOperationFixture()
            val operation = fixture.operation()
            val captured = operation.receipts

            assertEquals(listOf(0, 0), fixture.bars.map { it.writes })
            assertEquals(4, captured.size)
            assertEquals(captured.size, captured.map { it.id }.distinct().size)
            assertTrue(captured.all { it.status == RestoreStatus.PENDING })
            operation.apply()
            fixture.bars.forEach { assertEquals(Dimension(0, 0), it.preferredSize) }
            assertEquals(1, fixture.publications)
            val writes = fixture.bars.map { it.writes }

            operation.accept()
            operation.accept()

            assertEquals(writes, fixture.bars.map { it.writes })
            assertEquals(1, fixture.publications)
            assertEquals(
                captured.map { it.before to it.lastWritten },
                operation.receipts.map {
                    it.before to
                        it.lastWritten
                },
            )
            assertFailsWith<IllegalStateException> { operation.restore(StudioList(emptyList())) }
        }

    @Test
    fun `a late native failure restores independent projects and preserves a subsequent foreign size`() =
        workspaceEdt {
            val fixture = WorkspaceOperationFixture()
            val operation = fixture.operation()
            fixture.bars.last().failAfterWrite = true

            assertFailsWith<IllegalStateException> { operation.apply() }
            fixture.bars.first().preferredSize = Dimension(27, 91)
            assertEquals(0, fixture.publications)
            assertTrue(operation.runtimeChanges.isEmpty())

            operation.prepareRestore(StudioList(emptyList()))
            assertEquals(StudioRestoreContinuation.NONE, operation.restore(StudioList(emptyList())))

            assertEquals(Dimension(27, 91), fixture.bars.first().preferredSize)
            assertEquals(Dimension(16, 90), fixture.bars.last().preferredSize)
            assertTrue(operation.receipts.any { it.status == RestoreStatus.FOREIGN })
            assertTrue(operation.receipts.all { StudioNativeEvidence.isTerminal(it.status) })
        }

    @Test
    fun `failed rollback continues independent projects and retries without repeating completed setters`() =
        workspaceEdt {
            val fixture = WorkspaceOperationFixture()
            val operation = fixture.operation()
            operation.apply()
            fixture.bars.last().failAfterWrite = true
            operation.prepareRestore(StudioList(emptyList()))

            assertFailsWith<IllegalStateException> { operation.restore(StudioList(emptyList())) }
            fixture.bars.forEach { assertEquals(Dimension(16, 90), it.preferredSize) }
            val writes = fixture.bars.map { it.writes }

            operation.prepareRestore(StudioList(emptyList()))
            assertEquals(StudioRestoreContinuation.NONE, operation.restore(StudioList(emptyList())))

            assertEquals(writes, fixture.bars.map { it.writes })
            assertTrue(operation.receipts.all { StudioNativeEvidence.isTerminal(it.status) })
        }

    @Test
    fun `a newer write blocks only its native resource while other projects settle`() =
        workspaceEdt {
            val fixture = WorkspaceOperationFixture()
            val operation = fixture.operation()
            operation.apply()
            val current = operation.receipts.last()
            val newer =
                current.copy(
                    id = "newer-write",
                    identity = current.identity.copy(ownerToken = "newer-owner"),
                    status = RestoreStatus.OWNED,
                )
            operation.prepareRestore(StudioList(listOf(newer)))

            assertFailsWith<IllegalStateException> { operation.restore(StudioList(listOf(newer))) }

            assertEquals(Dimension(16, 90), fixture.bars.first().preferredSize)
            assertEquals(Dimension(0, 0), fixture.bars.last().preferredSize)
            operation.prepareRestore(StudioList(emptyList()))
            assertEquals(StudioRestoreContinuation.NONE, operation.restore(StudioList(emptyList())))
            assertEquals(Dimension(16, 90), fixture.bars.last().preferredSize)
        }

    @Test
    fun `native drift during durable acknowledgement prevents candidate writes`() =
        workspaceEdt {
            val fixture = WorkspaceOperationFixture()
            val operation = fixture.operation()
            fixture.bars.first().preferredSize = Dimension(29, 92)
            val writes = fixture.bars.map { it.writes }

            assertFailsWith<IllegalStateException> { operation.apply() }
            operation.prepareRestore(StudioList(emptyList()))
            operation.restore(StudioList(emptyList()))

            assertEquals(Dimension(29, 92), fixture.bars.first().preferredSize)
            assertEquals(Dimension(16, 90), fixture.bars.last().preferredSize)
            assertEquals(writes, fixture.bars.map { it.writes })
            assertEquals(0, fixture.publications)
        }
}

private class WorkspaceOperationFixture {
    val bars = List(2) { WorkspaceScrollBar() }
    var publications = 0
        private set
    private val projects = List(2) { mockk<Project>(relaxed = true) { every { isDisposed } returns false } }
    private val editors =
        projects.mapIndexed { index, project ->
            val pane = JScrollPane().apply { verticalScrollBar = bars[index] }
            mockk<EditorEx>(relaxed = true) {
                every { this@mockk.project } returns project
                every { scrollPane } returns pane
                every { isDisposed } returns false
            }
        }

    init {
        val factory = mockk<EditorFactory>(relaxed = true) { every { allEditors } returns editors.toTypedArray() }
        every { EditorFactory.getInstance() } returns factory
    }

    fun operation(): StudioWorkspaceOperation {
        val references = WorkspaceReferences("workspace-test", StudioProcess(72, Instant.EPOCH))
        val fields =
            projects.map { project ->
                StudioWorkspaceFields.editor(EditorScrollbarManager(project).capture(true, null), references)
            }
        return StudioWorkspaceOperation(
            fields = fields,
            nativeContext = EmptyCoroutineContext,
            publication = {
                publications++
                StudioList(emptyList())
            },
        )
    }
}

private class WorkspaceScrollBar : JScrollBar() {
    var writes = 0
        private set
    var failAfterWrite = false

    init {
        preferredSize = Dimension(16, 90)
        writes = 0
    }

    override fun setPreferredSize(preferredSize: Dimension?) {
        super.setPreferredSize(preferredSize)
        writes++
        if (failAfterWrite) {
            failAfterWrite = false
            error("Workspace scrollbar changed before failure")
        }
    }
}

private fun workspaceEdt(action: suspend () -> Unit) {
    var result: Result<Unit>? = null
    SwingUtilities.invokeAndWait { result = runCatching { runBlocking { action() } } }
    checkNotNull(result).getOrThrow()
}
