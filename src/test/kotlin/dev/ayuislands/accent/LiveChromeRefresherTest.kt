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
import java.awt.Window
import javax.swing.JPanel
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

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

    /**
     * JPanel subclass that captures every [setBackground] call so we can assert
     * direct invocations regardless of Swing's parent-chain fallback behaviour in
     * [java.awt.Component.getBackground] — a non-opaque JPanel with no parent may
     * still report a non-null background resolved through the LAF / peer chain.
     */
    private open class TrackingPanel : JPanel() {
        var lastSetBackground: Color? = null
        var setBackgroundCallCount: Int = 0
        var wasExplicitlyClearedToNull: Boolean = false

        override fun setBackground(bg: Color?) {
            super.setBackground(bg)
            setBackgroundCallCount++
            lastSetBackground = bg
            if (bg == null) wasExplicitlyClearedToNull = true
        }
    }

    private open class StripeLike : TrackingPanel()

    private open class ToolbarLike : TrackingPanel()

    @Test
    fun `refreshByClassName sets background on matching components only`() {
        val matching = StripeLike()
        val nonMatching = TrackingPanel()
        val parent =
            JPanel().apply {
                add(matching)
                add(nonMatching)
            }
        val matchingBaseline = matching.setBackgroundCallCount
        val nonMatchingBaseline = nonMatching.setBackgroundCallCount

        LiveChromeRefresher.refreshOnTree(parent, StripeLike::class.java.name, targetColor)

        assertEquals(targetColor, matching.lastSetBackground)
        assertEquals(matchingBaseline + 1, matching.setBackgroundCallCount)
        assertEquals(
            nonMatchingBaseline,
            nonMatching.setBackgroundCallCount,
            "non-matching peer must not be touched during tree walk",
        )
    }

    @Test
    fun `refreshByClassName descends into nested containers`() {
        val deep = StripeLike()
        val mid = JPanel().apply { add(deep) }
        val root = JPanel().apply { add(mid) }

        LiveChromeRefresher.refreshOnTree(root, StripeLike::class.java.name, targetColor)

        assertEquals(targetColor, deep.lastSetBackground)
    }

    @Test
    fun `refreshByClassName ignores different class-name matches`() {
        val unrelated = ToolbarLike()
        val root = JPanel().apply { add(unrelated) }
        val baseline = unrelated.setBackgroundCallCount

        LiveChromeRefresher.refreshOnTree(root, StripeLike::class.java.name, targetColor)

        assertEquals(baseline, unrelated.setBackgroundCallCount, "class-name mismatch must skip peer")
    }

    @Test
    fun `clearByClassName sets background to null on matching components`() {
        val matching = StripeLike().apply { background = Color.RED }
        val parent = JPanel().apply { add(matching) }

        LiveChromeRefresher.clearOnTree(parent, StripeLike::class.java.name)

        assertEquals(true, matching.wasExplicitlyClearedToNull)
        assertNull(matching.lastSetBackground)
    }

    @Test
    fun `clearByClassName leaves non-matching components untouched`() {
        val untouched = TrackingPanel().apply { background = Color.BLUE }
        val matching = StripeLike().apply { background = Color.RED }
        val root =
            JPanel().apply {
                add(untouched)
                add(matching)
            }
        val untouchedSetCountBeforeWalk = untouched.setBackgroundCallCount

        LiveChromeRefresher.clearOnTree(root, StripeLike::class.java.name)

        assertEquals(
            untouchedSetCountBeforeWalk,
            untouched.setBackgroundCallCount,
            "non-matching peer must see zero setBackground calls during walk",
        )
        assertEquals(true, matching.wasExplicitlyClearedToNull)
    }

    @Test
    fun `clearByClassName descends recursively and clears deeply-nested matches`() {
        val deep = StripeLike().apply { background = Color.MAGENTA }
        val mid = JPanel().apply { add(deep) }
        val root = JPanel().apply { add(mid) }

        LiveChromeRefresher.clearOnTree(root, StripeLike::class.java.name)

        assertEquals(true, deep.wasExplicitlyClearedToNull)
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

    // --- refreshOnTreeInsideAncestor / clearOnTreeInsideAncestor (Round 2 A-1) ---
    //
    // Ancestor-constrained variants prevent shared peer types (OnePixelDivider) from being
    // tinted IDE-wide — only instances inside a specific container ancestor are touched.

    private open class DividerLike : TrackingPanel()

    private open class ToolWindowDecoratorLike : JPanel()

    private open class EditorSplitterLike : JPanel()

    @Test
    fun `refreshOnTreeInsideAncestor mutates divider INSIDE tool-window ancestor`() {
        val targetDivider = DividerLike()
        val decorator = ToolWindowDecoratorLike().apply { add(targetDivider) }
        val root = JPanel().apply { add(decorator) }
        val baseline = targetDivider.setBackgroundCallCount

        LiveChromeRefresher.refreshOnTreeInsideAncestor(
            root,
            DividerLike::class.java.name,
            ToolWindowDecoratorLike::class.java.name,
            targetColor,
        )

        assertEquals(targetColor, targetDivider.lastSetBackground)
        assertEquals(baseline + 1, targetDivider.setBackgroundCallCount)
    }

    @Test
    fun `refreshOnTreeInsideAncestor leaves divider OUTSIDE tool-window ancestor untouched`() {
        val siblingDivider = DividerLike()
        val editorSplitter = EditorSplitterLike().apply { add(siblingDivider) }
        val root = JPanel().apply { add(editorSplitter) }
        val baseline = siblingDivider.setBackgroundCallCount

        LiveChromeRefresher.refreshOnTreeInsideAncestor(
            root,
            DividerLike::class.java.name,
            ToolWindowDecoratorLike::class.java.name,
            targetColor,
        )

        assertEquals(
            baseline,
            siblingDivider.setBackgroundCallCount,
            "divider outside the tool-window decorator must not be mutated",
        )
    }

    @Test
    fun `refreshOnTreeInsideAncestor finds divider deeply nested inside ancestor`() {
        val deepDivider = DividerLike()
        val midContainer = JPanel().apply { add(deepDivider) }
        val decorator = ToolWindowDecoratorLike().apply { add(midContainer) }
        val anotherIntermediate = JPanel().apply { add(decorator) }
        val root = JPanel().apply { add(anotherIntermediate) }

        LiveChromeRefresher.refreshOnTreeInsideAncestor(
            root,
            DividerLike::class.java.name,
            ToolWindowDecoratorLike::class.java.name,
            targetColor,
        )

        assertEquals(targetColor, deepDivider.lastSetBackground)
    }

    @Test
    fun `refreshOnTreeInsideAncestor handles mixed inside and outside siblings on same pass`() {
        val insideDivider = DividerLike()
        val outsideDivider = DividerLike()
        val decorator = ToolWindowDecoratorLike().apply { add(insideDivider) }
        val editorSplitter = EditorSplitterLike().apply { add(outsideDivider) }
        val root =
            JPanel().apply {
                add(decorator)
                add(editorSplitter)
            }
        val outsideBaseline = outsideDivider.setBackgroundCallCount

        LiveChromeRefresher.refreshOnTreeInsideAncestor(
            root,
            DividerLike::class.java.name,
            ToolWindowDecoratorLike::class.java.name,
            targetColor,
        )

        assertEquals(targetColor, insideDivider.lastSetBackground)
        assertEquals(
            outsideBaseline,
            outsideDivider.setBackgroundCallCount,
            "outside sibling must remain untouched while inside sibling is tinted",
        )
    }

    @Test
    fun `clearOnTreeInsideAncestor nulls divider INSIDE tool-window ancestor only`() {
        val insideDivider = DividerLike().apply { background = Color.RED }
        val outsideDivider = DividerLike().apply { background = Color.BLUE }
        val decorator = ToolWindowDecoratorLike().apply { add(insideDivider) }
        val editorSplitter = EditorSplitterLike().apply { add(outsideDivider) }
        val root =
            JPanel().apply {
                add(decorator)
                add(editorSplitter)
            }
        val outsideBaseline = outsideDivider.setBackgroundCallCount

        LiveChromeRefresher.clearOnTreeInsideAncestor(
            root,
            DividerLike::class.java.name,
            ToolWindowDecoratorLike::class.java.name,
        )

        assertEquals(true, insideDivider.wasExplicitlyClearedToNull)
        assertNull(insideDivider.lastSetBackground)
        assertEquals(
            outsideBaseline,
            outsideDivider.setBackgroundCallCount,
            "outside sibling must remain untouched during ancestor-scoped clear",
        )
    }

    @Test
    fun `refreshByClassNameInsideAncestorClass public entry does not throw in headless JVM`() {
        // Smoke test: the window walk over an empty (or near-empty) headless AWT window list
        // must complete without exceptions, just like the blind variant's smoke test above.
        LiveChromeRefresher.refreshByClassNameInsideAncestorClass(
            "non.existent.Target.Z",
            "non.existent.Ancestor.Y",
            targetColor,
        )
        LiveChromeRefresher.clearByClassNameInsideAncestorClass(
            "non.existent.Target.Z",
            "non.existent.Ancestor.Y",
        )
    }

    // --- Round 3 hotfix regression tests (C1–C4) ---
    //
    // These lock the isolation guarantees added in Phase 40 Round 3 C-1/C-2:
    // a single broken peer or window-enumeration failure must NOT abort the
    // entire chrome-refresh pass, and repeated passes over a known-broken
    // container must stay safe (the log-once latch demotes the second hit
    // to DEBUG but the walk still short-circuits cleanly).

    /**
     * Match-target subclass used by C2. Tracks a per-instance latch so one
     * sibling can throw on its first `setBackground` call while the next
     * sibling of the same runtime class keeps its normal behaviour.
     */
    private open class ThrowOnFirstSetBackgroundTracker(
        private val shouldThrow: Boolean,
    ) : TrackingPanel() {
        private var thrown: Boolean = false

        override fun setBackground(bg: Color?) {
            if (shouldThrow && !thrown) {
                thrown = true
                error("setBackground boom")
            }
            super.setBackground(bg)
        }
    }

    /**
     * Container whose `getComponents()` override throws on every invocation.
     * Used by C3 and C4 to prove the tree walk isolates container failures
     * per-subtree instead of aborting the whole pass.
     */
    private class BrokenChildrenContainer : JPanel() {
        override fun getComponents(): Array<java.awt.Component> = error("getComponents boom")
    }

    @Test
    fun `refreshByClassName returns early without throwing when Window getWindows throws`() {
        mockkStatic(Window::class)
        every { Window.getWindows() } throws IllegalStateException("enumeration boom")

        // No throw must propagate — the Round 3 C-2 guard converts the
        // enumeration failure into a WARN + early return.
        LiveChromeRefresher.refreshByClassName("dummy.FQN", Color.RED)
    }

    @Test
    fun `refreshOnTree continues visiting siblings after one node throws on setBackground`() {
        val throwingFqn = ThrowOnFirstSetBackgroundTracker::class.java.name
        val throwing = ThrowOnFirstSetBackgroundTracker(shouldThrow = true)
        val survivor = ThrowOnFirstSetBackgroundTracker(shouldThrow = false)
        val parent =
            JPanel().apply {
                add(throwing)
                add(survivor)
            }

        LiveChromeRefresher.refreshOnTree(parent, throwingFqn, Color.BLUE)

        // Surviving sibling must have been painted — the per-visit try/catch
        // in `walk` (Round 3 C-1) isolates the throwing peer from the rest.
        assertEquals(Color.BLUE, survivor.lastSetBackground)
    }

    @Test
    fun `refreshOnTree continues to sibling containers when one containers getComponents throws`() {
        val trackerFqn = TrackingPanel::class.java.name
        val tracker = TrackingPanel()
        val brokenContainer = BrokenChildrenContainer()
        val siblingContainer = JPanel().apply { add(tracker) }
        val parent =
            JPanel().apply {
                add(brokenContainer)
                add(siblingContainer)
            }

        LiveChromeRefresher.refreshOnTree(parent, trackerFqn, Color.GREEN)

        // The broken container's subtree is skipped, but the walk proceeds
        // to the next sibling and paints the tracker inside it. Without
        // the container-level try/catch (Round 3 C-1), the RuntimeException
        // would unwind the whole walk and tracker would stay untouched.
        assertEquals(Color.GREEN, tracker.lastSetBackground)
    }

    @Test
    fun `refreshOnTree survives repeated walks over a broken container`() {
        val brokenContainer = BrokenChildrenContainer()

        // Two consecutive walks must both return cleanly. The second pass
        // exercises the `brokenContainerLogged` latch demote-to-DEBUG branch
        // introduced in Round 3; the behaviour lock here is simply "no
        // throw on either call".
        LiveChromeRefresher.refreshOnTree(brokenContainer, "x", Color.RED)
        LiveChromeRefresher.refreshOnTree(brokenContainer, "x", Color.RED)
    }

    @Test
    fun `broken-container log cap is 64 and clears on overflow`() {
        // Round 3 G3 regression lock. The LOW R2-1 fix protects
        // `brokenContainerLogged` from unbounded growth with a
        // `BROKEN_CONTAINER_LOG_CAP = 64` constant plus an overflow-clear
        // branch. Either piece alone is cosmetic: raise the cap silently to
        // defeat the protection, or delete the clear branch and keep the
        // constant as dead code. Lock both at the source level since the
        // production path only fires under pathological conditions (65+
        // distinct broken Container subclasses), unreachable in unit tests.
        val source = readLiveChromeRefresherSource()
        assertTrue(
            source.contains("BROKEN_CONTAINER_LOG_CAP = 64"),
            "Cap must remain at 64 — raising it silently defeats the unbounded-growth protection",
        )
        val overflowClear =
            Regex(
                """brokenContainerLogged\.size\s*>\s*BROKEN_CONTAINER_LOG_CAP""" +
                    """\s*\)\s*\{\s*brokenContainerLogged\.clear\(\)""",
                RegexOption.DOT_MATCHES_ALL,
            )
        assertTrue(
            overflowClear.containsMatchIn(source),
            "Overflow clear branch must remain — the cap constant alone is cosmetic without it",
        )
        assertTrue(
            source.contains("resetBrokenContainerLoggedForTests"),
            "TestOnly reset hook must remain for test isolation across order-dependent runs",
        )
    }

    private fun readLiveChromeRefresherSource(): String {
        val file = java.io.File("src/main/kotlin/dev/ayuislands/accent/LiveChromeRefresher.kt")
        return file.takeIf { it.exists() }?.readText()
            ?: error("Could not locate LiveChromeRefresher.kt for source-level guard")
    }
}
