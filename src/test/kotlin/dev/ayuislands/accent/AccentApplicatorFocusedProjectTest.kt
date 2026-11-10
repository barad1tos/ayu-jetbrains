package dev.ayuislands.accent

import com.intellij.ide.impl.ProjectUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.wm.IdeFocusManager
import com.intellij.openapi.wm.IdeFrame
import com.intellij.openapi.wm.WindowManager
import com.intellij.testFramework.LoggedErrorProcessor
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import java.awt.Window
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.SwingUtilities
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Preserves the shared focus reader precedence and failure diagnostics. */
class AccentApplicatorFocusedProjectTest {
    @BeforeTest
    fun setUp() {
        mockkStatic(ProjectUtil::class)
        every { ProjectUtil.getActiveProject() } returns null
        mockkStatic(ProjectManager::class)
        // AccentApplicator is a JVM-wide object; WARN-gate flags persist across tests in
        // the same JVM. Reset before AND after so other test classes running in the same
        // fork can neither poison this suite nor inherit stale state from it.
        resetLogGates()

        // Default: empty frames + null window ancestor so `resolveFocusedProject` falls
        // through to the IdeFocusManager / ProjectManager chain. OS-active-path tests
        // override per test.
        mockkStatic(WindowManager::class)
        val windowManager =
            mockk<WindowManager> {
                every { allProjectFrames } returns emptyArray()
            }
        every { WindowManager.getInstance() } returns windowManager

        mockkStatic(SwingUtilities::class)
        every { SwingUtilities.getWindowAncestor(any()) } returns null
        every { SwingUtilities.isEventDispatchThread() } returns true
    }

    @AfterTest
    fun tearDown() {
        resetLogGates()
        unmockkAll()
    }

    private fun resetLogGates() {
        AccentApplicator.osActiveFrameFailureLogged.set(false)
        AccentApplicator.windowManagerUnavailableLogged.set(false)
    }

    @Test
    fun `resolveFocusedProject skips default and disposed projects when picking focus`() {
        val defaultProject = stubProject(isDefault = true, isDisposed = false)
        val disposedProject = stubProject(isDefault = false, isDisposed = true)
        val realFocus = stubProject(isDefault = false, isDisposed = false)
        val manager = mockk<ProjectManager>()
        every { manager.openProjects } returns arrayOf(defaultProject, disposedProject, realFocus)
        every { ProjectManager.getInstance() } returns manager

        val selected = AccentApplicator.resolveFocusedProject()

        assertSame(realFocus, selected)
    }

    @Test
    fun `resolveFocusedProject returns null when no non-default project is open`() {
        // Simulates the "Welcome screen / all projects closed" startup path. Resolver sees a null
        // project, short-circuits to the global accent, and the helper still propagates it.
        val manager = mockk<ProjectManager>()
        every { manager.openProjects } returns emptyArray()
        every { ProjectManager.getInstance() } returns manager

        val selected = AccentApplicator.resolveFocusedProject()

        assertSame(null, selected)
    }

    @Test
    fun `resolveFocusedProject prefers IdeFocusManager focused frame over openProjects order`() {
        // Locks the primary path of resolveFocusedProject: when IdeFocusManager.lastFocusedFrame
        // resolves to a real project, that wins over openProjects enumeration. Multi-window
        // bug R2 was about the OPPOSITE — silently picking the first open project regardless
        // of which window the user is actually in. This test proves the fix prefers focus
        // state.
        val focusedProject = stubProject(isDefault = false, isDisposed = false)
        val otherProject = stubProject(isDefault = false, isDisposed = false)

        val focusedFrame =
            mockk<IdeFrame> {
                every { project } returns focusedProject
            }
        val focusManager =
            mockk<IdeFocusManager> {
                every { lastFocusedFrame } returns focusedFrame
            }
        mockkStatic(IdeFocusManager::class)
        every { IdeFocusManager.getGlobalInstance() } returns focusManager

        // Both projects are in openProjects; the focused-frame project must win even if
        // it's NOT the first in enumeration order.
        val manager = mockk<ProjectManager>()
        every { manager.openProjects } returns arrayOf(otherProject, focusedProject)
        every { ProjectManager.getInstance() } returns manager

        val selected = AccentApplicator.resolveFocusedProject()

        assertSame(focusedProject, selected)
    }

