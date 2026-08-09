package dev.ayuislands.settings.mappings

import com.intellij.openapi.project.Project
import dev.ayuislands.accent.AccentResolver
import dev.ayuislands.accent.ProjectLanguageDetector
import dev.ayuislands.accent.ProjectLanguageVerdict
import dev.ayuislands.licensing.LicenseChecker
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
import io.mockk.verify
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

class AccentMappingsPreviewTest {
    @BeforeTest
    fun setUp() {
        mockkObject(ProjectLanguageDetector)
        every { ProjectLanguageDetector.verdict(any()) } returns ProjectLanguageVerdict.Cold
        every { ProjectLanguageDetector.dominant(any()) } throws AssertionError("dominant must not be read")

        mockkObject(LicenseChecker)
        every { LicenseChecker.isLicensedOrGrace() } returns true
    }

    @AfterTest
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `preview returns one consistent project override result`() {
        val projectPath = File(System.getProperty("java.io.tmpdir"), "preview-project").canonicalPath
        val project = stubProject(File(projectPath))
        val draft =
            AccentMappingsDraft().apply {
                addProject(ProjectMapping(projectPath, "Project", "#AABBCC"))
            }
        val builder = OverridesGroupBuilder(draft = draft)

        assertEquals(
            PendingAccentPreview(
                hex = "#AABBCC",
                source = AccentResolver.Source.PROJECT_OVERRIDE,
                detail = null,
            ),
            builder.preview(project, fallbackGlobalHex = "#FFCC66"),
        )
    }

    @Test
    fun `cache-only preview returns detected language with detail`() {
        val project = stubProject(File(System.getProperty("java.io.tmpdir"), "preview-language"))
        every { ProjectLanguageDetector.verdict(project) } returns
            ProjectLanguageVerdict.Detected("kotlin", mapOf("kotlin" to 1_000L))
        val draft =
            AccentMappingsDraft().apply {
                addLanguage(LanguageMapping("kotlin", "Kotlin", "#112233"))
            }

        assertEquals(
            PendingAccentPreview("#112233", AccentResolver.Source.LANGUAGE_OVERRIDE, "Kotlin, 100%"),
            OverridesGroupBuilder(draft = draft).preview(project, "#FFCC66", cacheOnly = true),
        )
        verify(exactly = 0) { ProjectLanguageDetector.dominant(project) }
    }

    @Test
    fun `cache-only preview uses one detector snapshot for winner and detail`() {
        val project = stubProject(File(System.getProperty("java.io.tmpdir"), "preview-snapshot"))
        every { ProjectLanguageDetector.verdict(project) } returnsMany
            listOf(
                ProjectLanguageVerdict.Detected("kotlin", mapOf("kotlin" to 1_000L)),
                ProjectLanguageVerdict.Detected("python", mapOf("python" to 1_000L)),
            )
        val draft =
            AccentMappingsDraft().apply {
                addLanguage(LanguageMapping("kotlin", "Kotlin", "#112233"))
                addLanguage(LanguageMapping("python", "Python", "#3572A5"))
            }

        assertEquals(
            PendingAccentPreview("#112233", AccentResolver.Source.LANGUAGE_OVERRIDE, "Kotlin, 100%"),
            OverridesGroupBuilder(draft = draft).preview(project, "#FFCC66", cacheOnly = true),
        )
        verify(exactly = 1) { ProjectLanguageDetector.verdict(project) }
    }

    @Test
    fun `cache-only preview returns language fallback with detected detail`() {
        val project = stubProject(File(System.getProperty("java.io.tmpdir"), "preview-language-fallback"))
        every { ProjectLanguageDetector.verdict(project) } returns
            ProjectLanguageVerdict.Detected("typescript", mapOf("typescript" to 1_000L))
        val draft =
            AccentMappingsDraft().apply {
                setLanguageFallbackAccent("#73D0FF")
            }

        assertEquals(
            PendingAccentPreview(
                "#73D0FF",
                AccentResolver.Source.LANGUAGE_FALLBACK_OVERRIDE,
                "TypeScript, 100%",
            ),
            OverridesGroupBuilder(draft = draft).preview(project, "#FFCC66", cacheOnly = true),
        )
        verify(exactly = 0) { ProjectLanguageDetector.dominant(project) }
    }

