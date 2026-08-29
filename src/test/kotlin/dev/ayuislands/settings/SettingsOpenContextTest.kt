package dev.ayuislands.settings

import com.intellij.lang.Language
import com.intellij.openapi.fileTypes.LanguageFileType
import com.intellij.openapi.project.Project
import io.mockk.every
import io.mockk.mockk
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class SettingsOpenContextTest {
    @Test
    fun `settings capture prefers the focused editor context over the detached live manager`() {
        val project = mockk<Project>(relaxed = true)
        val swift = ActiveFileContext("Recovery.swift", "NoctuleSwift", setOf("NoctuleSwift", "Swift"))
        var liveReads = 0
        val source =
            SettingsOpenContextSource(
                focusedProject = { project },
                focusedEditorContext = { SettingsOpenContext(project, swift) },
                liveFileContext = {
                    liveReads += 1
                    null
                },
            )

        val context = source.capture()

        assertSame(project, context.project)
        assertSame(swift, context.activeFile)
        assertEquals(0, liveReads)
    }

    @Test
    fun `active file snapshot copies native identities without retaining the file type`() {
        val language = mockk<Language>()
        every { language.id } returns "NoctuleSwift"
        every { language.displayName } returns "Swift"
        val fileType =
            mockk<LanguageFileType> {
                every { name } returns "NoctuleSwift"
                every { this@mockk.language } returns language
            }

        val context = ActiveFileContext.capture("Recovery.swift", fileType)

        assertEquals("Recovery.swift", context.fileName)
        assertEquals("NoctuleSwift", context.fileTypeName)
        assertEquals(setOf("NoctuleSwift", "Swift"), context.languageIds)
    }
}