    @Test
    fun `resolveFocusedProject prefers platform active project over stale focused frame`() {
        val activeProject = stubProject(isDefault = false, isDisposed = false)
        val staleFocusedProject = stubProject(isDefault = false, isDisposed = false)
        every { ProjectUtil.getActiveProject() } returns activeProject

        val staleFocusedFrame =
            mockk<IdeFrame> {
                every { project } returns staleFocusedProject
            }
        val focusManager =
            mockk<IdeFocusManager> {
                every { lastFocusedFrame } returns staleFocusedFrame
            }
        mockkStatic(IdeFocusManager::class)
        every { IdeFocusManager.getGlobalInstance() } returns focusManager

        val manager = mockk<ProjectManager>()
        every { manager.openProjects } returns arrayOf(staleFocusedProject, activeProject)
        every { ProjectManager.getInstance() } returns manager

        val selected = AccentApplicator.resolveFocusedProject()

        assertSame(activeProject, selected)
    }

    @Test
    fun `resolveFocusedProject falls back to openProjects when focused frame has null project`() {
        // IdeFrame.getProject is @Nullable on the platform; the lastFocusedFrame may itself
        // be non-null but return null for its project (welcome-frame edge). The cascade must
        // treat that the same as "no focused frame" and fall through to ProjectManager.
        val nullProjectFrame =
            mockk<IdeFrame> {
                every { project } returns null
            }
        val focusManager =
            mockk<IdeFocusManager> {
                every { lastFocusedFrame } returns nullProjectFrame
            }
        mockkStatic(IdeFocusManager::class)
        every { IdeFocusManager.getGlobalInstance() } returns focusManager

        val healthyProject = stubProject(isDefault = false, isDisposed = false)
        val manager = mockk<ProjectManager>()
        every { manager.openProjects } returns arrayOf(healthyProject)
        every { ProjectManager.getInstance() } returns manager

        val selected = AccentApplicator.resolveFocusedProject()

        assertSame(healthyProject, selected)
    }

    @Test
    fun `resolveFocusedProject falls back to openProjects when focused frame project is disposed`() {
        // Edge case in resolveFocusedProject: lastFocusedFrame returns a project that
        // disposed between focus event and our read. The takeIf { isUsable() } guard
        // discards it; the openProjects scan provides the fallback.
        val disposedProject = stubProject(isDefault = false, isDisposed = true)
        val healthyProject = stubProject(isDefault = false, isDisposed = false)

        val staleFrame =
            mockk<IdeFrame> {
                every { project } returns disposedProject
            }
        val focusManager =
            mockk<IdeFocusManager> {
                every { lastFocusedFrame } returns staleFrame
            }
        mockkStatic(IdeFocusManager::class)
        every { IdeFocusManager.getGlobalInstance() } returns focusManager

        val manager = mockk<ProjectManager>()
        every { manager.openProjects } returns arrayOf(healthyProject)
        every { ProjectManager.getInstance() } returns manager

        val selected = AccentApplicator.resolveFocusedProject()

        assertSame(healthyProject, selected)
    }

    @Test
    fun `resolveFocusedProject prefers OS-active project frame over IdeFocusManager lastFocusedFrame`() {
        // IdeFocusManager.lastFocusedFrame can still point at the previously-clicked IDE
        // frame even when the user has since alt-tabbed to the other open project. OS-level
        // window activity is the ground truth for "which project window is visible right now"
        // and must win over lastFocusedFrame when they disagree.
        val osActiveProject = stubProject(isDefault = false, isDisposed = false)
        val lastFocusedProject = stubProject(isDefault = false, isDisposed = false)

        val osActiveWindow = mockk<Window>(relaxed = true)
        every { osActiveWindow.isActive } returns true
        val osActiveComponent = JPanel()
        every { SwingUtilities.getWindowAncestor(osActiveComponent) } returns osActiveWindow
        val osActiveFrame = stubIdeFrame(osActiveProject, osActiveComponent)

        every { WindowManager.getInstance().allProjectFrames } returns arrayOf(osActiveFrame)

        val lastFocusedFrame =
            mockk<IdeFrame> {
                every { project } returns lastFocusedProject
            }
        val focusManager =
            mockk<IdeFocusManager> {
                every { this@mockk.lastFocusedFrame } returns lastFocusedFrame
            }
        mockkStatic(IdeFocusManager::class)
        every { IdeFocusManager.getGlobalInstance() } returns focusManager

        val selected = AccentApplicator.resolveFocusedProject()

        assertSame(osActiveProject, selected)
    }

