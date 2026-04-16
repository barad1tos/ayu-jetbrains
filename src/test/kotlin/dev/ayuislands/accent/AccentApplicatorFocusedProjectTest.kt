package dev.ayuislands.accent

import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.wm.IdeFocusManager
import com.intellij.openapi.wm.IdeFrame
import com.intellij.openapi.wm.WindowManager
import dev.ayuislands.settings.mappings.ProjectAccentSwapService
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import java.awt.Window
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.SwingUtilities
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Locks in the contract of [AccentApplicator.applyForFocusedProject] — five production
 * callers (the three settings panels, the LAF listener, the rotation tick) previously
 * hand-wired inconsistent variants of this sequence (most skipped the swap-cache sync).
 * These tests are the safety net that keeps the sequence (focused-project selection →
 * resolver → apply → swap-cache sync) from drifting.
 *
 * Uses the real [AccentApplicator] object but mocks every collaborator:
 *  - [com.intellij.openapi.wm.IdeFocusManager] is left unmocked → returns the headless
 *    no-op manager whose `lastFocusedFrame` is `null`, so these tests exercise the
 *    [ProjectManager] fallback path. Primary-path coverage (real focused frame) is left
 *    to manual smoke / IDE integration; the production fallback chain is documented in
 *    the helper's own KDoc.
 *  - [ProjectManager.getInstance] for the fallback focused-project pick (first
 *    non-default, non-disposed open project)
 *  - [AccentResolver.resolve] for the resolver call (covered independently in AccentResolverTest)
 *  - A partial mock on [AccentApplicator] itself to intercept `apply(hex)` — we only care that
 *    the helper forwards the resolver's output, not the full UIManager side-effect chain
 *  - [ProjectAccentSwapService.getInstance] for the notifyExternalApply assertion
 */
class AccentApplicatorFocusedProjectTest {
    private lateinit var swapService: ProjectAccentSwapService

    @BeforeTest
    fun setUp() {
        mockkStatic(ProjectManager::class)
        mockkObject(AccentResolver)
        mockkObject(AccentApplicator, recordPrivateCalls = false)
        every { AccentApplicator.apply(any()) } just Runs

        mockkObject(ProjectAccentSwapService.Companion)
        swapService = mockk(relaxed = true)
        every { ProjectAccentSwapService.getInstance() } returns swapService

        // Default: no OS-active project frames. resolveFocusedProject's new primary path
        // (OS-active IDE frame) short-circuits to null so tests that exercise the
        // IdeFocusManager / ProjectManager fallback chain behave as before. Tests covering
        // the OS-active path override this explicitly.
        mockkStatic(WindowManager::class)
        val windowManager =
            mockk<WindowManager> {
                every { allProjectFrames } returns emptyArray()
            }
        every { WindowManager.getInstance() } returns windowManager

        mockkStatic(SwingUtilities::class)
        every { SwingUtilities.getWindowAncestor(any()) } returns null
    }

    @AfterTest
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `applyForFocusedProject forwards the resolver output to apply and the swap cache`() {
        val focused = stubProject(isDefault = false, isDisposed = false)
        val manager = mockk<ProjectManager>()
        every { manager.openProjects } returns arrayOf(focused)
        every { ProjectManager.getInstance() } returns manager
        every { AccentResolver.resolve(focused, AyuVariant.MIRAGE) } returns "#ABCDEF"

        val applied = AccentApplicator.applyForFocusedProject(AyuVariant.MIRAGE)

        assertEquals("#ABCDEF", applied)
        verify(exactly = 1) { AccentApplicator.apply("#ABCDEF") }
        verify(exactly = 1) { swapService.notifyExternalApply("#ABCDEF") }
    }

    @Test
    fun `applyForFocusedProject skips default and disposed projects when picking focus`() {
        val defaultProject = stubProject(isDefault = true, isDisposed = false)
        val disposedProject = stubProject(isDefault = false, isDisposed = true)
        val realFocus = stubProject(isDefault = false, isDisposed = false)
        val manager = mockk<ProjectManager>()
        every { manager.openProjects } returns arrayOf(defaultProject, disposedProject, realFocus)
        every { ProjectManager.getInstance() } returns manager
        every { AccentResolver.resolve(realFocus, AyuVariant.DARK) } returns "#123456"

        val applied = AccentApplicator.applyForFocusedProject(AyuVariant.DARK)

        assertEquals("#123456", applied)
        verify(exactly = 1) { AccentResolver.resolve(realFocus, AyuVariant.DARK) }
    }

    @Test
    fun `applyForFocusedProject passes null to resolver when no non-default project is open`() {
        // Simulates the "Welcome screen / all projects closed" startup path. Resolver sees a null
        // project, short-circuits to the global accent, and the helper still propagates it.
        val manager = mockk<ProjectManager>()
        every { manager.openProjects } returns emptyArray()
        every { ProjectManager.getInstance() } returns manager
        every { AccentResolver.resolve(null, AyuVariant.LIGHT) } returns "#F29718"

        val applied = AccentApplicator.applyForFocusedProject(AyuVariant.LIGHT)

        assertEquals("#F29718", applied)
        verify(exactly = 1) { AccentApplicator.apply("#F29718") }
        verify(exactly = 1) { swapService.notifyExternalApply("#F29718") }
    }

