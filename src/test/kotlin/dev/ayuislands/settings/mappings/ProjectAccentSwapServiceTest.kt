package dev.ayuislands.settings.mappings

import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.IdeFrame
import com.intellij.openapi.wm.WindowManager
import com.intellij.testFramework.LoggedErrorProcessor
import dev.ayuislands.accent.AccentApplicator
import dev.ayuislands.accent.AccentResolver
import dev.ayuislands.accent.AyuVariant
import dev.ayuislands.ui.ComponentTreeRefresher
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import java.awt.AWTEvent
import java.awt.Toolkit
import java.awt.Window
import java.awt.event.AWTEventListener
import java.awt.event.WindowEvent
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.SwingUtilities
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Behavior tests for [ProjectAccentSwapService]. The AWTEventListener registered by
 * `install()` is covered by manual smoke / integration (registering a real listener against
 * `Toolkit.getDefaultToolkit()` would leak across headless test runs). Everything else —
 * the handler logic, short-circuit gates, exception catching, and the `notifyExternalApply`
 * cache hook — is unit-tested via the [ProjectAccentSwapService.onWindowActivatedForTest]
 * seam plus mockkObject wrappers around AccentResolver / AccentApplicator / AyuVariant /
 * ComponentTreeRefresher and a static mock on WindowManager.
 *
 * Headless-safe: mocks [Window] (real `JFrame` / `JDialog` instantiation throws
 * `HeadlessException` on CI) and stubs [SwingUtilities.getWindowAncestor] so frame-to-window
 * reconciliation works without bringing up a graphics environment. Components are `JPanel`,
 * which is purely lightweight (no native peer) and works in headless.
 */
class ProjectAccentSwapServiceTest {
    @BeforeTest
    fun setUp() {
        mockkObject(AccentResolver)
        mockkObject(AccentApplicator)
        mockkObject(AyuVariant.Companion)
        mockkObject(ComponentTreeRefresher)
        every { AyuVariant.detect() } returns AyuVariant.MIRAGE
        every { AccentApplicator.apply(any()) } just Runs
        every { ComponentTreeRefresher.walkAndNotify(any(), any()) } just Runs

        mockkStatic(WindowManager::class)
        // Default: no project frames. Tests that need a matching frame override via
        // `wireMatchingFrame()`.
        val windowManager =
            mockk<WindowManager> {
                every { allProjectFrames } returns emptyArray()
            }
        every { WindowManager.getInstance() } returns windowManager

        // SwingUtilities.getWindowAncestor walks up component.parent looking for a Window.
        // In headless tests the components have no real window ancestor, so we stub the
        // static lookup explicitly. Default: returns null; tests override per call site
        // when they need a matching window.
        mockkStatic(SwingUtilities::class)
        every { SwingUtilities.getWindowAncestor(any()) } returns null
    }

    @AfterTest
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `onWindowActivated catches RuntimeException and converts to LOG error with the original cause`() {
        // The handler runs from an AWTEventListener that AWT does not remove on failure —
        // an uncaught exception here would dump a generic SEVERE trace into idea.log on
        // every alt-tab. Verify the catch at onWindowActivated() converts it into an
        // actionable LOG.error WITH context (message + the ORIGINAL throwable so Sentry /
        // log triage doesn't lose the stack).
        val service = ProjectAccentSwapService()
        val boom = IllegalStateException("frame disposed mid-event")
        val event = mockk<WindowEvent>()
        every { event.id } returns WindowEvent.WINDOW_ACTIVATED
        every { event.window } throws boom

        val capturedErrors = mutableListOf<Pair<String, Throwable?>>()
        val processor =
            object : LoggedErrorProcessor() {
                override fun processError(
                    category: String,
                    message: String,
                    details: Array<out String>,
                    throwable: Throwable?,
                ): Set<Action> {
                    capturedErrors += message to throwable
                    return java.util.EnumSet.noneOf(Action::class.java)
                }
            }

        LoggedErrorProcessor.executeWith<Throwable>(processor) {
            service.onWindowActivatedForTest(event)
        }

        val matched = capturedErrors.firstOrNull { it.first.contains("Project accent swap failed") }
        assertTrue(matched != null, "RuntimeException must produce LOG.error; got: $capturedErrors")
        assertSame(
            boom,
            matched.second,
            "LOG.error must carry the ORIGINAL exception so triage doesn't lose the stack",
        )
    }

