package dev.ayuislands.integration

import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindowAnchor
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.openapi.wm.ToolWindowType
import com.intellij.openapi.wm.ex.ToolWindowEx
import com.intellij.ui.content.Content
import com.intellij.ui.content.ContentManager
import dev.ayuislands.accent.AccentWriteCheckpoint
import dev.ayuislands.accent.AccentWriteState
import dev.ayuislands.settings.PanelWidthMode
import dev.ayuislands.toolwindow.ToolWindowAutoFitter
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.GraphicsEnvironment
import java.awt.Rectangle
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.swing.JFrame
import javax.swing.JPanel
import javax.swing.JTree
import javax.swing.SwingUtilities
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Requires the existing graphical nativeSchemeTest runtime; no skipped/headless substitute. */
class ToolWindowWidthNativeTest {
    private lateinit var project: Project
    private lateinit var manager: ToolWindowManager

    @BeforeTest
    fun setUp() {
        assertFalse(GraphicsEnvironment.isHeadless(), "Register this class in nativeSchemeTest")
        mockkStatic(ToolWindowManager::class)
        project = mockk(relaxed = true)
        manager = mockk(relaxed = true)
        every { project.isDisposed } returns false
        every { ToolWindowManager.getInstance(project) } returns manager
    }

    @AfterTest
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `floating and windowed capture uses existing native ancestor and restores complete bounds`() {
        for (type in listOf(ToolWindowType.FLOATING, ToolWindowType.WINDOWED)) {
            withWindow(type) {
                val before = window.bounds
                val writes = window.boundsWrites
                checkpoint = prepare(350)
                assertEquals(before, window.bounds)
                assertEquals(writes, window.boundsWrites)
                assertTrue(checkpoint!!.apply())
                assertEquals(350, window.width)
                assertEquals(before.height, window.height)
                assertNotEquals(before, window.bounds)
                checkpoint!!.restore()
                assertEquals(before, window.bounds)
                verify(exactly = 0) { toolWindow.stretchWidth(any()) }
                verify(exactly = 0) { toolWindow.show(any()) }
                verify(exactly = 0) { toolWindow.activate(any(), any(), any()) }
            }
        }
    }

    @Test
    fun `native minimum constrains existing forward size without changing saved candidate or rollback image`() {
        withWindow(ToolWindowType.FLOATING) {
            window.minimumSize = Dimension(300, 250)
            val before = window.bounds
            checkpoint = prepare(200)
            assertTrue(checkpoint!!.apply())
            assertEquals(300, window.width)
            assertEquals(Dimension(300, 250), window.minimumSize)
            checkpoint!!.restore()
            assertEquals(before, window.bounds)
            assertEquals(Dimension(300, 250), window.minimumSize)
        }
    }

    @Test
    fun `native window mutation then throw retains rollback and acceptance retries have zero setters`() {
        withWindowPhases(ToolWindowType.WINDOWED, captureDiagnostics = true) {
            lateinit var before: Rectangle
            onEdt { before = window.bounds }
            awaitWidth(350) {
                checkpoint = prepare(350)
                window.failNextBounds = true
                assertFailsWith<IllegalStateException> { checkpoint!!.apply() }
                mark("after failed apply ${checkpoint!!.state}")
                assertTrue(checkpoint!!.hasPending)
                assertNotEquals(before, window.bounds)
            }
            awaitWidth(480) {
                val writes = window.boundsWrites
                fitter.applyFixedWidth(700)
                mark("after held legacy fixed 700 ${checkpoint!!.state}")
                assertEquals(writes, window.boundsWrites)
                window.failNextBounds = true
                assertFailsWith<IllegalStateException> { checkpoint!!.restore() }
                mark("after failed restore ${checkpoint!!.state}")
                assertTrue(checkpoint!!.hasPending)
            }
            onEdt {
                checkpoint!!.restore()
                mark("after restore retry ${checkpoint!!.state}")
                assertEquals(before, window.bounds)
                assertFalse(checkpoint!!.hasPending)
            }
            awaitWidth(360) {
                checkpoint = prepare(360)
                mark("before apply 360 ${checkpoint!!.state}")
                assertTrue(checkpoint!!.apply())
                mark("after apply 360 ${checkpoint!!.state}")
                assertEquals(360, window.width, window.geometryHistory())
            }
            onEdt {
                assertEquals(360, window.width, window.geometryHistory())
                val accepted = window.bounds
                val acceptedWrites = window.boundsWrites
                repeat(2) { attempt ->
                    mark("before commit $attempt ${checkpoint!!.state}")
                    checkpoint!!.commit()
                    mark("after commit $attempt ${checkpoint!!.state}")
                    checkpoint!!.releaseResources()
                    mark("after release $attempt ${checkpoint!!.state}")
                    assertEquals(AccentWriteState.RESTORED, checkpoint!!.state)
                }
                assertEquals(360, window.width, window.geometryHistory())
                assertEquals(accepted, window.bounds, window.geometryHistory())
                assertEquals(acceptedWrites, window.boundsWrites)
            }
        }
    }

    @Test
    fun `foreign native movement resize minimum and replacement target survive cancellation`() {
        for (drift in WindowDrift.entries) {
            withWindow(ToolWindowType.FLOATING) {
                checkpoint = prepare(350)
                assertTrue(checkpoint!!.apply())
                when (drift) {
                    WindowDrift.MOVE -> window.setLocation(window.x + 17, window.y + 19)
                    WindowDrift.RESIZE -> window.setSize(470, 390)
                    WindowDrift.MINIMUM -> window.minimumSize = Dimension(400, 280)
                    WindowDrift.TARGET -> every { manager.getToolWindow("Project") } returns mockk(relaxed = true)
                }
                val foreign = window.bounds
                val writes = window.boundsWrites
                checkpoint!!.restore()
                checkpoint!!.commit()
                checkpoint!!.releaseResources()
                assertEquals(AccentWriteState.RELINQUISHED, checkpoint!!.state)
                assertEquals(foreign, window.bounds)
                assertEquals(writes, window.boundsWrites)
            }
        }
    }

