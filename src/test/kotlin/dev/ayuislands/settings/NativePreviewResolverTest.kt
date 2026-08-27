package dev.ayuislands.settings

import com.intellij.lang.Language
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.fileTypes.LanguageFileType
import com.intellij.openapi.fileTypes.PlainTextFileType
import com.intellij.openapi.fileTypes.UnknownFileType
import com.intellij.openapi.progress.ProcessCanceledException
import dev.ayuislands.syntax.SyntaxLanguageRegistry
import io.mockk.every
import io.mockk.mockk
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertSame

class NativePreviewResolverTest {
    private val fileTypes = mockk<FileTypeManager>()
    private val resolver = NativePreviewResolver { fileTypes }
    private val swift = requireNotNull(SyntaxLanguageRegistry.findByStorageId("Swift"))
    private val preview = swift.preview.files.single()
    private val profile = swift.nativeProfiles.single()

    @Test
    fun `registered filename association wins over a generic standard type`() {
        val noctule = languageFileType("NoctuleSwift", "NoctuleSwift")
        val generic = languageFileType("Swift", "Swift")
        every { fileTypes.getFileTypeByFileName(preview.fileName) } returns noctule
        every { fileTypes.getStdFileType(any()) } returns generic

        val resolution = resolver.resolve(preview.fileName, profile)

        assertSame(noctule, assertIs<NativePreviewResolution.Resolved>(resolution).fileType)
    }

    @Test
    fun `plain and unknown candidates are unavailable`() {
        every { fileTypes.getFileTypeByFileName(preview.fileName) } returns PlainTextFileType.INSTANCE
        every { fileTypes.getStdFileType(any()) } returns UnknownFileType.INSTANCE

        val resolution = resolver.resolve(preview.fileName, profile)

        assertIs<NativePreviewResolution.Unavailable>(resolution)
    }

    @Test
    fun `non-language and mismatched candidates are rejected`() {
        val nonLanguage = mockk<FileType> { every { name } returns "Swift" }
        val mismatched = languageFileType("Swift", "Kotlin")
        every { fileTypes.getFileTypeByFileName(preview.fileName) } returns nonLanguage
        every { fileTypes.getStdFileType(any()) } returns mismatched

        val resolution = resolver.resolve(preview.fileName, profile)

        assertIs<NativePreviewResolution.Unavailable>(resolution)
    }

    @Test
    fun `lookup failure is typed while process cancellation is rethrown`() {
        val failure = IllegalStateException("registry unavailable")
        every { fileTypes.getFileTypeByFileName(preview.fileName) } throws failure
        every { fileTypes.getStdFileType(any()) } returns UnknownFileType.INSTANCE

        val resolution = resolver.resolve(preview.fileName, profile)

        val unavailable = assertIs<NativePreviewResolution.LookupFailed>(resolution)
        assertSame(failure, unavailable.failure)

        every { fileTypes.getFileTypeByFileName(preview.fileName) } throws ProcessCanceledException()
        assertFailsWith<ProcessCanceledException> { resolver.resolve(preview.fileName, profile) }
    }

    private fun languageFileType(
        fileTypeName: String,
        languageId: String,
    ): LanguageFileType {
        val language = mockk<Language>()
        every { language.id } returns languageId
        every { language.displayName } returns languageId
        return mockk {
            every { name } returns fileTypeName
            every { this@mockk.language } returns language
        }
    }
}