    @Test
    fun `resolveFocusedProject falls back to IdeFocusManager when no project frame is OS-active`() {
        // User alt-tabbed to a non-IDE app (browser, Slack). No project frame has OS focus,
        // so the OS-active-frame path returns null and the cascade falls through to
        // IdeFocusManager.lastFocusedFrame — the best available signal for "which project
        // the user was most recently in".
        val lastFocusedProject = stubProject(isDefault = false, isDisposed = false)

        val inactiveWindow = mockk<Window>(relaxed = true)
        every { inactiveWindow.isActive } returns false
        val inactiveComponent = JPanel()
        every { SwingUtilities.getWindowAncestor(inactiveComponent) } returns inactiveWindow
        val otherProject = stubProject(isDefault = false, isDisposed = false)
        val inactiveFrame = stubIdeFrame(otherProject, inactiveComponent)

        every { WindowManager.getInstance().allProjectFrames } returns arrayOf(inactiveFrame)

        val lastFocusedFrame =
            mockk<IdeFrame> {
                every { project } returns lastFocusedProject
            }
        val focusManager =
            mockk<IdeFocusManager> {
                every { this@mockk.lastFocusedFrame } returns lastFocusedFrame
            }
        mockkStatic(IdeFocusManager::class)
        every { IdeFocusManager.getGlobalInstance() } returns focusManager

        val selected = AccentApplicator.resolveFocusedProject()

        assertSame(lastFocusedProject, selected)
    }

    @Test
    fun `resolveFocusedProject skips OS-active frame whose project is disposed and continues the cascade`() {
        // Shutdown race: a project frame is OS-active but its project has just been disposed.
        // The isUsable() guard inside the OS-active scan must drop it so the cascade reaches
        // IdeFocusManager / ProjectManager. AccentResolver.findOverride has its own isDisposed
        // early-return, but relying on that would route every mid-dispose frame through a
        // wasted lookup; drop it here and keep the cascade hygienic.
        val disposedProject = stubProject(isDefault = false, isDisposed = true)

        val osActiveWindow = mockk<Window>(relaxed = true)
        every { osActiveWindow.isActive } returns true
        val osActiveComponent = JPanel()
        every { SwingUtilities.getWindowAncestor(osActiveComponent) } returns osActiveWindow
        val disposedFrame = stubIdeFrame(disposedProject, osActiveComponent)

        every { WindowManager.getInstance().allProjectFrames } returns arrayOf(disposedFrame)

        val healthyProject = stubProject(isDefault = false, isDisposed = false)
        val manager = mockk<ProjectManager>()
        every { manager.openProjects } returns arrayOf(healthyProject)
        every { ProjectManager.getInstance() } returns manager

        val selected = AccentApplicator.resolveFocusedProject()

        assertSame(healthyProject, selected)
    }

    @Test
    fun `resolveFocusedProject falls through to IdeFocusManager when WindowManager service is null`() {
        // Early-startup or mid-shutdown edge case: the Application service registry has not
        // yet produced a WindowManager (or has just torn it down). The OS-active path must
        // degrade cleanly to the middle tier of the cascade rather than NPE out of the whole
        // apply.
        every { WindowManager.getInstance() } returns null

        val focusedProject = stubProject(isDefault = false, isDisposed = false)
        val focusedFrame =
            mockk<IdeFrame> {
                every { project } returns focusedProject
            }
        val focusManager =
            mockk<IdeFocusManager> {
                every { lastFocusedFrame } returns focusedFrame
            }
        mockkStatic(IdeFocusManager::class)
        every { IdeFocusManager.getGlobalInstance() } returns focusManager

        val selected = AccentApplicator.resolveFocusedProject()

        assertSame(focusedProject, selected)
    }

