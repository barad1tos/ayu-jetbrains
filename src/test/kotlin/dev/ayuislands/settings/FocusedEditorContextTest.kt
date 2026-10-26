package dev.ayuislands.settings

import com.intellij.openapi.project.Project
import io.mockk.every
import io.mockk.mockk
import java.beans.PropertyChangeEvent
import java.beans.PropertyChangeListener
import javax.swing.JPanel
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class FocusedEditorContextTest {
    @Test
    fun `disposing the service removes its focus subscription`() {
        var isSubscribed = false
        val context =
            FocusedEditorContext(subscribeFocus = {
                isSubscribed = true
                val unsubscribe: () -> Unit = { isSubscribed = false }
                unsubscribe
            })

        assertTrue(isSubscribed)
        context.dispose()
        assertFalse(isSubscribed)
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
