package dev.ayuislands.settings

import com.intellij.lang.Language
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.fileTypes.LanguageFileType
import com.intellij.openapi.project.Project
import dev.ayuislands.accent.ProjectLanguageVerdict
import io.mockk.every
import io.mockk.mockk
import kotlin.test.Test
import kotlin.test.assertEquals

class SyntaxLanguageResolverTest {
    private val project = mockk<Project>(relaxed = true)

    @Test
    fun `active supported file language wins over persisted fallback`() {
        val resolver =
            SyntaxLanguageResolver(
                selectedFileType = { languageFileType("Swift") },
                projectVerdict = { error("Project verdict must not be read for a supported active file") },
            )

        val selected = resolver.resolve(project, fallbackLanguage = "Kotlin")

        assertEquals("Swift", selected)
    }

    @Test
    fun `active Noctule Swift file resolves through the catalog alias`() {
        val resolver = SyntaxLanguageResolver(selectedFileType = { languageFileType("NoctuleSwift") })

        val selected = resolver.resolve(project, fallbackLanguage = "Kotlin")

        assertEquals("Swift", selected)
    }

    @Test
    fun `active Shell Script file resolves through the Bash catalog alias`() {
        val resolver = SyntaxLanguageResolver(selectedFileType = { languageFileType("Shell Script") })

        val selected = resolver.resolve(project, fallbackLanguage = "Kotlin")

        assertEquals("Bash", selected)
    }

    @Test
    fun `active catalog language survives unavailable native file type`() {
        val resolver = SyntaxLanguageResolver(selectedFileType = { languageFileType("Swift") })

        val selected = resolver.resolve(project, fallbackLanguage = "Kotlin")

        assertEquals("Swift", selected)
    }

    @Test
    fun `cached dominant project language fills an unavailable active editor`() {
        val resolver =
            SyntaxLanguageResolver(
                selectedFileType = { null },
                projectVerdict = {
                    ProjectLanguageVerdict.Detected(
                        languageId = "noctuleswift",
                        weights = mapOf("noctuleswift" to 1_000L),
                    )
                },
            )

        val selected = resolver.resolve(project, fallbackLanguage = "Kotlin")

        assertEquals("Swift", selected)
    }

    @Test
    fun `unsupported active language preserves persisted catalog fallback`() {
        val resolver = SyntaxLanguageResolver(selectedFileType = { languageFileType("UnsupportedLanguage") })

        val selected = resolver.resolve(project, fallbackLanguage = "Swift")

        assertEquals("Swift", selected)
    }

    @Test
    fun `missing project preserves persisted catalog fallback without reading an editor`() {
        val resolver =
            SyntaxLanguageResolver(
                selectedFileType = { error("Selected file must not be read without a project") },
            )

        val selected = resolver.resolve(project = null, fallbackLanguage = "Kotlin")

        assertEquals("Kotlin", selected)
    }

    private fun languageFileType(languageId: String): FileType {
        val language = mockk<Language>()
        every { language.id } returns languageId
        every { language.displayName } returns languageId
        return mockk<LanguageFileType> {
            every { this@mockk.language } returns language
        }
    }
}
