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
import kotlin.test.assertTrue

class SyntaxLanguageResolverTest {
    private val project = mockk<Project>(relaxed = true)

    @Test
    fun `active supported file language wins over persisted fallback`() {
        val resolver =
            SyntaxLanguageResolver(
                projectVerdict = { _, _ -> error("Project verdict must not be read for a supported active file") },
            )

        val selected =
            resolver.resolve(
                project = project,
                activeFileType = languageFileType("Swift"),
                fallbackLanguage = "Kotlin",
            )

        assertEquals("Swift", selected)
    }

    @Test
    fun `active Noctule Swift file resolves through the catalog alias`() {
        val resolver = SyntaxLanguageResolver()

        val selected =
            resolver.resolve(
                project = project,
                activeFileType = languageFileType("NoctuleSwift"),
                fallbackLanguage = "Kotlin",
            )

        assertEquals("Swift", selected)
    }

    @Test
    fun `active Shell Script file resolves through the Bash catalog alias`() {
        val resolver = SyntaxLanguageResolver()

        val selected =
            resolver.resolve(
                project = project,
                activeFileType = languageFileType("Shell Script"),
                fallbackLanguage = "Kotlin",
            )

        assertEquals("Bash", selected)
    }

    @Test
    fun `active catalog language survives unavailable native file type`() {
        val resolver = SyntaxLanguageResolver()

        val selected =
            resolver.resolve(
                project = project,
                activeFileType = languageFileType("Swift"),
                fallbackLanguage = "Kotlin",
            )

        assertEquals("Swift", selected)
    }

    @Test
    fun `cached dominant project language fills an unavailable active editor`() {
        val resolver =
            SyntaxLanguageResolver(
                projectVerdict = { _, _ ->
                    ProjectLanguageVerdict.Detected(
                        languageId = "noctuleswift",
                        weights = mapOf("noctuleswift" to 1_000L),
                    )
                },
            )

        val selected = resolver.resolve(project, activeFileType = null, fallbackLanguage = "Kotlin")

        assertEquals("Swift", selected)
    }

    @Test
    fun `missing active file requests a bounded project cache warmup`() {
        var shouldWarmCache = false
        val resolver =
            SyntaxLanguageResolver(
                projectVerdict = { _, warmCache ->
                    shouldWarmCache = warmCache
                    ProjectLanguageVerdict.Cold
                },
            )

        resolver.resolve(project, activeFileType = null, fallbackLanguage = "Kotlin")

        assertTrue(shouldWarmCache)
    }

    @Test
    fun `unsupported active language preserves persisted catalog fallback`() {
        val resolver =
            SyntaxLanguageResolver(
                projectVerdict = { _, _ -> ProjectLanguageVerdict.Cold },
            )

        val selected =
            resolver.resolve(
                project = project,
                activeFileType = languageFileType("UnsupportedLanguage"),
                fallbackLanguage = "Swift",
            )

        assertEquals("Swift", selected)
    }

    @Test
    fun `missing project preserves persisted catalog fallback without reading an editor`() {
        val resolver = SyntaxLanguageResolver()

        val selected = resolver.resolve(project = null, activeFileType = null, fallbackLanguage = "Kotlin")

        assertEquals("Kotlin", selected)
    }

    private fun languageFileType(languageId: String): FileType {
        val language = mockk<Language>()
        every { language.id } returns languageId
        every { language.displayName } returns languageId
        return mockk<LanguageFileType> {
            every { this@mockk.language } returns language
            every { name } returns languageId
        }
    }
}