    @Test
    fun `resolveFocusedProject logs WARN once then DEBUG for repeated null WindowManager`() {
        // Sibling parity with ProjectAccentSwapService.findProjectForWindow: first occurrence
        // surfaces in user-submitted idea.log at WARN, subsequent ones degrade to DEBUG so
        // repeated Apply / LAF-change / rotation-tick calls during a prolonged null-service
        // window do not flood the log. A regression that flips the gate would either spam
        // WARNs every call or hide the first failure below the default log level.
        every { WindowManager.getInstance() } returns null

        val projectManager = mockk<ProjectManager>()
        every { projectManager.openProjects } returns emptyArray()
        every { ProjectManager.getInstance() } returns projectManager

        val capturedWarns = mutableListOf<String>()
        val processor =
            object : LoggedErrorProcessor() {
                override fun processWarn(
                    category: String,
                    message: String,
                    throwable: Throwable?,
                ): Boolean {
                    if (!message.contains("WindowManager unavailable")) return true
                    capturedWarns += message
                    return false
                }
            }

        LoggedErrorProcessor.executeWith<Throwable>(processor) {
            AccentApplicator.resolveFocusedProject()
            AccentApplicator.resolveFocusedProject()
            AccentApplicator.resolveFocusedProject()
        }

        assertEquals(
            1,
            capturedWarns.size,
            "null WindowManager must WARN once then dedup; got: $capturedWarns",
        )
        assertTrue(
            capturedWarns.single().contains("further occurrences at DEBUG"),
            "first WARN must announce the dedup contract; got: ${capturedWarns.single()}",
        )
        assertTrue(
            capturedWarns.single().contains("thread="),
            "WARN must carry thread-name context so idea.log distinguishes callers; got: ${capturedWarns.single()}",
        )
        assertFalse(
            AccentApplicator.osActiveFrameFailureLogged.get(),
            "WindowManager gate must not flip the per-frame gate; the two are independent",
        )
    }

    @Test
    fun `resolveFocusedProject skips frame with null project in OS-active scan`() {
        // IdeFrame.getProject is @Nullable on the platform — a welcome frame, a newly-opened
        // but not-yet-bound project window, or a frame mid-dispose can legitimately return
        // null. The scan must skip and keep looking; crashing here would NPE every Apply.
        val nullProjectFrame =
            mockk<IdeFrame> {
                every { project } returns null
            }

        val healthyProject = stubProject(isDefault = false, isDisposed = false)
        val healthyWindow = mockk<Window>(relaxed = true)
        every { healthyWindow.isActive } returns true
        val healthyComponent = JPanel()
        every { SwingUtilities.getWindowAncestor(healthyComponent) } returns healthyWindow
        val healthyFrame = stubIdeFrame(healthyProject, healthyComponent)

        every {
            WindowManager.getInstance().allProjectFrames
        } returns arrayOf(nullProjectFrame, healthyFrame)

        val selected = AccentApplicator.resolveFocusedProject()

        assertSame(healthyProject, selected)
    }

    @Test
    fun `resolveFocusedProject picks first OS-active frame when multiple are marked active`() {
        // Two frames briefly report `isActive = true` during alt-tab transitions before the
        // OS settles. The scan returns the first match; locking that contract red/green
        // catches a refactor that flips to `lastOrNull` or filters differently. In reality
        // only one window is active at a time, but the observable output must still be
        // deterministic under the platform's enumeration order.
        val firstProject = stubProject(isDefault = false, isDisposed = false)
        val secondProject = stubProject(isDefault = false, isDisposed = false)

        val firstWindow = mockk<Window>(relaxed = true)
        every { firstWindow.isActive } returns true
        val firstComponent = JPanel()
        every { SwingUtilities.getWindowAncestor(firstComponent) } returns firstWindow
        val firstFrame = stubIdeFrame(firstProject, firstComponent)

        val secondWindow = mockk<Window>(relaxed = true)
        every { secondWindow.isActive } returns true
        val secondComponent = JPanel()
        every { SwingUtilities.getWindowAncestor(secondComponent) } returns secondWindow
        val secondFrame = stubIdeFrame(secondProject, secondComponent)

        every {
            WindowManager.getInstance().allProjectFrames
        } returns arrayOf(firstFrame, secondFrame)

        val selected = AccentApplicator.resolveFocusedProject()

        assertSame(firstProject, selected)
    }

