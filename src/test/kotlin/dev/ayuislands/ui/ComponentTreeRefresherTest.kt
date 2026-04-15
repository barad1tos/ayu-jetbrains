package dev.ayuislands.ui

import com.intellij.openapi.project.Project
import com.intellij.testFramework.LoggedErrorProcessor
import com.intellij.util.IJSwingUtilities
import com.intellij.util.messages.MessageBus
import io.mockk.every
import io.mockk.justRun
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import java.awt.Component
import javax.swing.JPanel
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test

/**
 * Locks in the contract of [ComponentTreeRefresher] — the central pipeline subscribers
 * (EditorScrollbarManager, ProjectViewScrollbarManager) hang their self-heal logic off.
 *
 * The two surfaces under test:
 *  - [walkAndNotify] walks the subtree via [IJSwingUtilities.updateComponentTreeUI] then
 *    publishes [ComponentTreeRefreshedTopic.TOPIC] so subscribers reapply.
 *  - [notifyOnly] publishes the same topic without walking — for callers (LAF listener)
 *    where the platform has already done the walk.
 *
 * Disposal guards on both methods are exercised; the inner try/catch around the walk is
 * exercised so a Swing refresh failure doesn't propagate out of subscribers.
 */
class ComponentTreeRefresherTest {
    private lateinit var project: Project
    private lateinit var messageBus: MessageBus
    private lateinit var listener: ComponentTreeRefreshedListener
    private lateinit var root: Component

    @BeforeTest
    fun setUp() {
        listener = mockk(relaxed = true)
        messageBus = mockk()
        every { messageBus.syncPublisher(ComponentTreeRefreshedTopic.TOPIC) } returns listener

        project =
            mockk {
                every { isDisposed } returns false
                every { name } returns "test-project"
                every { this@mockk.messageBus } returns this@ComponentTreeRefresherTest.messageBus
            }

        root = JPanel()

        mockkStatic(IJSwingUtilities::class)
        justRun { IJSwingUtilities.updateComponentTreeUI(any()) }
    }

    @AfterTest
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `walkAndNotify on disposed project is a no-op`() {
        every { project.isDisposed } returns true

        ComponentTreeRefresher.walkAndNotify(project, root)

        verify(exactly = 0) { IJSwingUtilities.updateComponentTreeUI(any()) }
        verify(exactly = 0) { listener.afterRefresh(any()) }
    }

    @Test
    fun `walkAndNotify on healthy project walks the tree then publishes the topic`() {
        ComponentTreeRefresher.walkAndNotify(project, root)

        io.mockk.verifyOrder {
            IJSwingUtilities.updateComponentTreeUI(root)
            listener.afterRefresh(project)
        }
    }

    @Test
    fun `walkAndNotify still publishes the topic even if the tree walk throws`() {
        // Subscribers must always get a chance to reapply their overrides — a Swing refresh
        // failure (LAF mid-swap, ClassCastException on legacy components) shouldn't blackhole
        // the EditorScrollbarManager / ProjectViewScrollbarManager reapply path.
        every { IJSwingUtilities.updateComponentTreeUI(root) } throws IllegalStateException("boom")

        // Production code logs at LOG.warn — override processWarn (not processError) so the
        // TestLoggerFactory warn-to-failure promotion is suppressed for THIS test's
        // intentional throw, but any unexpected LOG.error elsewhere still escalates.
        val capturedWarns = mutableListOf<String>()
        val processor =
            object : LoggedErrorProcessor() {
                override fun processWarn(
                    category: String,
                    message: String,
                    throwable: Throwable?,
                ): Boolean {
                    capturedWarns += message
                    return false
                }
            }

        LoggedErrorProcessor.executeWith<Throwable>(processor) {
            ComponentTreeRefresher.walkAndNotify(project, root)
        }

        verify(exactly = 1) { listener.afterRefresh(project) }
        assert(capturedWarns.any { it.contains("Component tree refresh failed") }) {
            "Expected warn about failed tree refresh, got: $capturedWarns"
        }
    }

    @Test
    fun `notifyOnly on disposed project is a no-op`() {
        every { project.isDisposed } returns true

        ComponentTreeRefresher.notifyOnly(project)

        verify(exactly = 0) { listener.afterRefresh(any()) }
    }

    @Test
    fun `notifyOnly on healthy project publishes the topic without walking the tree`() {
        ComponentTreeRefresher.notifyOnly(project)

        verify(exactly = 1) { listener.afterRefresh(project) }
        verify(exactly = 0) { IJSwingUtilities.updateComponentTreeUI(any()) }
    }
}
