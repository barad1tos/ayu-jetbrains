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
import javax.swing.JFrame
import javax.swing.JLabel
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
        // `wireMatchingFrame(window, project)`.
        val windowManager =
            mockk<WindowManager> {
                every { allProjectFrames } returns emptyArray()
            }
        every { WindowManager.getInstance() } returns windowManager
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
        val window = JFrame() // not registered with WindowManager — no matching frame
        val event = WindowEvent(window, WindowEvent.WINDOW_ACTIVATED)

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
        val event = WindowEvent(window, WindowEvent.WINDOW_ACTIVATED)

        service.onWindowActivatedForTest(event)

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
        val event = WindowEvent(window, WindowEvent.WINDOW_ACTIVATED)

        service.onWindowActivatedForTest(event)

        verify(exactly = 0) { AccentResolver.resolve(project, any()) }
        verify(exactly = 0) { AccentApplicator.apply(any()) }
    }

    @Test
    fun `onWindowActivated short-circuits on same-project re-activation`() {
        // The whole point of the cache: alt-tabbing within the same project window must NOT
        // re-apply the accent on every focus event. After the first activation primes the
        // cache, the second call with the same project must skip resolve+apply.
        val (window, project) = wireMatchingFrame()
        every { AccentResolver.resolve(project, AyuVariant.MIRAGE) } returns "#FFCC66"
        val service = ProjectAccentSwapService()
        val event = WindowEvent(window, WindowEvent.WINDOW_ACTIVATED)

        service.onWindowActivatedForTest(event) // primes cache
        service.onWindowActivatedForTest(event) // same project — must short-circuit

        // resolve called exactly once on the priming pass; apply called exactly once.
        verify(exactly = 1) { AccentResolver.resolve(project, AyuVariant.MIRAGE) }
        verify(exactly = 1) { AccentApplicator.apply("#FFCC66") }
    }

    @Test
    fun `onWindowActivated short-circuits on same-hex re-activation across different projects`() {
        // Two different projects sharing the same effective accent hex (e.g. same global
        // override). Activating the second after priming with the first must update the
        // lastAppliedProject reference but skip the apply, because the visible UIManager
        // state is already correct.
        val projectA = stubProject("project-a")
        val projectB = stubProject("project-b")
        val windowA = JFrame()
        val windowB = JFrame()
        val labelA = JLabel().also { windowA.add(it) }
        val labelB = JLabel().also { windowB.add(it) }
        val frameA = stubIdeFrame(projectA, labelA)
        val frameB = stubIdeFrame(projectB, labelB)
        every { WindowManager.getInstance().allProjectFrames } returns arrayOf(frameA, frameB)
        every { AccentResolver.resolve(projectA, AyuVariant.MIRAGE) } returns "#FFCC66"
        every { AccentResolver.resolve(projectB, AyuVariant.MIRAGE) } returns "#FFCC66"

        val service = ProjectAccentSwapService()
        service.onWindowActivatedForTest(WindowEvent(windowA, WindowEvent.WINDOW_ACTIVATED))
        service.onWindowActivatedForTest(WindowEvent(windowB, WindowEvent.WINDOW_ACTIVATED))

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
        val event = WindowEvent(window, WindowEvent.WINDOW_ACTIVATED)

        service.onWindowActivatedForTest(event)

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
        service.onWindowActivatedForTest(WindowEvent(window, WindowEvent.WINDOW_ACTIVATED))

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
        val event = WindowEvent(JFrame(), WindowEvent.WINDOW_ACTIVATED)

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
     * Returns (window, project) where the mocked WindowManager.allProjectFrames contains a
     * single IdeFrame whose component's window ancestor is `window` and whose project is
     * the returned mock. Lets [handleWindowActivated] reach AyuVariant.detect / resolve /
     * apply on a real Swing tree.
     */
    private fun wireMatchingFrame(disposed: Boolean = false): Pair<Window, Project> {
        val window = JFrame()
        val component = JLabel()
        window.add(component) // window becomes the ancestor of component
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
