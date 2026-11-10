package dev.ayuislands.accent

import com.intellij.openapi.application.Application
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import javax.swing.SwingUtilities
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertTrue

class AccentApplyPlanRunnerTest {
    @AfterTest
    fun tearDown() = unmockkAll()

    @Test
    fun `posts existing lifecycle callbacks with nonModal modality`() {
        val application = mockk<Application>()
        mockkStatic(ApplicationManager::class)
        every { ApplicationManager.getApplication() } returns application
        every { application.invokeLater(any(), any<ModalityState>()) } answers { firstArg<Runnable>().run() }
        var completed = false

        AccentApplyPlanRunner.post { completed = true }

        verify(exactly = 1) { application.invokeLater(any(), ModalityState.nonModal()) }
        assertTrue(completed)
    }

    @Test
    fun `falls back to SwingUtilities when application is unavailable`() {
        mockkStatic(ApplicationManager::class, SwingUtilities::class)
        every { ApplicationManager.getApplication() } returns null
        every { SwingUtilities.invokeLater(any()) } answers { firstArg<Runnable>().run() }
        var completed = false

        AccentApplyPlanRunner.post { completed = true }

        verify(exactly = 1) { SwingUtilities.invokeLater(any()) }
        assertTrue(completed)
    }
}
