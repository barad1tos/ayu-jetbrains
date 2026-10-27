package dev.ayuislands.settings

import com.intellij.openapi.project.Project
import io.mockk.every
import io.mockk.mockk
import java.awt.KeyboardFocusManager
import java.beans.PropertyChangeEvent
import java.beans.PropertyChangeListener
import javax.swing.JPanel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class FocusedEditorContextTest {
    @Test
    fun `disposing the service removes its focus subscription`() {
        val focusManager = KeyboardFocusManager.getCurrentKeyboardFocusManager()
        val originalListeners = focusManager.getPropertyChangeListeners("permanentFocusOwner").toSet()
        val context = FocusedEditorContext()

        try {
            val subscribedListeners = focusManager.getPropertyChangeListeners("permanentFocusOwner").toSet()
            assertEquals(1, (subscribedListeners - originalListeners).size)
            assertTrue(subscribedListeners.containsAll(originalListeners))
        } finally {
            context.dispose()
        }

        assertEquals(originalListeners, focusManager.getPropertyChangeListeners("permanentFocusOwner").toSet())
    }

    @Test
    fun `incomplete focus contexts preserve the last editor selection`() {
        val project = mockk<Project>()
        val file = ActiveFileContext("Recovery.kt", "Kotlin", setOf("kotlin"))
        val selectedContext = SettingsOpenContext(project, file)
        val capture = FocusedEditorCapture()
        assertTrue(capture.record(selectedContext))

        assertFalse(capture.record(SettingsOpenContext(null, file)))
        assertFalse(capture.record(SettingsOpenContext(project, null)))

        assertSame(selectedContext, capture.get(project))
    }

    @Test
    fun `focused editor context remains scoped to its project tab`() {
        val swiftProject = mockk<Project>()
        val kotlinProject = mockk<Project>()
        val swift = ActiveFileContext("Recovery.swift", "NoctuleSwift", setOf("NoctuleSwift", "Swift"))
        val capture = FocusedEditorCapture()

        capture.record(SettingsOpenContext(swiftProject, swift))

        assertNull(capture.get(kotlinProject))
        assertSame(swift, capture.get(swiftProject)?.activeFile)
    }

    @Test
    fun `focus listener captures the editor before settings steals focus`() {
        val project = mockk<Project>()
        val swift = ActiveFileContext("Recovery.swift", "NoctuleSwift", setOf("NoctuleSwift", "Swift"))
        val editorContext = SettingsOpenContext(project, swift)
        val editorComponent = JPanel()
        val settingsComponent = JPanel()
        lateinit var focusListener: PropertyChangeListener
        every { project.name } returns "GenreUpdater"
        val context =
            FocusedEditorContext(
                subscribeFocus = { registered ->
                    focusListener = registered
                    {}
                },
                focusedEditorContext = { component ->
                    editorContext.takeIf { component === editorComponent }
                },
            )

        focusListener.propertyChange(
            PropertyChangeEvent(
                settingsComponent,
                "permanentFocusOwner",
                editorComponent,
                settingsComponent,
            ),
        )

        assertSame(swift, context.get(project)?.activeFile)
        context.dispose()
    }
}
