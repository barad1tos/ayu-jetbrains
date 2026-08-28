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
import io.mockk.verify
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

    init {
        every { fileTypes.findFileTypeByName(any()) } returns UnknownFileType.INSTANCE
        every { fileTypes.registeredFileTypes } returns emptyArray()
    }

    @Test
    fun `registered filename association short-circuits broader registries`() {
        val noctule = languageFileType("NoctuleSwift", "NoctuleSwift")
        every { fileTypes.getFileTypeByFileName(preview.fileName) } returns noctule

        val resolution = resolver.resolve(preview.fileName, profile)

        assertSame(noctule, assertIs<NativePreviewResolution.Resolved>(resolution).fileType)
        verify(exactly = 0) { fileTypes.getStdFileType(any()) }
        verify(exactly = 0) { fileTypes.registeredFileTypes }
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
    fun `registered language type resolves without a filename association`() {
        val qute = languageFileType("Qute", "Qute")
        val quteProfile = requireNotNull(SyntaxLanguageRegistry.findByStorageId("Qute")).nativeProfiles.single()
        every { fileTypes.getFileTypeByFileName(any()) } returns PlainTextFileType.INSTANCE
        every { fileTypes.getStdFileType(any()) } returns UnknownFileType.INSTANCE
        every { fileTypes.registeredFileTypes } returns arrayOf(qute)

        val resolution = resolver.resolve("preview.html", quteProfile)

        assertSame(qute, assertIs<NativePreviewResolution.Resolved>(resolution).fileType)
    }

    @Test
    fun `associated language type resolves contextual providers`() {
        val djangoFileType = languageFileType("DjangoTemplate", "DjangoTemplate")
        val djangoLanguage = mockk<Language> { every { associatedFileType } returns djangoFileType }
        val djangoProfile = requireNotNull(SyntaxLanguageRegistry.findByStorageId("Django")).nativeProfiles.single()
        val contextualResolver =
            NativePreviewResolver(
                fileTypes = { fileTypes },
                languageById = { languageId -> djangoLanguage.takeIf { languageId == "DjangoTemplate" } },
            )
        every { fileTypes.getFileTypeByFileName(any()) } returns PlainTextFileType.INSTANCE
        every { fileTypes.getStdFileType(any()) } returns UnknownFileType.INSTANCE

        val resolution = contextualResolver.resolve("preview.html", djangoProfile)

        assertSame(djangoFileType, assertIs<NativePreviewResolution.Resolved>(resolution).fileType)
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
