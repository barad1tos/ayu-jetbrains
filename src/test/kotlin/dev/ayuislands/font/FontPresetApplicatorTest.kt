package dev.ayuislands.font

import com.intellij.openapi.application.Application
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.colors.EditorColorsScheme
import com.intellij.openapi.editor.colors.ModifiableFontPreferences
import dev.ayuislands.theme.EditorSchemeChange
import io.mockk.every
import io.mockk.justRun
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.slot
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
    fun `apply queues editor scheme writes through non-modal application dispatch`() {
        val application = mockk<Application>()
        val colorsManager = mockk<EditorColorsManager>()
        val scheme = mockk<EditorColorsScheme>(relaxed = true)
        val preferences = mockk<ModifiableFontPreferences>(relaxed = true)
        val queuedTask = slot<Runnable>()

        mockkStatic(SwingUtilities::class)
        every { SwingUtilities.isEventDispatchThread() } returns false
        every { SwingUtilities.invokeLater(any()) } returns Unit
        mockkStatic(ApplicationManager::class)
        every { ApplicationManager.getApplication() } returns application
        every { application.invokeLater(capture(queuedTask), ModalityState.nonModal()) } returns Unit
        mockkStatic(EditorColorsManager::class)
        every { EditorColorsManager.getInstance() } returns colorsManager
        every { colorsManager.globalScheme } returns scheme
        every { scheme.fontPreferences } returns preferences
        mockkObject(EditorSchemeChange)
        justRun { EditorSchemeChange.publish() }

        val settings = FontSettings.fromPreset(FontPreset.CUSTOM)
        FontPresetApplicator.apply(settings)

        verify(exactly = 1) { application.invokeLater(any(), ModalityState.nonModal()) }
        verify(exactly = 0) { SwingUtilities.invokeLater(any()) }
        verify(exactly = 0) { scheme.setEditorFontSize(any<Float>()) }

        queuedTask.captured.run()

        verify(exactly = 1) { scheme.setEditorFontSize(settings.fontSize) }
        verify(exactly = 1) { EditorSchemeChange.publish() }
    }
}
