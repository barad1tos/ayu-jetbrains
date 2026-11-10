package dev.ayuislands.toolwindow

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ThreeComponentsSplitter
import com.intellij.openapi.wm.ToolWindowAnchor
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.openapi.wm.ToolWindowType
import com.intellij.openapi.wm.ex.ToolWindowEx
import com.intellij.ui.content.Content
import com.intellij.ui.content.ContentManager
import dev.ayuislands.accent.AccentApplyPlanRunner
import dev.ayuislands.accent.AccentWriteCheckpoint
import dev.ayuislands.accent.AccentWriteState
import dev.ayuislands.settings.PanelWidthMode
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import java.awt.BorderLayout
import java.awt.Rectangle
import javax.swing.BorderFactory
import javax.swing.JPanel
import javax.swing.JTree
import javax.swing.SwingUtilities
import javax.swing.UIManager
import javax.swing.event.TreeExpansionEvent
import javax.swing.event.TreeExpansionListener
import javax.swing.plaf.metal.MetalLookAndFeel
import javax.swing.tree.TreePath
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ToolWindowAutoFitterCheckpointTest {
    private lateinit var project: Project
    private lateinit var manager: ToolWindowManager
    private val posted = ArrayDeque<Runnable>()

    @BeforeTest
    fun setUp() {
        mockkStatic(ToolWindowManager::class)
        mockkObject(AccentApplyPlanRunner)
        project = mockk(relaxed = true)
        manager = mockk(relaxed = true)
        every { project.isDisposed } returns false
        every { ToolWindowManager.getInstance(project) } returns manager
        every { AccentApplyPlanRunner.post(any()) } answers { posted += firstArg<Runnable>() }
    }

    @AfterTest
    fun tearDown() {
        posted.clear()
        unmockkAll()
    }

    @Test
    fun `existing forward stretches native host from content delta and raw size restores constrained geometry`() {
        for (anchor in listOf(ToolWindowAnchor.LEFT, ToolWindowAnchor.RIGHT)) {
            withFixture(anchor, rawExtent = 800) {
                val before = geometry()
                val contentWidth = content.width
                val hostWidth = host.width
                assertNotEquals(rawExtent(), hostWidth)
                assertNotEquals(hostWidth, contentWidth)

                fitter.applyFixedWidth(350)

                assertEquals(350 - contentWidth, lastDelta)
                assertEquals(
                    (hostWidth + lastDelta).coerceIn(splitter.getMinSize(isFirst), splitter.getMaxSize(isFirst)),
                    rawExtent(),
                )
                setRawExtent(if (isFirst) before.firstSize else before.lastSize)
                assertEquals(before, geometry())
            }
        }
    }

    @Test
    fun `capture is readonly and abort restores exact raw geometry and listener identities on either side`() {
        for (anchor in listOf(ToolWindowAnchor.LEFT, ToolWindowAnchor.RIGHT)) {
            withFixture(anchor, rawExtent = 800) {
                fitter.installExpansionListener()
                val listeners = tree.treeExpansionListeners.toList()
                val before = geometry()
                val layouts = splitter.layoutCalls
                val contentWidth = content.width
                checkpoint = prepare(PanelWidthMode.FIXED)
                assertEquals(before, geometry())
                assertEquals(listeners, tree.treeExpansionListeners.toList())
                assertEquals(layouts, splitter.layoutCalls)
                assertEquals(0, forwardWrites)

                assertTrue(checkpoint!!.apply())
                assertEquals(350 - contentWidth, lastDelta)
                val writes = forwardWrites
                fitter.applyFixedWidth(900)
                assertEquals(writes, forwardWrites)

                checkpoint!!.restore()
                assertEquals(before, geometry())
                assertEquals(listeners, tree.treeExpansionListeners.toList())
                assertFalse(checkpoint!!.hasPending)
                val settledLayouts = splitter.layoutCalls
                checkpoint!!.commit()
                checkpoint!!.releaseResources()
                checkpoint!!.releaseResources()
                assertEquals(settledLayouts, splitter.layoutCalls)
                assertEquals(writes, forwardWrites)
                assertTrue(posted.isEmpty())
            }
        }
    }

    @Test
    fun `auto fit uses explicit candidate limits without preference reads and rolls back listener addition`() {
        withFixture {
            fitter.minWidthProvider = { error("Candidate must not read saved minimum") }
            fitter.maxWidthProvider = { error("Candidate must not read saved maximum") }
            val before = geometry()
            val contentWidth = content.width
            val listeners = tree.treeExpansionListeners.toList()
            val desired =
                AutoFitCalculator.calculateDesiredWidth(
                    AutoFitCalculator.measureTreeMaxRowWidth(tree),
                    360,
                    340,
                )
            checkpoint = prepare(PanelWidthMode.AUTO_FIT, minimum = 340, maximum = 360)
            assertEquals(before, geometry())
            assertEquals(listeners, tree.treeExpansionListeners.toList())
            assertTrue(checkpoint!!.apply())
            assertEquals(desired - contentWidth, lastDelta)
            assertEquals(listeners.size + 1, tree.treeExpansionListeners.size)

            checkpoint!!.restore()

            assertEquals(before, geometry())
            assertEquals(listeners, tree.treeExpansionListeners.toList())
            assertTrue(posted.isEmpty())
        }
    }

    @Test
    fun `native mutation then throw retains hold through restore failure and resumes one due event after abort`() {
        withFixture {
            fitter.installExpansionListener()
            val listeners = tree.treeExpansionListeners.toList()
            val before = geometry()
            fitter.scheduleAutoFit()
            checkpoint = prepare(PanelWidthMode.FIXED)
            failAfterStretch = true
            assertFailsWith<IllegalStateException> { checkpoint!!.apply() }
            assertTrue(checkpoint!!.hasPending)
            assertNotEquals(before, geometry())

            fitter.flushDebounceForTesting()
            fitter.scheduleAutoFit()
            fitter.flushDebounceForTesting()
            assertEquals(1, forwardWrites)
            assertTrue(posted.isEmpty())

            splitter.failNextLayout = true
            assertFailsWith<IllegalStateException> { checkpoint!!.restore() }
            assertTrue(checkpoint!!.hasPending)
            fitter.applyFixedWidth(900)
            assertEquals(1, forwardWrites)
            assertTrue(posted.isEmpty())

            checkpoint!!.restore()
            assertFalse(checkpoint!!.hasPending)
            assertEquals(before, geometry())
            assertEquals(listeners, tree.treeExpansionListeners.toList())
            assertEquals(1, forwardWrites)
            checkpoint!!.releaseResources()
            assertEquals(1, posted.size)

            fitter.minWidthProvider = { 420 }
            fitter.maxWidthProvider = { 500 }
            val contentWidth = content.width
            posted.removeFirst().run()
            assertEquals(2, forwardWrites)
            assertEquals(420 - contentWidth, lastDelta)
            assertTrue(posted.isEmpty())
        }
    }

    @Test
    fun `accept and release never write inline and stale auto fit event cannot override accepted fixed mode`() {
        withFixture {
            fitter.installExpansionListener()
            fitter.scheduleAutoFit()
            checkpoint = prepare(PanelWidthMode.FIXED)
            assertTrue(checkpoint!!.apply())
            fitter.flushDebounceForTesting()
            val accepted = geometry()
            val writes = forwardWrites
            val layouts = splitter.layoutCalls

            checkpoint!!.commit()
            checkpoint!!.releaseResources()
            checkpoint!!.commit()
            checkpoint!!.releaseResources()

            assertEquals(accepted, geometry())
            assertEquals(writes, forwardWrites)
            assertEquals(layouts, splitter.layoutCalls)
            assertTrue(posted.size <= 1)
            while (posted.isNotEmpty()) posted.removeFirst().run()
            assertEquals(accepted, geometry())
            assertEquals(writes, forwardWrites)
        }
    }

    @Test
    fun `foreign resize target anchor disposal and LAF changes invalidate before or after apply`() {
        for (afterApply in listOf(false, true)) {
            for (drift in WidthDrift.entries) {
                withFixture {
                    checkpoint = prepare(PanelWidthMode.FIXED)
                    if (afterApply) assertTrue(checkpoint!!.apply())
                    when (drift) {
                        WidthDrift.RESIZE -> setRawExtent(470)
                        WidthDrift.TARGET -> every { manager.getToolWindow("Project") } returns mockk(relaxed = true)
                        WidthDrift.ANCHOR -> every { toolWindow.anchor } returns ToolWindowAnchor.BOTTOM
                        WidthDrift.DISPOSAL -> every { project.isDisposed } returns true
                        WidthDrift.LAF -> UIManager.setLookAndFeel(MetalLookAndFeel())
                    }
                    val foreign = geometry()
                    val writes = forwardWrites
                    if (afterApply) checkpoint!!.restore() else assertFalse(checkpoint!!.apply())
                    checkpoint!!.commit()
                    checkpoint!!.releaseResources()
                    assertEquals(AccentWriteState.RELINQUISHED, checkpoint!!.state)
                    assertEquals(foreign, geometry())
                    assertEquals(writes, forwardWrites)
                }
            }
        }
    }

    @Test
    fun `missing created content and hidden native baseline are explicit unavailable capabilities`() {
        withFixture {
            every { toolWindow.contentManagerIfCreated } returns null
            assertNull(fitter.prepareWidth(PanelWidthMode.FIXED, 500, 350, 100))
            verify(exactly = 0) { toolWindow.contentManager }
            every { toolWindow.contentManagerIfCreated } returns contentManager
            every { toolWindow.isVisible } returns false
            host.isVisible = false
            assertEquals(0, rawExtent())
            assertNull(fitter.prepareWidth(PanelWidthMode.FIXED, 500, 350, 100))
            assertEquals(0, forwardWrites)
            verify(exactly = 0) { toolWindow.show(any()) }
            verify(exactly = 0) { toolWindow.activate(any(), any(), any()) }
        }
    }

    @Test
    fun `tentative inactive modes retain listener order and cancel preserves later foreign listeners`() {
        for (mode in listOf(PanelWidthMode.DEFAULT, PanelWidthMode.FIXED)) {
            withFixture {
                fitter.installExpansionListener()
                var foreignCalls = 0
                tree.addTreeExpansionListener(
                    object : TreeExpansionListener {
                        override fun treeExpanded(event: TreeExpansionEvent) {
                            foreignCalls++
                        }

                        override fun treeCollapsed(event: TreeExpansionEvent) {
                            foreignCalls++
                        }
                    },
                )
                val beforeListeners = tree.treeExpansionListeners.toList()
                checkpoint = prepare(mode)
                assertEquals(beforeListeners, tree.treeExpansionListeners.toList())
                assertTrue(checkpoint!!.apply())
                val candidate = geometry()
                val writes = forwardWrites
                assertEquals(beforeListeners, tree.treeExpansionListeners.toList())

                val event = TreeExpansionEvent(tree, TreePath(tree.model.root))
                for (listener in tree.treeExpansionListeners) listener.treeExpanded(event)
                fitter.flushDebounceForTesting()
                assertEquals(1, foreignCalls)
                assertEquals(writes, forwardWrites)
                assertEquals(candidate, geometry())

                checkpoint!!.restore()

                assertEquals(beforeListeners, tree.treeExpansionListeners.toList())
            }
        }
    }

    @Test
    fun `DEFAULT captures listener cleanup when native window or created content disappeared or became hidden`() {
        for (missing in MissingGeometry.entries) {
            withFixture {
                val platformListeners = tree.treeExpansionListeners.toList()
                fitter.installExpansionListener()
                val own = tree.treeExpansionListeners.single { it !in platformListeners }
                val foreign =
                    object : TreeExpansionListener {
                        override fun treeExpanded(event: TreeExpansionEvent) = Unit

                        override fun treeCollapsed(event: TreeExpansionEvent) = Unit
                    }
                tree.addTreeExpansionListener(foreign)
                when (missing) {
                    MissingGeometry.WINDOW -> {
                        every { manager.getToolWindow("Project") } returns null
                    }

                    MissingGeometry.CONTENT -> {
                        every { toolWindow.contentManagerIfCreated } returns null
                    }

                    MissingGeometry.HIDDEN -> {
                        every { toolWindow.isVisible } returns false
                        host.isVisible = false
                    }
                }
                val before = geometry()
                val beforeListeners = tree.treeExpansionListeners.toList()
                checkpoint = prepare(PanelWidthMode.DEFAULT)
                assertEquals(beforeListeners, tree.treeExpansionListeners.toList())
                assertEquals(before, geometry())
                assertTrue(checkpoint!!.apply())
                assertEquals(beforeListeners, tree.treeExpansionListeners.toList())
                checkpoint!!.restore()
                assertEquals(beforeListeners, tree.treeExpansionListeners.toList())
                assertEquals(before, geometry())

                checkpoint = prepare(PanelWidthMode.DEFAULT)
                assertTrue(checkpoint!!.apply())
                checkpoint!!.commit()
                checkpoint!!.releaseResources()
                assertEquals(before, geometry())
                assertEquals(0, forwardWrites)
                assertEquals(beforeListeners, tree.treeExpansionListeners.toList())
                while (posted.isNotEmpty()) posted.removeFirst().run()
                assertEquals(beforeListeners.filter { it !== own }, tree.treeExpansionListeners.toList())
                assertEquals(before, geometry())
                assertEquals(0, forwardWrites)
                verify(exactly = 0) { toolWindow.show(any()) }
                verify(exactly = 0) { toolWindow.activate(any(), any(), any()) }
            }
        }
    }

    @Test
    fun `horizontal docked anchors retain legacy content width delta and restore the native height slot`() {
        for (anchor in listOf(ToolWindowAnchor.TOP, ToolWindowAnchor.BOTTOM)) {
            withFixture(anchor, rawExtent = 800) {
                val before = geometry()
                val beforeWidth = host.width
                val contentWidth = content.width
                checkpoint = assertNotNull(fitter.prepareWidth(PanelWidthMode.FIXED, 1000, 900, 100))
                assertTrue(checkpoint!!.apply())
                assertEquals(900 - contentWidth, lastDelta)
                assertEquals(beforeWidth, host.width)
                assertNotEquals(before, geometry())
                checkpoint!!.restore()
                assertEquals(before, geometry())
            }
        }
    }

    @Test
    fun `receipt outcomes distinguish foreign geometry from independently restored listeners and vice versa`() {
        for (foreignGeometry in listOf(false, true)) {
            withFixture {
                fitter.installExpansionListener()
                val beforeListeners = tree.treeExpansionListeners.toList()
                val beforeGeometry = geometry()
                val change = assertNotNull(fitter.prepareWidth(PanelWidthMode.FIXED, 500, 350, 100))
                checkpoint = change
                assertTrue(change.apply())
                if (foreignGeometry) {
                    setRawExtent(470)
                } else {
                    tree.removeTreeExpansionListener(change.listenerRecords.single().listener)
                }
                val foreign = geometry()
                change.restore()
                assertEquals(AccentWriteState.RELINQUISHED, change.state)
                if (foreignGeometry) {
                    assertEquals(AccentWriteState.RELINQUISHED, change.records.single().state)
                    assertEquals(AccentWriteState.RESTORED, change.listenerRecords.single().state)
                    assertEquals(change.listenerRecords.single().before, change.listenerRecords.single().lastWritten)
                    assertEquals(beforeListeners, tree.treeExpansionListeners.toList())
                    assertEquals(foreign, geometry())
                } else {
                    assertEquals(AccentWriteState.RESTORED, change.records.single().state)
                    assertEquals(change.records.single().before, change.records.single().lastWritten)
                    assertEquals(AccentWriteState.RELINQUISHED, change.listenerRecords.single().state)
                    assertEquals(beforeGeometry, geometry())
                }
            }
        }
    }

    @Test
    fun `disposal cleanup requested during a hold retires its own retained listener after rollback settlement`() {
        withFixture {
            val platformListeners = tree.treeExpansionListeners.toList()
            fitter.installExpansionListener()
            val own = tree.treeExpansionListeners.single { it !in platformListeners }
            val foreign =
                object : TreeExpansionListener {
                    override fun treeExpanded(event: TreeExpansionEvent) = Unit

                    override fun treeCollapsed(event: TreeExpansionEvent) = Unit
                }
            tree.addTreeExpansionListener(foreign)
            val beforeListeners = tree.treeExpansionListeners.toList()
            checkpoint = prepare(PanelWidthMode.FIXED)
            assertTrue(checkpoint!!.apply())
            val candidate = geometry()
            val writes = forwardWrites
            every { project.isDisposed } returns true
            fitter.removeExpansionListener()
            checkpoint!!.restore()
            assertFalse(checkpoint!!.hasPending)
            while (posted.isNotEmpty()) posted.removeFirst().run()
            assertEquals(beforeListeners.filter { it !== own }, tree.treeExpansionListeners.toList())
            assertEquals(candidate, geometry())
            assertEquals(writes, forwardWrites)
        }
    }

    private fun withFixture(
        anchor: ToolWindowAnchor = ToolWindowAnchor.LEFT,
        rawExtent: Int = 320,
        action: WidthFixture.() -> Unit,
    ) {
        SwingUtilities.invokeAndWait {
            val laf = UIManager.getLookAndFeel()
            val fixture = WidthFixture(anchor, rawExtent)
            try {
                fixture.action()
            } finally {
                fixture.checkpoint?.let { if (it.hasPending) it.restore() }
                fixture.fitter.removeExpansionListener()
                UIManager.setLookAndFeel(laf)
                every { project.isDisposed } returns false
                posted.clear()
            }
        }
    }

    private inner class WidthFixture(
        anchor: ToolWindowAnchor,
        initialWidth: Int,
    ) {
        val isFirst = anchor == ToolWindowAnchor.LEFT || anchor == ToolWindowAnchor.TOP
        private val vertical = anchor == ToolWindowAnchor.TOP || anchor == ToolWindowAnchor.BOTTOM
        val tree = JTree()
        val content = JPanel(BorderLayout()).apply { add(tree, BorderLayout.CENTER) }
        val host =
            JPanel(BorderLayout()).apply {
                border = BorderFactory.createEmptyBorder(0, 7, 0, 11)
                add(content, BorderLayout.CENTER)
            }
        private val center = JPanel()
        private val other = JPanel()
        val splitter =
            FailingSplitter(vertical).apply {
                firstComponent = if (isFirst) host else other
                innerComponent = center
                lastComponent = if (isFirst) other else host
                setSize(1000, 600)
                firstSize = if (isFirst) initialWidth else 300
                lastSize = if (isFirst) 300 else initialWidth
                doLayout()
            }
        val toolWindow = mockk<ToolWindowEx>(relaxed = true)
        val contentManager = mockk<ContentManager>(relaxed = true)
        val fitter = ToolWindowAutoFitter(project, "Project", 100)
        var checkpoint: AccentWriteCheckpoint? = null
        var forwardWrites = 0
        var lastDelta = 0
        var failAfterStretch = false

        init {
            host.doLayout()
            content.doLayout()
            val tab = mockk<Content> { every { component } returns content }
            every { contentManager.component } returns content
            every { contentManager.contents } returns arrayOf(tab)
            every { manager.getToolWindow("Project") } returns toolWindow
            every { manager.toolWindowIdSet } returns setOf("Project")
            every { toolWindow.component } returns content
            every { toolWindow.contentManager } returns contentManager
            every { toolWindow.contentManagerIfCreated } returns contentManager
            every { toolWindow.type } returns ToolWindowType.DOCKED
            every { toolWindow.anchor } returns anchor
            every { toolWindow.isVisible } returns true
            every { toolWindow.isDisposed } returns false
            // SDK 2025.1 ToolWindowPane.stretch: clamp host size + delta, then set the native side.
            every { toolWindow.stretchWidth(any()) } answers {
                forwardWrites++
                lastDelta = firstArg()
                val currentExtent = if (vertical) host.height else host.width
                setRawExtent(
                    (currentExtent + lastDelta).coerceIn(splitter.getMinSize(isFirst), splitter.getMaxSize(isFirst)),
                )
                if (failAfterStretch) {
                    failAfterStretch = false
                    error("Forward setter mutated before failing")
                }
            }
        }

        fun prepare(
            mode: PanelWidthMode,
            minimum: Int = 100,
            maximum: Int = 500,
        ): AccentWriteCheckpoint = assertNotNull(fitter.prepareWidth(mode, maximum, 350, minimum))

        fun rawExtent(): Int = if (isFirst) splitter.firstSize else splitter.lastSize

        fun setRawExtent(value: Int) {
            if (isFirst) splitter.firstSize = value else splitter.lastSize = value
        }

        fun geometry(): NativeGeometry =
            NativeGeometry(splitter.firstSize, splitter.lastSize, host.bounds, center.bounds, other.bounds)
    }

    private class FailingSplitter(
        vertical: Boolean = false,
    ) : ThreeComponentsSplitter(vertical) {
        var layoutCalls = 0
        var failNextLayout = false

        override fun doLayout() {
            super.doLayout()
            layoutCalls++
            if (failNextLayout) {
                failNextLayout = false
                error("Native side setter mutated before failing")
            }
        }
    }

    private data class NativeGeometry(
        val firstSize: Int,
        val lastSize: Int,
        val host: Rectangle,
        val center: Rectangle,
        val other: Rectangle,
    )

    private enum class MissingGeometry { WINDOW, CONTENT, HIDDEN }

    private enum class WidthDrift { RESIZE, TARGET, ANCHOR, DISPOSAL, LAF }
}