    @Test
    fun `applyForFocusedProject prefers IdeFocusManager focused frame over openProjects order`() {
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

        every { AccentResolver.resolve(focusedProject, AyuVariant.MIRAGE) } returns "#FOCUSED"

        val applied = AccentApplicator.applyForFocusedProject(AyuVariant.MIRAGE)

        assertEquals("#FOCUSED", applied)
        verify(exactly = 1) { AccentResolver.resolve(focusedProject, AyuVariant.MIRAGE) }
        verify(exactly = 0) { AccentResolver.resolve(otherProject, any()) }
    }

    @Test
    fun `applyForFocusedProject falls back to openProjects when focused frame project is disposed`() {
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

        every { AccentResolver.resolve(healthyProject, AyuVariant.MIRAGE) } returns "#FALLBACK"

        val applied = AccentApplicator.applyForFocusedProject(AyuVariant.MIRAGE)

        assertEquals("#FALLBACK", applied)
        verify(exactly = 1) { AccentResolver.resolve(healthyProject, AyuVariant.MIRAGE) }
    }

    @Test
    fun `applyForFocusedProject ordering - resolver runs before apply, swap cache after`() {
        // The swap cache's notifyExternalApply must fire AFTER apply, otherwise a concurrent
        // window-activated event would see the cache updated for a hex that's not yet painted.
        val focused = stubProject(isDefault = false, isDisposed = false)
        val manager = mockk<ProjectManager>()
        every { manager.openProjects } returns arrayOf(focused)
        every { ProjectManager.getInstance() } returns manager
        every { AccentResolver.resolve(focused, AyuVariant.MIRAGE) } returns "#FFCC66"

        AccentApplicator.applyForFocusedProject(AyuVariant.MIRAGE)

        io.mockk.verifyOrder {
            AccentResolver.resolve(focused, AyuVariant.MIRAGE)
            AccentApplicator.apply("#FFCC66")
            swapService.notifyExternalApply("#FFCC66")
        }
    }

    @Test
    fun `applyForFocusedProject prefers OS-active project frame over IdeFocusManager lastFocusedFrame`() {
        // Regression guard for the rotation-override bug. When rotation tick fires while the
        // user is alt-tabbed between IDE project windows, IdeFocusManager.lastFocusedFrame can
        // still point at the previously-clicked IDE frame (not the one currently on top). The
        // OS-active-frame path is the ground truth for "which project window is the user
        // visually on right now", and must win over lastFocusedFrame when they disagree.
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

        every { AccentResolver.resolve(osActiveProject, AyuVariant.MIRAGE) } returns "#OS"

        val applied = AccentApplicator.applyForFocusedProject(AyuVariant.MIRAGE)

        assertEquals("#OS", applied)
        verify(exactly = 1) { AccentResolver.resolve(osActiveProject, AyuVariant.MIRAGE) }
        verify(exactly = 0) { AccentResolver.resolve(lastFocusedProject, any()) }
    }

    @Test
    fun `applyForFocusedProject falls back to IdeFocusManager when no project frame is OS-active`() {
        // User alt-tabbed to a non-IDE app (browser, Slack) when rotation tick fires. No
        // project frame has OS focus, so the OS-active-frame path returns null and the
        // cascade falls through to IdeFocusManager.lastFocusedFrame. The scheduler runs on
        // EDT via invokeLater, so lastFocusedFrame is the best available signal for "which
        // project the user was most recently in".
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

        every { AccentResolver.resolve(lastFocusedProject, AyuVariant.MIRAGE) } returns "#LAST"

        val applied = AccentApplicator.applyForFocusedProject(AyuVariant.MIRAGE)

        assertEquals("#LAST", applied)
        verify(exactly = 1) { AccentResolver.resolve(lastFocusedProject, AyuVariant.MIRAGE) }
    }

    @Test
    fun `applyForFocusedProject skips OS-active frame whose project is disposed and continues the cascade`() {
        // Shutdown race: a project frame is OS-active but its project has just been disposed.
        // The isUsable() guard inside the OS-active scan must drop it; the cascade falls
        // through to IdeFocusManager / ProjectManager. Without the guard, resolve() would be
        // called with a disposed project and throw inside AccentResolver.projectKey.
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

        every { AccentResolver.resolve(healthyProject, AyuVariant.MIRAGE) } returns "#FALLBACK"

        val applied = AccentApplicator.applyForFocusedProject(AyuVariant.MIRAGE)

        assertEquals("#FALLBACK", applied)
        verify(exactly = 0) { AccentResolver.resolve(disposedProject, any()) }
    }

    @Test
    fun `applyForFocusedProject survives a frame throwing during OS-active resolution`() {
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

        every { AccentResolver.resolve(healthyProject, AyuVariant.MIRAGE) } returns "#HEALTHY"

        val applied = AccentApplicator.applyForFocusedProject(AyuVariant.MIRAGE)

        assertEquals("#HEALTHY", applied)
        verify(exactly = 1) { AccentResolver.resolve(healthyProject, AyuVariant.MIRAGE) }
    }

    private fun stubProject(
        isDefault: Boolean,
        isDisposed: Boolean,
    ): Project {
        val project = mockk<Project>()
        every { project.isDefault } returns isDefault
        every { project.isDisposed } returns isDisposed
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
