package dev.ayuislands.font

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

class FontPresetApplicatorTest {
    @AfterTest
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `background apply uses IntelliJ write-safe dispatcher`() {
        val application = mockk<Application>(relaxed = true)
        mockkStatic(ApplicationManager::class)
        mockkStatic(SwingUtilities::class)
        every { ApplicationManager.getApplication() } returns application
        every { SwingUtilities.isEventDispatchThread() } returns false
        every { application.invokeLater(any<Runnable>(), ModalityState.nonModal()) } returns Unit

        FontPresetApplicator.apply(FontSettings.fromPreset(FontPreset.AMBIENT))

        verify(exactly = 1) {
            application.invokeLater(any<Runnable>(), ModalityState.nonModal())
        }
        verify(exactly = 0) { SwingUtilities.invokeLater(any()) }
    }

    @Test
    fun `background apply keeps Swing fallback without an Application`() {
        mockkStatic(ApplicationManager::class)
        mockkStatic(SwingUtilities::class)
        every { ApplicationManager.getApplication() } returns null
        every { SwingUtilities.isEventDispatchThread() } returns false
        every { SwingUtilities.invokeLater(any()) } returns Unit

        FontPresetApplicator.apply(FontSettings.fromPreset(FontPreset.AMBIENT))

        verify(exactly = 1) { SwingUtilities.invokeLater(any()) }
    }
}
