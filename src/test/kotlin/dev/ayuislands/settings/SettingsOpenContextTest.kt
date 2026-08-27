package dev.ayuislands.settings

import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.project.Project
import io.mockk.mockk
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class SettingsOpenContextTest {
    @Test
    fun `settings capture prefers the focused editor context over the detached live manager`() {
        val project = mockk<Project>(relaxed = true)
        val swift = mockk<FileType>(relaxed = true)
        var liveReads = 0
        val source =
            SettingsOpenContextSource(
                focusedProject = { project },
                focusedEditorContext = { SettingsOpenContext(project, swift) },
                liveFileType = {
                    liveReads += 1
                    null
                },
            )

        val context = source.capture()

        assertSame(project, context.project)
        assertSame(swift, context.activeFileType)
        assertEquals(0, liveReads)
    }
}