    @Test
    fun `cache-only preview returns project fallback for no winner`() {
        val projectPath = File(System.getProperty("java.io.tmpdir"), "preview-project-fallback").canonicalPath
        val project = stubProject(File(projectPath))
        every { ProjectLanguageDetector.verdict(project) } returns
            ProjectLanguageVerdict.NoWinner(mapOf("typescript" to 500L, "javascript" to 500L))
        val draft =
            AccentMappingsDraft().apply {
                setProjectFallbackAccent(projectPath, "#5CCFE6")
            }

        assertEquals(
            PendingAccentPreview("#5CCFE6", AccentResolver.Source.PROJECT_FALLBACK, null),
            OverridesGroupBuilder(draft = draft).preview(project, "#FFCC66", cacheOnly = true),
        )
        verify(exactly = 0) { ProjectLanguageDetector.dominant(project) }
    }

    @Test
    fun `cache-only preview keeps global fallback while detector is cold`() {
        val projectPath = File(System.getProperty("java.io.tmpdir"), "preview-cold").canonicalPath
        val project = stubProject(File(projectPath))
        every { ProjectLanguageDetector.verdict(project) } returns ProjectLanguageVerdict.Cold
        val draft =
            AccentMappingsDraft().apply {
                setProjectFallbackAccent(projectPath, "#5CCFE6")
            }

        assertEquals(
            PendingAccentPreview("#FFCC66", AccentResolver.Source.GLOBAL, null),
            OverridesGroupBuilder(draft = draft).preview(project, "#FFCC66", cacheOnly = true),
        )
        verify(exactly = 0) { ProjectLanguageDetector.dominant(project) }
    }

    @Test
    fun `cache-only preview returns forced language with manual detail`() {
        val projectPath = File(System.getProperty("java.io.tmpdir"), "preview-forced-language").canonicalPath
        val project = stubProject(File(projectPath))
        val draft =
            AccentMappingsDraft().apply {
                addLanguage(LanguageMapping("typescript", "TypeScript", "#3178C6"))
                setForcedLanguage(projectPath, "typescript")
            }

        assertEquals(
            PendingAccentPreview(
                "#3178C6",
                AccentResolver.Source.FORCED_LANGUAGE_OVERRIDE,
                "TypeScript, manual",
            ),
            OverridesGroupBuilder(draft = draft).preview(project, "#FFCC66", cacheOnly = true),
        )
        verify(exactly = 0) { ProjectLanguageDetector.dominant(project) }
    }

    @Test
    fun `cache-only preview suppresses overrides while unlicensed`() {
        every { LicenseChecker.isLicensedOrGrace() } returns false
        val projectPath = File(System.getProperty("java.io.tmpdir"), "preview-unlicensed").canonicalPath
        val project = stubProject(File(projectPath))
        val draft =
            AccentMappingsDraft().apply {
                addProject(ProjectMapping(projectPath, "Project", "#AABBCC"))
            }

        assertEquals(
            PendingAccentPreview("#FFCC66", AccentResolver.Source.GLOBAL, null),
            OverridesGroupBuilder(draft = draft).preview(project, "#FFCC66", cacheOnly = true),
        )
        verify(exactly = 0) { ProjectLanguageDetector.dominant(project) }
    }

    private fun stubProject(baseDirectory: File): Project {
        val project = mockk<Project>()
        every { project.isDefault } returns false
        every { project.isDisposed } returns false
        every { project.basePath } returns baseDirectory.path
        every { project.name } returns baseDirectory.name
        return project
    }
}
