package dev.ayuislands.toolwindow

import com.intellij.openapi.actionSystem.ActionToolbar
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ThreeComponentsSplitter
import com.intellij.openapi.wm.ToolWindowAnchor
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.openapi.wm.ToolWindowType
import com.intellij.openapi.wm.ex.ToolWindowEx
import com.intellij.openapi.wm.impl.InternalDecorator
import com.intellij.ui.content.Content
import com.intellij.ui.content.ContentManager
import dev.ayuislands.accent.AccentWriteCheckpoint
import dev.ayuislands.accent.AccentWriteState
import dev.ayuislands.settings.PanelWidthMode
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import java.awt.BorderLayout
import javax.swing.BorderFactory
import javax.swing.JLayeredPane
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

class ToolWindowSlidingWidthTest {
    private lateinit var project: Project
    private lateinit var manager: ToolWindowManager

    @BeforeTest
    fun setUp() {
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
    fun `sliding forward keeps SDK anchor edge and rollback restores exact public decorator bounds`() {
        for (anchor in anchors) {
            withSliding(anchor) {
                val before = decorator.bounds
                val writes = decorator.boundsWrites
                checkpoint = prepare()
                assertEquals(before, decorator.bounds)
                assertEquals(writes, decorator.boundsWrites)
                assertTrue(checkpoint!!.apply())
                assertNotEquals(before, decorator.bounds)
                assertEquals(desiredWidth - content.width, lastDelta)
                when (anchor) {
                    ToolWindowAnchor.RIGHT -> assertEquals(before.x + before.width, decorator.x + decorator.width)
                    ToolWindowAnchor.BOTTOM -> assertEquals(before.y + before.height, decorator.y + decorator.height)
                    ToolWindowAnchor.LEFT, ToolWindowAnchor.TOP -> assertEquals(before.location, decorator.location)
                    else -> error("Unsupported anchor")
                }
                checkpoint!!.restore()
                assertEquals(before, decorator.bounds)
                verify(exactly = 0) { toolWindow.show(any()) }
                verify(exactly = 0) { toolWindow.activate(any(), any(), any()) }
            }
        }
    }

    @Test
    fun `sliding mutation then throw stays retained and foreign peer movement is never overwritten`() {
        withSliding(ToolWindowAnchor.RIGHT) {
            val before = decorator.bounds
            checkpoint = prepare()
            decorator.failNextBounds = true
            assertFailsWith<IllegalStateException> { checkpoint!!.apply() }
            assertTrue(checkpoint!!.hasPending)
            decorator.failNextBounds = true
            assertFailsWith<IllegalStateException> { checkpoint!!.restore() }
            assertTrue(checkpoint!!.hasPending)
            checkpoint!!.restore()
            assertEquals(before, decorator.bounds)
            assertFalse(checkpoint!!.hasPending)

            checkpoint = prepare()
            assertTrue(checkpoint!!.apply())
            decorator.setLocation(decorator.x - 23, decorator.y + 13)
            val foreign = decorator.bounds
            val writes = decorator.boundsWrites
            checkpoint!!.restore()
            checkpoint!!.commit()
            checkpoint!!.releaseResources()
            assertEquals(AccentWriteState.RELINQUISHED, checkpoint!!.state)
            assertEquals(foreign, decorator.bounds)
            assertEquals(writes, decorator.boundsWrites)
        }
    }

    @Test
    fun `replacement public decorator and reparented native peer invalidate captured ownership`() {
        for (replaceGetter in listOf(false, true)) {
            withSliding(ToolWindowAnchor.LEFT) {
                checkpoint = prepare()
                assertTrue(checkpoint!!.apply())
                if (replaceGetter) {
                    every { toolWindow.decorator } returns TestDecorator()
                } else {
                    JLayeredPane().add(decorator, JLayeredPane.PALETTE_LAYER, -1)
                }
                val foreign = decorator.bounds
                val writes = decorator.boundsWrites
                checkpoint!!.restore()
                assertEquals(AccentWriteState.RELINQUISHED, checkpoint!!.state)
                assertEquals(foreign, decorator.bounds)
                assertEquals(writes, decorator.boundsWrites)
            }
        }
    }

    private fun withSliding(
        anchor: ToolWindowAnchor,
        action: SlidingFixture.() -> Unit,
    ) {
        SwingUtilities.invokeAndWait {
            val fixture = SlidingFixture(anchor)
            try {
                fixture.action()
            } finally {
                fixture.checkpoint?.let { if (it.hasPending) it.restore() }
                fixture.fitter.removeExpansionListener()
            }
        }
    }

    private inner class SlidingFixture(
        private val anchor: ToolWindowAnchor,
    ) {
        private val vertical = anchor == ToolWindowAnchor.TOP || anchor == ToolWindowAnchor.BOTTOM
        private val first = anchor == ToolWindowAnchor.LEFT || anchor == ToolWindowAnchor.TOP
        val desiredWidth = if (vertical) 900 else 350
        val content = JPanel(BorderLayout()).apply { add(JTree(), BorderLayout.CENTER) }
        val decorator =
            TestDecorator().apply {
                layout = BorderLayout()
                border = BorderFactory.createEmptyBorder(5, 7, 9, 11)
                add(content, BorderLayout.CENTER)
                when (anchor) {
                    ToolWindowAnchor.LEFT -> setBounds(0, 0, 320, 600)
                    ToolWindowAnchor.RIGHT -> setBounds(680, 0, 320, 600)
                    ToolWindowAnchor.TOP -> setBounds(0, 0, 1000, 250)
                    ToolWindowAnchor.BOTTOM -> setBounds(0, 350, 1000, 250)
                    else -> error("Unsupported anchor")
                }
            }
        private val splitter = ThreeComponentsSplitter(vertical).apply { setSize(1000, 600) }
        private val layered =
            JLayeredPane().apply {
                setSize(1000, 600)
                add(splitter, JLayeredPane.DEFAULT_LAYER, -1)
                add(decorator, JLayeredPane.PALETTE_LAYER, -1)
            }
        val toolWindow = mockk<ToolWindowEx>(relaxed = true)
        val fitter = ToolWindowAutoFitter(project, "Project", 100)
        var checkpoint: AccentWriteCheckpoint? = null
        var lastDelta = 0

        init {
            check(decorator.parent === layered)
            assertEquals(JLayeredPane.DEFAULT_LAYER.toInt(), layered.getLayer(splitter))
            assertEquals(JLayeredPane.PALETTE_LAYER.toInt(), layered.getLayer(decorator))
            decorator.doLayout()
            content.doLayout()
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
            every { toolWindow.decorator } returns decorator
            every { toolWindow.type } returns ToolWindowType.SLIDING
            every { toolWindow.anchor } returns anchor
            every { toolWindow.isVisible } returns true
            every { toolWindow.isDisposed } returns false
            // SDK 2025.1 anchor-axis clamp and public Component setter boundary.
            every { toolWindow.stretchWidth(any()) } answers {
                lastDelta = firstArg()
                val extent = if (vertical) decorator.height else decorator.width
                val next = maxOf(splitter.getMinSize(first), minOf(splitter.getMaxSize(first), extent + lastDelta))
                val bounds = decorator.bounds
                if (vertical) {
                    if (!first) bounds.y -= next - bounds.height
                    bounds.height = next
                } else {
                    if (!first) bounds.x -= next - bounds.width
                    bounds.width = next
                }
                decorator.bounds = bounds
            }
        }

        fun prepare(): AccentWriteCheckpoint =
            assertNotNull(fitter.prepareWidth(PanelWidthMode.FIXED, 1000, desiredWidth, 100))
    }

    /** The SDK's public, unannotated base returned by ToolWindowEx.getDecorator(). */
    private class TestDecorator : InternalDecorator() {
        var boundsWrites = 0
        var failNextBounds = false
        private var headerShown = true

        override fun getHeaderToolbar(): ActionToolbar = mockk(relaxed = true)

        override fun getHeaderHeight(): Int = 0

        override fun isHeaderVisible(): Boolean = headerShown

        override fun setHeaderVisible(value: Boolean) {
            headerShown = value
        }

        override fun setBounds(
            x: Int,
            y: Int,
            width: Int,
            height: Int,
        ) {
            super.setBounds(x, y, width, height)
            boundsWrites++
            if (failNextBounds) {
                failNextBounds = false
                error("Sliding peer setter mutated before failing")
            }
        }
    }

    companion object {
        private val anchors =
            listOf(ToolWindowAnchor.LEFT, ToolWindowAnchor.RIGHT, ToolWindowAnchor.TOP, ToolWindowAnchor.BOTTOM)
    }
}
