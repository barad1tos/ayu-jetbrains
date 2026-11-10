package dev.ayuislands.accent

import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.wm.StatusBar
import com.intellij.openapi.wm.WindowManager
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import java.awt.Color
import java.awt.Component
import java.awt.Window
import javax.swing.JPanel
import javax.swing.SwingUtilities
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ChromePeerCensusTest {
    @AfterTest
    fun tearDown() = unmockkAll()

    @Test
    fun `tree census keeps breadth first order without changing backgrounds or repainting`() =
        onEdt {
            val root = Peer()
            val first = Peer()
            val second = Peer()
            val nested = Peer()
            root.add(first)
            root.add(second)
            first.add(nested)
            val expected = listOf(root, first, second, nested)
            val before = expected.map { it.writes to it.paints }

            assertEquals(expected, LiveChromeRefresher.collectOnTree(root, peerTarget()))
            assertEquals(before, expected.map { it.writes to it.paints })
        }

    @Test
    fun `ancestor census excludes matching peers outside the selected container`() =
        onEdt {
            val inside = Peer()
            val outside = Peer()
            val root = JPanel()
            root.add(Host().apply { add(inside) })
            root.add(outside)
            val target =
                ChromeTarget.ByClassNameInside(
                    target = ClassFqn.require(Peer::class.java.name),
                    ancestor = ClassFqn.require(Host::class.java.name),
                )

            assertEquals(listOf(inside), LiveChromeRefresher.collectOnTree(root, target))
            assertTrue(LiveChromeRefresher.collectOnTree(inside, target).isNotEmpty())
            assertTrue(LiveChromeRefresher.collectOnTree(Peer(), target).isEmpty())
        }

    @Test
    fun `broken descendants abort census after an earlier matching peer without writing it`() =
        onEdt {
            val peer = Peer()
            val root = JPanel()
            root.add(peer)
            root.add(BrokenChildren())
            val writes = peer.writes
            val paints = peer.paints

            assertFailsWith<IllegalStateException> {
                LiveChromeRefresher.collectOnTree(root, peerTarget())
            }
            assertEquals(writes, peer.writes)
            assertEquals(paints, peer.paints)
        }

    @Test
    fun `status census keeps first occurrence order and deduplicates by identity`() =
        onEdt {
            val first = EqualPeer()
            val second = EqualPeer()
            val projects = List(3) { mockk<Project>(relaxed = true) }
            val roots = listOf(first, second, first)
            val manager = mockk<WindowManager>()
            projects.zip(roots).forEach { (project, root) ->
                every { manager.getStatusBar(project) } returns mockk<StatusBar> { every { component } returns root }
            }
            services(projects, manager)

            val peers = LiveChromeRefresher.collect(ChromeTarget.StatusBar)
            assertEquals(2, peers.size)
            assertSame(first, peers[0])
            assertSame(second, peers[1])
        }

    @Test
    fun `status census skips absent status bars and absent components`() =
        onEdt {
            val projects = List(2) { mockk<Project>(relaxed = true) }
            val manager = mockk<WindowManager>()
            every { manager.getStatusBar(projects[0]) } returns null
            every { manager.getStatusBar(projects[1]) } returns mockk<StatusBar> { every { component } returns null }
            services(projects, manager)

            assertTrue(LiveChromeRefresher.collect(ChromeTarget.StatusBar).isEmpty())
        }

    @Test
    fun `unavailable project and window services cannot be mistaken for an empty census`() =
        onEdt {
            mockkStatic(ProjectManager::class)
            every { ProjectManager.getInstance() } throws IllegalStateException("Project service unavailable")
            assertFailsWith<IllegalStateException> { LiveChromeRefresher.collect(ChromeTarget.StatusBar) }

            services(emptyList(), null)
            assertFailsWith<IllegalStateException> { LiveChromeRefresher.collect(ChromeTarget.StatusBar) }
        }

    @Test
    fun `window census filters hidden windows and deduplicates overlapping roots`() =
        onEdt {
            val first = Peer()
            val second = Peer()
            val visible = window(true, arrayOf(first, second))
            val hidden = window(false, arrayOf(Peer()))
            mockkStatic(Window::class)
            every { Window.getWindows() } returns arrayOf(hidden, visible, visible)

            assertEquals(listOf(first, second), LiveChromeRefresher.collect(peerTarget()))
        }

    @Test
    fun `window enumeration and child failures propagate without returning partial census`() =
        onEdt {
            mockkStatic(Window::class)
            every { Window.getWindows() } throws IllegalStateException("Window enumeration failed")
            assertFailsWith<IllegalStateException> { LiveChromeRefresher.collect(peerTarget()) }

            val visible = window(true, arrayOf(Peer(), BrokenChildren()))
            every { Window.getWindows() } returns arrayOf(visible)
            assertFailsWith<IllegalStateException> { LiveChromeRefresher.collect(peerTarget()) }
        }

    private fun services(
        projects: List<Project>,
        manager: WindowManager?,
    ) {
        val projectManager = mockk<ProjectManager> { every { openProjects } returns projects.toTypedArray() }
        mockkStatic(ProjectManager::class, WindowManager::class)
        every { ProjectManager.getInstance() } returns projectManager
        every { WindowManager.getInstance() } returns manager
    }

    private fun window(
        showing: Boolean,
        children: Array<Component>,
    ): Window =
        mockk {
            every { isShowing } returns showing
            every { components } returns children
        }

    private fun peerTarget(): ChromeTarget = ChromeTarget.ByClassName(ClassFqn.require(Peer::class.java.name))

    private fun onEdt(action: () -> Unit) = SwingUtilities.invokeAndWait(action)

    private class Host : JPanel()

    private class EqualPeer : JPanel() {
        override fun equals(other: Any?): Boolean = other is EqualPeer

        override fun hashCode(): Int = 0
    }

    private class BrokenChildren : JPanel() {
        override fun getComponents(): Array<Component> = error("Unreadable children")
    }

    private class Peer : JPanel() {
        var writes: Int = 0
        var paints: Int = 0

        override fun setBackground(color: Color?) {
            super.setBackground(color)
            writes++
        }

        override fun repaint() {
            paints++
        }
    }
}