    @Test
    fun `onWindowActivated short-circuits when no project frame matches the activated window`() {
        // findProjectForWindow returns null (no matching frame in WindowManager). The handler
        // must early-exit without calling AccentResolver.resolve or AccentApplicator.apply —
        // otherwise activating a popup / dialog window would re-apply the accent on every
        // alt-tab, defeating the whole point of the cache.
        val service = ProjectAccentSwapService()
        val window = mockk<Window>(relaxed = true) // no matching frame in WindowManager
        val event = makeEvent(window)

        service.onWindowActivatedForTest(event)

        verify(exactly = 0) { AccentApplicator.apply(any()) }
        verify(exactly = 0) { AccentResolver.resolve(any(), any()) }
        verify(exactly = 0) { AyuVariant.detect() }
    }

    @Test
    fun `onWindowActivated short-circuits when AyuVariant detect returns null`() {
        // Non-Ayu theme is active — the handler must NOT apply even after window resolution
        // succeeds. A regression dropping the `AyuVariant.detect() ?: return` guard would
        // pass apply with whatever default variant other code defaults to. This test wires
        // a real matching frame so the handler reaches AyuVariant.detect, then asserts no
        // apply happens AND detect was actually consulted.
        val (window, project) = wireMatchingFrame()
        every { AyuVariant.detect() } returns null
        val service = ProjectAccentSwapService()

        service.onWindowActivatedForTest(makeEvent(window))

        verify(exactly = 1) { AyuVariant.detect() }
        verify(exactly = 0) { AccentResolver.resolve(project, any()) }
        verify(exactly = 0) { AccentApplicator.apply(any()) }
    }

    @Test
    fun `onWindowActivated short-circuits when project is disposed or default`() {
        // Race: between WindowManager enumeration and the dispose-check inside the handler,
        // a project transitions to disposed. Guard prevents calling resolve/apply on a
        // disposed project (which would throw deeper inside AccentResolver).
        val (window, project) = wireMatchingFrame(disposed = true)
        val service = ProjectAccentSwapService()

        service.onWindowActivatedForTest(makeEvent(window))

        verify(exactly = 0) { AccentResolver.resolve(project, any()) }
        verify(exactly = 0) { AccentApplicator.apply(any()) }
    }

    @Test
    fun `onWindowActivated re-resolves on same-project re-activation and skips apply when hex matches`() {
        // Same-project re-activation (alt-tab out to a non-IDE app and back) MUST re-resolve
        // because an external apply — rotation tick, settings panel — may have drifted the
        // JVM-wide UIManager/globalScheme color since the last activation. The apply itself
        // is still skipped when the resolver output matches lastAppliedHex, so alt-tab within
        // a stable project is cheap (one HashMap lookup, no component-tree walk).
        val (window, project) = wireMatchingFrame()
        every { AccentResolver.resolve(project, AyuVariant.MIRAGE) } returns "#FFCC66"
        val service = ProjectAccentSwapService()
        val event = makeEvent(window)

        service.onWindowActivatedForTest(event) // primes cache
        service.onWindowActivatedForTest(event) // same project — re-resolves, hex matches, skips apply

        verify(exactly = 2) { AccentResolver.resolve(project, AyuVariant.MIRAGE) }
        verify(exactly = 1) { AccentApplicator.apply("#FFCC66") }
        verify(exactly = 1) { ComponentTreeRefresher.walkAndNotify(project, window) }
    }

    @Test
    fun `onWindowActivated re-applies when external apply drifted hex for same project`() {
        // Rotation tick / Settings Apply / LAF change can push a color resolved for a DIFFERENT
        // focused project into the JVM-wide UIManager/globalScheme (via AccentApplicator.apply
        // + notifyExternalApply). Re-activating the same project later must notice the drift
        // and re-apply the correct resolved color — the only signal that the visible UI needs
        // to be rewritten is a cache-hex that no longer matches the resolver's output. Without
        // the re-apply, tabs/toolbars (global scheme) would stay on the wrong color while
        // glow (per-project) stayed on the correct override.
        val (window, project) = wireMatchingFrame()
        val service = ProjectAccentSwapService()

        // First activation primes the cache with the project's real override color.
        every { AccentResolver.resolve(project, AyuVariant.MIRAGE) } returns "#5CCFE6"
        service.onWindowActivatedForTest(makeEvent(window))

        // Rotation tick path: external apply pushed a DIFFERENT color into UIManager
        // (because the tick resolved for a different focused project) and synced the cache.
        service.notifyExternalApply("#DFBFFF")

        // Alt-tab back to the original project. Resolver still returns the override color;
        // cache-hex is lavender; the handler must notice the drift and re-apply cyan.
        service.onWindowActivatedForTest(makeEvent(window))

        verify(exactly = 2) { AccentApplicator.apply("#5CCFE6") }
        verify(exactly = 2) { ComponentTreeRefresher.walkAndNotify(project, window) }
    }