    @Test
    fun `resolveFocusedProject continues scan when a frame has no window ancestor`() {
        // SwingUtilities.getWindowAncestor can return null for a frame whose component has
        // been detached from the tree (rare, but observed during startup animation). The
        // null-ancestor branch must `continue` the loop, not exit with null — otherwise a
        // later active-window frame would be missed.
        val detachedProject = stubProject(isDefault = false, isDisposed = false)
        val detachedComponent = JPanel()
        every { SwingUtilities.getWindowAncestor(detachedComponent) } returns null
        val detachedFrame = stubIdeFrame(detachedProject, detachedComponent)

        val healthyProject = stubProject(isDefault = false, isDisposed = false)
        val healthyWindow = mockk<Window>(relaxed = true)
        every { healthyWindow.isActive } returns true
        val healthyComponent = JPanel()
        every { SwingUtilities.getWindowAncestor(healthyComponent) } returns healthyWindow
        val healthyFrame = stubIdeFrame(healthyProject, healthyComponent)

        every {
            WindowManager.getInstance().allProjectFrames
        } returns arrayOf(detachedFrame, healthyFrame)

        val selected = AccentApplicator.resolveFocusedProject()

        assertSame(healthyProject, selected)
    }

    @Test
    fun `resolveFocusedProject disposed OS-active frame falls through to IdeFocusManager not ProjectManager`() {
        // A regression that collapsed tiers 2+3 of the cascade would pass the existing
        // "fall back to openProjects" test (empty tier-1, empty tier-2, hits tier-3 as
        // expected) but silently reach tier-3 instead of tier-2 when tier-1 returns an
        // unusable match. This test forces tier-1 to return disposed, leaves tier-2
        // healthy, and asserts tier-2 wins.
        val disposedProject = stubProject(isDefault = false, isDisposed = true)
        val disposedWindow = mockk<Window>(relaxed = true)
        every { disposedWindow.isActive } returns true
        val disposedComponent = JPanel()
        every { SwingUtilities.getWindowAncestor(disposedComponent) } returns disposedWindow
        val disposedFrame = stubIdeFrame(disposedProject, disposedComponent)

        every { WindowManager.getInstance().allProjectFrames } returns arrayOf(disposedFrame)

        val focusedProject = stubProject(isDefault = false, isDisposed = false)
        val focusedFrame =
            mockk<IdeFrame> {
                every { project } returns focusedProject
            }
        val focusManager =
            mockk<IdeFocusManager> {
                every { lastFocusedFrame } returns focusedFrame
            }
        mockkStatic(IdeFocusManager::class)
        every { IdeFocusManager.getGlobalInstance() } returns focusManager

        val projectManagerProject = stubProject(isDefault = false, isDisposed = false)
        val projectManager = mockk<ProjectManager>()
        every { projectManager.openProjects } returns arrayOf(projectManagerProject)
        every { ProjectManager.getInstance() } returns projectManager

        val selected = AccentApplicator.resolveFocusedProject()

        assertSame(focusedProject, selected)
    }

    @Test
    fun `resolveFocusedProject survives a frame throwing during OS-active resolution`() {
        // Frame access can throw when the frame is mid-dispose during shutdown. The OS-active
        // scan wraps each frame access in try/catch so one bad frame doesn't break resolution
        // of a healthy one — same defensive pattern as ProjectAccentSwapService.findProjectForWindow.
        val brokenFrame =
            mockk<IdeFrame> {
                every { project } throws IllegalStateException("frame mid-dispose")
            }
        val healthyProject = stubProject(isDefault = false, isDisposed = false)
        val healthyWindow = mockk<Window>(relaxed = true)
        every { healthyWindow.isActive } returns true
        val healthyComponent = JPanel()
        every { SwingUtilities.getWindowAncestor(healthyComponent) } returns healthyWindow
        val healthyFrame = stubIdeFrame(healthyProject, healthyComponent)

        every { WindowManager.getInstance().allProjectFrames } returns arrayOf(brokenFrame, healthyFrame)

        val loggedErrors = mutableListOf<Throwable?>()
        val processor =
            object : LoggedErrorProcessor() {
                override fun processWarn(
                    category: String,
                    message: String,
                    throwable: Throwable?,
                ): Boolean {
                    if (!message.contains("Skipping frame during OS-active resolution")) return true
                    loggedErrors += throwable
                    return false
                }
            }

        var selected: Project? = null
        LoggedErrorProcessor.executeWith<Throwable>(processor) {
            selected = AccentApplicator.resolveFocusedProject()
        }

        assertSame(healthyProject, selected)
    }

