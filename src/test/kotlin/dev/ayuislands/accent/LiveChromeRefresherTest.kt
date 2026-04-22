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
import javax.swing.JPanel
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Tests for [LiveChromeRefresher] — the Level 2 Gap-4 helper that walks live
 * Swing peers and sets their background directly because UIManager writes do
 * not propagate to already-rendered components.
 *
 * Tree-walk tests use a mock Container tree of [JPanel] subclasses with
 * class names crafted to match (or not match) the runtime string-match
 * lookup used for internal platform types.
 */
class LiveChromeRefresherTest {
    private val targetColor = Color(0x11, 0x22, 0x33)

    @AfterTest
    fun tearDown() {
        unmockkAll()
    }

    // --- refreshByClassName / clearByClassName ---

    /** Subclass so its FQN differs from plain JPanel — used for class-name string match. */
    private open class StripeLike : JPanel()

    private open class ToolbarLike : JPanel()

    @Test
    fun `refreshByClassName sets background on matching components only`() {
        val matching = StripeLike()
        val nonMatching = JPanel()
        val parent =
            JPanel().apply {
                add(matching)
                add(nonMatching)
            }

        LiveChromeRefresher.refreshOnTree(parent, StripeLike::class.java.name, targetColor)

        assertEquals(targetColor, matching.background)
        // JPanel default LAF background is non-null; we assert we didn't overwrite it with our target.
        assertEquals(false, nonMatching.background == targetColor)
    }

    @Test
    fun `refreshByClassName descends into nested containers`() {
        val deep = StripeLike()
        val mid = JPanel().apply { add(deep) }
        val root = JPanel().apply { add(mid) }

        LiveChromeRefresher.refreshOnTree(root, StripeLike::class.java.name, targetColor)

        assertEquals(targetColor, deep.background)
    }

    @Test
    fun `refreshByClassName ignores different class-name matches`() {
        val unrelated = ToolbarLike()
        val root = JPanel().apply { add(unrelated) }

        LiveChromeRefresher.refreshOnTree(root, StripeLike::class.java.name, targetColor)

        assertEquals(false, unrelated.background == targetColor)
    }

    @Test
    fun `clearByClassName sets background to null on matching components`() {
        val matching = StripeLike().apply { background = Color.RED }
        val parent = JPanel().apply { add(matching) }

        LiveChromeRefresher.clearOnTree(parent, StripeLike::class.java.name)

        assertNull(matching.background)
    }

    @Test
    fun `clearByClassName leaves non-matching components untouched`() {
        val untouched = JPanel().apply { background = Color.BLUE }
        val matching = StripeLike().apply { background = Color.RED }
        val root =
            JPanel().apply {
                add(untouched)
                add(matching)
            }

        LiveChromeRefresher.clearOnTree(root, StripeLike::class.java.name)

        assertEquals(Color.BLUE, untouched.background)
        assertNull(matching.background)
    }

    @Test
    fun `clearByClassName descends recursively and clears deeply-nested matches`() {
        val deep = StripeLike().apply { background = Color.MAGENTA }
        val mid = JPanel().apply { add(deep) }
        val root = JPanel().apply { add(mid) }

        LiveChromeRefresher.clearOnTree(root, StripeLike::class.java.name)

        assertNull(deep.background)
    }

    // --- refreshStatusBar / clearStatusBar ---

    @Test
    fun `refreshStatusBar sets background on resolved status bar component`() {
        val project = mockUsableProject()
        val statusBarComponent = JPanel()
        val statusBar =
            mockk<StatusBar>(relaxed = true) {
                every { component } returns statusBarComponent
            }
        val windowManager =
            mockk<WindowManager>(relaxed = true) {
                every { getStatusBar(project) } returns statusBar
            }
        mockkStatic(WindowManager::class)
        every { WindowManager.getInstance() } returns windowManager
        mockProjectManagerOpenProjects(project)

        LiveChromeRefresher.refreshStatusBar(targetColor)

        assertEquals(targetColor, statusBarComponent.background)
    }

    @Test
    fun `clearStatusBar sets background to null on resolved status bar component`() {
        val project = mockUsableProject()
        val statusBarComponent = JPanel().apply { background = Color.RED }
        val statusBar =
            mockk<StatusBar>(relaxed = true) {
                every { component } returns statusBarComponent
            }
        val windowManager =
            mockk<WindowManager>(relaxed = true) {
                every { getStatusBar(project) } returns statusBar
            }
        mockkStatic(WindowManager::class)
        every { WindowManager.getInstance() } returns windowManager
        mockProjectManagerOpenProjects(project)

        LiveChromeRefresher.clearStatusBar()

        assertNull(statusBarComponent.background)
    }

    @Test
    fun `refreshStatusBar is a no-op when WindowManager returns null status bar`() {
        val project = mockUsableProject()
        val windowManager =
            mockk<WindowManager>(relaxed = true) {
                every { getStatusBar(project) } returns null
            }
        mockkStatic(WindowManager::class)
        every { WindowManager.getInstance() } returns windowManager
        mockProjectManagerOpenProjects(project)

        // No assertion needed beyond "does not throw".
        LiveChromeRefresher.refreshStatusBar(targetColor)
    }

    @Test
    fun `refreshStatusBar skips unusable projects (disposed or default)`() {
        val disposedProject =
            mockk<Project>(relaxed = true) {
                every { isDefault } returns false
                every { isDisposed } returns true
            }
        val windowManager = mockk<WindowManager>(relaxed = true)
        mockkStatic(WindowManager::class)
        every { WindowManager.getInstance() } returns windowManager
        mockProjectManagerOpenProjects(disposedProject)

        LiveChromeRefresher.refreshStatusBar(targetColor)

        // Never resolved a status bar because project is unusable.
        io.mockk.verify(exactly = 0) { windowManager.getStatusBar(any<Project>()) }
    }

    @Test
    fun `refreshStatusBar is a no-op when status bar component is null`() {
        val project = mockUsableProject()
        val statusBar =
            mockk<StatusBar>(relaxed = true) {
                every { component } returns null
            }
        val windowManager =
            mockk<WindowManager>(relaxed = true) {
                every { getStatusBar(project) } returns statusBar
            }
        mockkStatic(WindowManager::class)
        every { WindowManager.getInstance() } returns windowManager
        mockProjectManagerOpenProjects(project)

        // No throw — null component path is safe.
        LiveChromeRefresher.refreshStatusBar(targetColor)
    }

    // --- helpers ---

    private fun mockUsableProject(): Project =
        mockk(relaxed = true) {
            every { isDefault } returns false
            every { isDisposed } returns false
        }

    private fun mockProjectManagerOpenProjects(vararg projects: Project) {
        val projectManager =
            mockk<ProjectManager>(relaxed = true) {
                every { openProjects } returns projects.toList().toTypedArray()
            }
        mockkStatic(ProjectManager::class)
        every { ProjectManager.getInstance() } returns projectManager
    }

    @Test
    fun `refreshByClassName walks every top-level window in Window getWindows`() {
        // Smoke test to ensure the public entry point does not throw when walking the real
        // AWT window list in a headless test JVM (getWindows returns a possibly-empty array).
        LiveChromeRefresher.refreshByClassName("non.existent.ClassName.Z", targetColor)
        LiveChromeRefresher.clearByClassName("non.existent.ClassName.Z")
    }
}