    @Test
    fun `onWindowActivated short-circuits on same-hex re-activation across different projects`() {
        // Two different projects sharing the same effective accent hex (e.g. same global
        // override). Activating the second after priming with the first must resolve the
        // new project but skip the apply, because the visible UIManager state is already
        // correct — the hex gate carries the dedup load now that project identity alone is
        // no longer enough to prove staleness.
        val projectA = stubProject("project-a")
        val projectB = stubProject("project-b")
        val windowA = mockk<Window>(relaxed = true)
        val windowB = mockk<Window>(relaxed = true)
        val componentA = JPanel()
        val componentB = JPanel()
        every { SwingUtilities.getWindowAncestor(componentA) } returns windowA
        every { SwingUtilities.getWindowAncestor(componentB) } returns windowB
        val frameA = stubIdeFrame(projectA, componentA)
        val frameB = stubIdeFrame(projectB, componentB)
        every { WindowManager.getInstance().allProjectFrames } returns arrayOf(frameA, frameB)
        every { AccentResolver.resolve(projectA, AyuVariant.MIRAGE) } returns "#FFCC66"
        every { AccentResolver.resolve(projectB, AyuVariant.MIRAGE) } returns "#FFCC66"

        val service = ProjectAccentSwapService()
        service.onWindowActivatedForTest(makeEvent(windowA))
        service.onWindowActivatedForTest(makeEvent(windowB))

        // Both projects went through resolve (different cached project identity) but apply
        // fired only once because the effective hex hadn't changed.
        verify(exactly = 1) { AccentResolver.resolve(projectA, AyuVariant.MIRAGE) }
        verify(exactly = 1) { AccentResolver.resolve(projectB, AyuVariant.MIRAGE) }
        verify(exactly = 1) { AccentApplicator.apply("#FFCC66") }
    }

    @Test
    fun `onWindowActivated invokes ComponentTreeRefresher walkAndNotify after apply`() {
        // AccentApplicator updates UIManager + editor scheme but leaves cached JBColor on
        // already-painted components. The handler must follow apply() with a tree-walk so
        // toolbar / tab underlines / scrollbar chrome pick up the new accent. Regression
        // dropping the walkAndNotify call would silently break per-project visual isolation
        // (apply would update UIManager but the visible UI would stay stale until the next
        // organic refresh).
        val (window, project) = wireMatchingFrame()
        every { AccentResolver.resolve(project, AyuVariant.MIRAGE) } returns "#FFCC66"
        val service = ProjectAccentSwapService()

        service.onWindowActivatedForTest(makeEvent(window))

        verify(exactly = 1) { ComponentTreeRefresher.walkAndNotify(project, window) }
    }

    @Test
    fun `notifyExternalApply primes cache so next matching swap skips apply`() {
        // Exercises the cache-write contract end-to-end: after notifyExternalApply primes
        // lastAppliedHex, an onWindowActivated that resolves the same hex must skip apply.
        // Without the cache write, every focus-swap after a Settings panel apply would
        // redundantly re-apply the accent.
        val (window, project) = wireMatchingFrame()
        every { AccentResolver.resolve(project, AyuVariant.MIRAGE) } returns "#FFCC66"
        val service = ProjectAccentSwapService()

        service.notifyExternalApply("#FFCC66") // priming write
        service.onWindowActivatedForTest(makeEvent(window))

        // resolve was called (project changed from null to projectA) but apply was skipped
        // because the effective hex matches the cache.
        verify(exactly = 1) { AccentResolver.resolve(project, AyuVariant.MIRAGE) }
        verify(exactly = 0) { AccentApplicator.apply(any()) }
    }

