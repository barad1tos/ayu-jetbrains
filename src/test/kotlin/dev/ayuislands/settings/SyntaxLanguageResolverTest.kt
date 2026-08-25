package dev.ayuislands.settings

import com.intellij.lang.Language
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.fileTypes.LanguageFileType
import com.intellij.openapi.project.Project
import dev.ayuislands.accent.ProjectLanguageVerdict
import dev.ayuislands.syntax.SyntaxLanguageRegistry
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

class SyntaxLanguageResolverTest {
    private lateinit var editorFixture: SyntaxPreviewEditorFixture
    private lateinit var project: Project

    @BeforeTest
    fun setUp() {
        editorFixture = SyntaxPreviewEditorFixture().also(SyntaxPreviewEditorFixture::install)
        project = mockk(relaxed = true)
    }

    @AfterTest
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `active supported file language wins over cached project language`() {
        val swiftPreviewType = editorFixture.mockFileType("Swift", "swift")
        every { editorFixture.fileTypeManager.getStdFileType("Swift") } returns swiftPreviewType
        val resolver =
            SyntaxLanguageResolver(
                selectedFileType = { languageFileType("Swift") },
                projectVerdict = { ProjectLanguageVerdict.Detected("kotlin", weights = null) },
            )

        val selected =
            resolver.resolve(
                project = project,
                fallbackLanguage = "Kotlin",
                supportedLanguages = supportedLanguages(),
            )

        assertEquals("Swift", selected)
    }

    @Test
    fun `cached project language wins when active language is unsupported`() {
        val swiftPreviewType = editorFixture.mockFileType("Swift", "swift")
        every { editorFixture.fileTypeManager.getStdFileType("Swift") } returns swiftPreviewType
        val resolver =
            SyntaxLanguageResolver(
                selectedFileType = { languageFileType("UnsupportedLanguage") },
                projectVerdict = { ProjectLanguageVerdict.Detected("swift", weights = null) },
            )

        val selected =
            resolver.resolve(
                project = project,
                fallbackLanguage = "Kotlin",
                supportedLanguages = supportedLanguages(),
            )

        assertEquals("Swift", selected)
    }

    @Test
    fun `active language without a preview highlighter falls through to cached project language`() {
        val unavailableFileType = editorFixture.mockFileType("Unavailable", "txt")
        every { editorFixture.fileTypeManager.getStdFileType("Swift") } returns unavailableFileType
        val resolver =
            SyntaxLanguageResolver(
                selectedFileType = { languageFileType("Swift") },
                projectVerdict = { ProjectLanguageVerdict.Detected("kotlin", weights = null) },
            )

        val selected =
            resolver.resolve(
                project = project,
                fallbackLanguage = "Kotlin",
                supportedLanguages = supportedLanguages(),
            )

        assertEquals("Kotlin", selected)
    }

    @Test
    fun `missing project preserves the existing Kotlin fallback`() {
        val resolver =
            SyntaxLanguageResolver(
                selectedFileType = { error("Selected file must not be read without a project") },
                projectVerdict = { error("Project verdict must not be read without a project") },
            )

        val selected =
            resolver.resolve(
                project = null,
                supportedLanguages = supportedLanguages(),
                fallbackLanguage = "Kotlin",
            )

        assertEquals("Kotlin", selected)
    }

    private fun supportedLanguages(): List<SyntaxLanguageRegistry.LangTag> =
        listOf(
            SyntaxLanguageRegistry.LangTag("Kotlin", "Kotlin", SyntaxLanguageRegistry.Bucket.LANGUAGE),
            SyntaxLanguageRegistry.LangTag("Swift", "Swift", SyntaxLanguageRegistry.Bucket.LANGUAGE),
        )

    private fun languageFileType(languageId: String): FileType {
        val language = mockk<Language>()
        every { language.id } returns languageId
        return mockk<LanguageFileType> {
            every { this@mockk.language } returns language
            every { name } returns languageId
            every { defaultExtension } returns languageId.lowercase()
        }
    }
}
