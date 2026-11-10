package dev.ayuislands.settings.mappings

import com.intellij.openapi.application.Application
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.IdeFrame
import com.intellij.openapi.wm.WindowManager
import com.intellij.testFramework.LoggedErrorProcessor
import dev.ayuislands.studio.StudioService
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.Job
import java.awt.AWTEvent
import java.awt.Toolkit
import java.awt.Window
import java.awt.event.AWTEventListener
import java.awt.event.WindowEvent
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.SwingUtilities
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ProjectAccentSwapServiceTest {
    private val studio = mockk<StudioService>()

    @BeforeTest
    fun setUp() {
        mockkObject(StudioService.Companion)
        every { StudioService.getInstance() } returns studio
        every { studio.focusChanged() } returns Job().apply { complete() }
        mockkStatic(WindowManager::class)
        val manager = mockk<WindowManager>()
        every { WindowManager.getInstance() } returns manager
        every { manager.allProjectFrames } returns emptyArray()
        mockkStatic(SwingUtilities::class)
        every { SwingUtilities.getWindowAncestor(any()) } returns null
    }

    @AfterTest
    fun tearDown() = unmockkAll()

    @Test
    fun `onWindowActivated catches RuntimeException and converts to LOG error with the original cause`() {
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
    fun `window activation propagates cancellation without reporting failure`() {
        val (window, _) = wireMatchingFrame()
        val service = ProjectAccentSwapService()
        val capturedErrors = mutableListOf<Throwable?>()
        val processor =
            object : LoggedErrorProcessor() {
                override fun processError(
                    category: String,
                    message: String,
                    details: Array<out String>,
                    throwable: Throwable?,
                ): Set<Action> {
                    capturedErrors += throwable
                    return java.util.EnumSet.noneOf(Action::class.java)
                }
            }

        for (cancellation in listOf(ProcessCanceledException(), CancellationException("cancelled"))) {
            every { studio.focusChanged() } throws cancellation
            LoggedErrorProcessor.executeWith<Throwable>(processor) {
                assertSame(
                    cancellation,
                    assertFailsWith<RuntimeException> { service.onWindowActivatedForTest(makeEvent(window)) },
                )
            }
        }
        assertTrue(capturedErrors.isEmpty(), "Cancellation must not be logged as an application failure")
    }

    @Test
    fun `onWindowActivated short-circuits when no project frame matches the activated window`() {
        val service = ProjectAccentSwapService()
        val window = mockk<Window>(relaxed = true) // no matching frame in WindowManager
        val event = makeEvent(window)

        service.onWindowActivatedForTest(event)

        verify(exactly = 0) { studio.focusChanged() }
    }

    @Test
    fun `onWindowActivated short-circuits when project is disposed or default`() {
        val (window, _) = wireMatchingFrame(disposed = true)
        val service = ProjectAccentSwapService()

        service.onWindowActivatedForTest(makeEvent(window))

        verify(exactly = 0) { studio.focusChanged() }
    }

    @Test
    fun `findProjectForWindow logs first-frame failure at WARN then deduplicates subsequent failures to DEBUG`() {
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
    fun `install dispatches window activation through IntelliJ write-safe invokeLater`() {
        val toolkit = mockk<Toolkit>(relaxed = true)
        val listenerSlot = slot<AWTEventListener>()
        mockkStatic(Toolkit::class)
        every { Toolkit.getDefaultToolkit() } returns toolkit
        every {
            toolkit.addAWTEventListener(capture(listenerSlot), AWTEvent.WINDOW_EVENT_MASK)
        } just Runs
        val application = mockk<Application>(relaxed = true)
        mockkStatic(ApplicationManager::class)
        every { ApplicationManager.getApplication() } returns application
        every { application.invokeLater(any<Runnable>(), ModalityState.nonModal()) } just Runs

        val service = ProjectAccentSwapService()
        service.install()
        listenerSlot.captured.eventDispatched(makeEvent(mockk(relaxed = true)))

        verify(exactly = 1) {
            application.invokeLater(any<Runnable>(), ModalityState.nonModal())
        }
    }

    @Test
    fun `dispose removes the installed AWTEventListener`() {
        val toolkit = mockk<Toolkit>(relaxed = true)
        mockkStatic(Toolkit::class)
        every { Toolkit.getDefaultToolkit() } returns toolkit
        val service = ProjectAccentSwapService()
        service.install()

        service.dispose()

        verify(exactly = 1) { toolkit.removeAWTEventListener(any<AWTEventListener>()) }
    }

    @Test
    fun `handleWindowActivated returns gracefully when WindowManager is null (shutdown race)`() {
        every { WindowManager.getInstance() } returns null

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

        val service = ProjectAccentSwapService()
        val event = makeEvent(mockk<Window>(relaxed = true))

        LoggedErrorProcessor.executeWith<Throwable>(processor) {
            service.onWindowActivatedForTest(event) // first call must NOT throw
            service.onWindowActivatedForTest(event) // second call must dedup to DEBUG
            service.onWindowActivatedForTest(event)
        }

        assertEquals(
            1,
            capturedWarns.size,
            "WindowManager-unavailable dedup must collapse 3 shutdown-race events into 1 WARN; got: $capturedWarns",
        )
        verify(exactly = 0) { studio.focusChanged() }
    }

    @Test
    fun `each matching window activation enters the shared focus admission`() {
        val (window, _) = wireMatchingFrame()
        val service = ProjectAccentSwapService()
        service.onWindowActivatedForTest(makeEvent(window))
        service.onWindowActivatedForTest(makeEvent(window))
        verify(exactly = 2) { studio.focusChanged() }
    }

    @Test
    fun `default project windows never enter appearance admission`() {
        val (window, project) = wireMatchingFrame()
        every { project.isDefault } returns true
        ProjectAccentSwapService().onWindowActivatedForTest(makeEvent(window))
        verify(exactly = 0) { studio.focusChanged() }
    }

    private fun makeEvent(window: Window): WindowEvent =
        mockk {
            every { id } returns WindowEvent.WINDOW_ACTIVATED
            every { this@mockk.window } returns window
        }

    private fun wireMatchingFrame(disposed: Boolean = false): Pair<Window, Project> {
        val window = mockk<Window>(relaxed = true)
        val component = JPanel() // lightweight, headless-safe
        every { SwingUtilities.getWindowAncestor(component) } returns window
        val project = stubProject(disposed = disposed)
        val frame = stubIdeFrame(project, component)
        every { WindowManager.getInstance().allProjectFrames } returns arrayOf(frame)
        return window to project
    }

    private fun stubProject(disposed: Boolean = false): Project =
        mockk {
            every { isDisposed } returns disposed
            every { isDefault } returns false
            every { name } returns "test-project"
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
