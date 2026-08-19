package dev.ayuislands.ui

import com.intellij.openapi.project.Project
import com.intellij.util.messages.MessageBus
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import io.mockk.verify
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test

/**
 * Locks in the contract of [ComponentTreeRefresher] — the central pipeline subscribers
 * (EditorScrollbarManager, ProjectViewScrollbarManager) hang their self-heal logic off.
 */
class ComponentTreeRefresherTest {
    private lateinit var project: Project
    private lateinit var messageBus: MessageBus
    private lateinit var listener: ComponentTreeRefreshedListener

    @BeforeTest
    fun setUp() {
        listener = mockk(relaxed = true)
        messageBus = mockk()
        every { messageBus.syncPublisher(ComponentTreeRefreshedTopic.TOPIC) } returns listener

        project =
            mockk {
                every { isDisposed } returns false
                every { this@mockk.messageBus } returns this@ComponentTreeRefresherTest.messageBus
            }
    }

    @AfterTest
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `notifyOnly on disposed project is a no-op`() {
        every { project.isDisposed } returns true

        ComponentTreeRefresher.notifyOnly(project)

        verify(exactly = 0) { listener.afterRefresh(any()) }
    }

    @Test
    fun `notifyOnly on healthy project publishes the topic`() {
        ComponentTreeRefresher.notifyOnly(project)

        verify(exactly = 1) { listener.afterRefresh(project) }
    }
}