    @Test
    fun `findProjectForWindow logs first-frame failure at WARN then dedups subsequent failures to DEBUG`() {
        // The frameResolutionFailureLogged gate prevents log spam on every alt-tab when one
        // broken frame keeps throwing on access. First failure must produce a WARN; later
        // failures (same handler invocation or subsequent invocations) must NOT produce
        // additional WARNs. A regression dropping the gate would flood idea.log.
        val brokenFrame =
            mockk<IdeFrame> {
                every { project } throws IllegalStateException("frame mid-dispose")
            }
        every { WindowManager.getInstance().allProjectFrames } returns arrayOf(brokenFrame)

        val service = ProjectAccentSwapService()
        val event = makeEvent(mockk<Window>(relaxed = true))

        val capturedWarns = mutableListOf<String>()
        val processor =
            object : LoggedErrorProcessor() {
                override fun processWarn(
                    category: String,
                    message: String,
                    throwable: Throwable?,
                ): Boolean {
                    if (!message.contains("Skipping frame")) return true
                    capturedWarns += message
                    return false
                }
            }

        LoggedErrorProcessor.executeWith<Throwable>(processor) {
            // Three consecutive activations all hit the broken frame — only the first must
            // produce a WARN; the next two must log at DEBUG (not captured here).
            service.onWindowActivatedForTest(event)
            service.onWindowActivatedForTest(event)
            service.onWindowActivatedForTest(event)
        }

        assertEquals(
            1,
            capturedWarns.size,
            "Frame-resolution dedup must collapse 3 broken-frame failures into 1 WARN; got: $capturedWarns",
        )
    }

    @Test
    fun `install registers AWTEventListener exactly once across repeated calls`() {
        // Reentrancy guard: install() is invoked from every ProjectActivity (per project open).
        // Without `if (listener != null) return`, each project window double-registers the
        // listener, so the focus-swap handler would fire N times per WINDOW_ACTIVATED for an
        // N-window IDE — straight perf regression and possible visible flicker.
        val toolkit = mockk<Toolkit>(relaxed = true)
        mockkStatic(Toolkit::class)
        every { Toolkit.getDefaultToolkit() } returns toolkit
        val service = ProjectAccentSwapService()

        service.install()
        service.install()
        service.install()

        verify(exactly = 1) {
            toolkit.addAWTEventListener(any<AWTEventListener>(), AWTEvent.WINDOW_EVENT_MASK)
        }
    }

    @Test
    fun `dispose removes the AWTEventListener and clears the swap cache`() {
        // Without removing the listener, AWT keeps dispatching to a service whose backing
        // state is being torn down — guaranteed NPE inside handleWindowActivated. Also clears
        // lastAppliedProject/Hex so a re-install on a fresh service starts with a clean cache.
        val toolkit = mockk<Toolkit>(relaxed = true)
        mockkStatic(Toolkit::class)
        every { Toolkit.getDefaultToolkit() } returns toolkit
        val service = ProjectAccentSwapService()
        service.install()

        service.dispose()

        verify(exactly = 1) { toolkit.removeAWTEventListener(any<AWTEventListener>()) }
    }

    // Test helpers

    /**
     * Builds a [WindowEvent] whose `window` getter returns [window]. Direct construction
     * via `WindowEvent(window, id)` works on macOS dev machines but indirectly touches
     * graphics on some platforms; mocking the event keeps the test fully headless-safe.
     */
    private fun makeEvent(window: Window): WindowEvent =
        mockk {
            every { id } returns WindowEvent.WINDOW_ACTIVATED
            every { this@mockk.window } returns window
        }

    /**
     * Returns (window, project) where the mocked WindowManager.allProjectFrames contains a
     * single IdeFrame whose component's window ancestor is `window` and whose project is
     * the returned mock. Lets `handleWindowActivated` reach AyuVariant.detect / resolve /
     * apply on a real Swing tree without instantiating a [java.awt.Frame] (which throws
     * `HeadlessException` on CI).
     */
    private fun wireMatchingFrame(disposed: Boolean = false): Pair<Window, Project> {
        val window = mockk<Window>(relaxed = true)
        val component = JPanel() // lightweight, headless-safe
        every { SwingUtilities.getWindowAncestor(component) } returns window
        val project = stubProject("test-project", disposed = disposed)
        val frame = stubIdeFrame(project, component)
        every { WindowManager.getInstance().allProjectFrames } returns arrayOf(frame)
        return window to project
    }

    private fun stubProject(
        name: String,
        disposed: Boolean = false,
    ): Project =
        mockk {
            every { isDisposed } returns disposed
            every { isDefault } returns false
            every { this@mockk.name } returns name
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