    private fun withWindow(
        type: ToolWindowType,
        action: WindowFixture.() -> Unit,
    ) = withWindowPhases(type) { onEdt(action) }

    private fun withWindowPhases(
        type: ToolWindowType,
        captureDiagnostics: Boolean = false,
        action: WindowFixture.() -> Unit,
    ) {
        lateinit var fixture: WindowFixture
        SwingUtilities.invokeAndWait {
            fixture = WindowFixture(type)
            fixture.window.isRecording = captureDiagnostics
        }
        try {
            fixture.action()
        } finally {
            fixture.onEdt {
                try {
                    checkpoint?.let { if (it.hasPending) it.restore() }
                } finally {
                    try {
                        fitter.removeExpansionListener()
                    } finally {
                        window.dispose()
                    }
                }
            }
        }
    }

    private inner class WindowFixture(
        type: ToolWindowType,
    ) {
        val content = JPanel(BorderLayout()).apply { add(JTree(), BorderLayout.CENTER) }
        val window =
            FailingFrame().apply {
                title = "Ayu retained width test"
                contentPane = content
                setBounds(140, 160, 480, 360)
                isVisible = true
                validate()
            }
        val toolWindow = mockk<ToolWindowEx>(relaxed = true)
        val fitter = ToolWindowAutoFitter(project, "Project", 100)
        var checkpoint: AccentWriteCheckpoint? = null

        init {
            val tab = mockk<Content> { every { component } returns content }
            val contentManager =
                mockk<ContentManager> {
                    every { component } returns content
                    every { contents } returns arrayOf(tab)
                }
            every { manager.getToolWindow("Project") } returns toolWindow
            every { manager.toolWindowIdSet } returns setOf("Project")
            every { toolWindow.component } returns content
            every { toolWindow.contentManager } returns contentManager
            every { toolWindow.contentManagerIfCreated } returns contentManager
            every { toolWindow.type } returns type
            every { toolWindow.anchor } returns ToolWindowAnchor.LEFT
            every { toolWindow.isVisible } returns true
            every { toolWindow.isDisposed } returns false
        }

        fun onEdt(action: WindowFixture.() -> Unit) {
            SwingUtilities.invokeAndWait { action() }
        }

        fun awaitWidth(
            width: Int,
            action: WindowFixture.() -> Unit,
        ) {
            val completed = CountDownLatch(1)
            lateinit var expectedWindow: Rectangle
            lateinit var expectedContent: Rectangle
            val listener =
                object : ComponentAdapter() {
                    override fun componentResized(event: ComponentEvent) = observeGeometry()

                    override fun componentMoved(event: ComponentEvent) = observeGeometry()

                    private fun observeGeometry() {
                        if (window.bounds == expectedWindow && content.bounds == expectedContent) {
                            completed.countDown()
                        }
                    }
                }
            try {
                onEdt {
                    expectedWindow = window.bounds.apply { this.width = width }
                    expectedContent = content.bounds.apply { this.width += width - window.width }
                    window.addComponentListener(listener)
                    content.addComponentListener(listener)
                    action()
                }
                val observed = completed.await(10, TimeUnit.SECONDS)
                onEdt {
                    mark("after native width $width event")
                    assertTrue(observed, window.geometryHistory())
                    assertEquals(expectedWindow, window.bounds)
                    assertEquals(expectedContent, content.bounds)
                }
            } finally {
                onEdt {
                    window.removeComponentListener(listener)
                    content.removeComponentListener(listener)
                }
            }
        }

        fun mark(phase: String) {
            window.mark("$phase content=${content.bounds}")
        }

        fun prepare(width: Int): AccentWriteCheckpoint {
            mark("before capture $width")
            val captured = assertNotNull(fitter.prepareWidth(PanelWidthMode.FIXED, 500, width, 100))
            mark("after capture $width before=${captured.records.single().before}")
            return captured
        }
    }

    private class FailingFrame : JFrame() {
        var boundsWrites = 0
        var failNextBounds = false
        var isRecording = false
        private val events = mutableListOf<String>()
        private var phase = "setup"

        init {
            addComponentListener(
                object : ComponentAdapter() {
                    override fun componentResized(event: ComponentEvent) = record("EVENT_RESIZED")

                    override fun componentMoved(event: ComponentEvent) = record("EVENT_MOVED")
                },
            )
        }

        fun mark(value: String) {
            phase = value
            record("PHASE")
        }

        fun geometryHistory(): String = events.joinToString("\n", prefix = "Native window geometry:\n")

        private fun record(event: String) {
            if (isRecording) {
                events +=
                    "$phase $event observed=$bounds writes=$boundsWrites thread=${Thread.currentThread().name}"
            }
        }

        override fun setBounds(
            x: Int,
            y: Int,
            width: Int,
            height: Int,
        ) {
            super.setBounds(x, y, width, height)
            boundsWrites++
            record("SET requested=${Rectangle(x, y, width, height)}")
            if (failNextBounds) {
                failNextBounds = false
                record("THROW")
                error("Native window setter mutated before failing")
            }
        }
    }

    private enum class WindowDrift { MOVE, RESIZE, MINIMUM, TARGET }
}