    @Test
    fun `resolveFocusedProject WARN context includes resolved project name when name succeeded before throw`() {
        // Exercises the other branch of `probedName ?: "<unresolved>"` in the WARN context
        // string: when `project.name` succeeds but a later state probe (isUsable / window
        // ancestor) throws, the catch must credit the resolved name in the log so triage
        // can tell WHICH project's frame broke. The "<unresolved>" branch is covered by
        // the throwing-frame tests above where `.project` itself throws.
        val probedProject =
            mockk<Project> {
                every { name } returns "frame-project"
                every { isDefault } throws IllegalStateException("probe after name")
            }
        val probedFrame =
            mockk<IdeFrame> {
                every { project } returns probedProject
            }
        every { WindowManager.getInstance().allProjectFrames } returns arrayOf(probedFrame)

        val projectManager = mockk<ProjectManager>()
        every { projectManager.openProjects } returns emptyArray()
        every { ProjectManager.getInstance() } returns projectManager

        val capturedWarns = mutableListOf<String>()
        val processor =
            object : LoggedErrorProcessor() {
                override fun processWarn(
                    category: String,
                    message: String,
                    throwable: Throwable?,
                ): Boolean {
                    if (!message.contains("Skipping frame during OS-active resolution")) return true
                    capturedWarns += message
                    return false
                }
            }

        LoggedErrorProcessor.executeWith<Throwable>(processor) {
            AccentApplicator.resolveFocusedProject()
        }

        val firstWarn = capturedWarns.single()
        assertTrue(
            firstWarn.contains("project=frame-project"),
            "WARN must carry the resolved project name when it was captured before the throw; got: $firstWarn",
        )
    }

    @Test
    fun `resolveFocusedProject WARN-gates per-frame failures then degrades to DEBUG`() {
        // Three consecutive apply cycles each hit the same broken frame. The first must WARN
        // (so user-submitted idea.log captures it); subsequent failures must DEBUG-log so
        // interactive sessions calling Apply multiple times per minute don't drown the log.
        // Without the gate, this would emit three WARNs or zero WARNs — both regress against
        // the sibling pattern in ProjectAccentSwapService.findProjectForWindow.
        val brokenFrame =
            mockk<IdeFrame> {
                every { project } throws IllegalStateException("frame mid-dispose")
            }
        every { WindowManager.getInstance().allProjectFrames } returns arrayOf(brokenFrame)

        val projectManager = mockk<ProjectManager>()
        every { projectManager.openProjects } returns emptyArray()
        every { ProjectManager.getInstance() } returns projectManager

        val capturedWarns = mutableListOf<String>()
        val processor =
            object : LoggedErrorProcessor() {
                override fun processWarn(
                    category: String,
                    message: String,
                    throwable: Throwable?,
                ): Boolean {
                    if (!message.contains("Skipping frame during OS-active resolution")) return true
                    capturedWarns += message
                    return false
                }
            }

        LoggedErrorProcessor.executeWith<Throwable>(processor) {
            AccentApplicator.resolveFocusedProject()
            AccentApplicator.resolveFocusedProject()
            AccentApplicator.resolveFocusedProject()
        }

        assertEquals(
            1,
            capturedWarns.size,
            "Per-frame exception must WARN once then dedup; got: $capturedWarns",
        )
        val firstWarn = capturedWarns.single()
        assertTrue(
            firstWarn.contains("further failures logged at DEBUG"),
            "first WARN must announce the dedup contract; got: $firstWarn",
        )
        assertTrue(
            firstWarn.contains("index=") && firstWarn.contains("thread="),
            "WARN must carry frame index and thread name so idea.log makes triage possible; got: $firstWarn",
        )
        assertFalse(
            AccentApplicator.windowManagerUnavailableLogged.get(),
            "per-frame gate must not flip the WindowManager gate; the two are independent",
        )
    }

    private fun stubProject(
        isDefault: Boolean,
        isDisposed: Boolean,
        name: String = "stub-project",
    ): Project {
        val project = mockk<Project>()
        every { project.isDefault } returns isDefault
        every { project.isDisposed } returns isDisposed
        // AccentApplicator.osActiveProjectFrame reads `project.name` directly (Pattern B
        // fix — no runCatching wrapper) for the diagnostic probe. Stub it here so mocks
        // don't throw MockKException when the code probes the name on a healthy frame.
        every { project.name } returns name
        return project
    }

    private fun stubIdeFrame(
        project: Project,
        component: JComponent,
    ): IdeFrame =
        mockk {
            every { this@mockk.project } returns project
            every { this@mockk.component } returns component
        }
}
