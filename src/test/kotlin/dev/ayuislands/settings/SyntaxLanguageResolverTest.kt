package dev.ayuislands.settings

import com.intellij.openapi.project.Project
import dev.ayuislands.accent.ProjectLanguageVerdict
import dev.ayuislands.syntax.SyntaxLanguageRegistry
import io.mockk.mockk
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SyntaxLanguageResolverTest {
    private val project = mockk<Project>(relaxed = true)
    private val coldProjectResolver =
        SyntaxLanguageResolver(
            projectVerdict = { _, _ -> ProjectLanguageVerdict.Cold },
        )

    @Test
    fun `active supported file language wins over persisted fallback`() {
        val resolver =
            SyntaxLanguageResolver(
                projectVerdict = { _, _ -> error("Project verdict must not be read for a supported active file") },
            )

        val selected =
            resolver.resolve(
                project = project,
                activeFile = activeFile(languageId = "Swift"),
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
                activeFile = activeFile(languageId = "NoctuleSwift"),
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
                activeFile = activeFile(languageId = "Shell Script"),
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
                activeFile = activeFile(languageId = "Swift"),
                fallbackLanguage = "Kotlin",
            )

        assertEquals("Swift", selected)
    }

    @Test
    fun `special file name wins over a generic host language`() {
        val selected =
            coldProjectResolver.resolve(
                project = project,
                activeFile = activeFile(".gitlab-ci.yml", "YAML", "YAML"),
                fallbackLanguage = "Kotlin",
            )

        assertEquals("GitLab CI", selected)
    }

    @Test
    fun `native file type selects ReSharper C sharp without a matching language id`() {
        val selected =
            coldProjectResolver.resolve(
                project = project,
                activeFile = activeFile("Program.cs", "C#", "CSharp"),
                fallbackLanguage = "Kotlin",
            )

        assertEquals("C# (ReSharper)", selected)
    }

    @Test
    fun `unique extension selects a language when native identities are unavailable`() {
        val selected =
            coldProjectResolver.resolve(
                project = project,
                activeFile = activeFile("Info.plist", "Plain text"),
                fallbackLanguage = "Kotlin",
            )

        assertEquals("Apple plist", selected)
    }

    @Test
    fun `ambiguous Terraform identity preserves the persisted fallback`() {
        var projectReads = 0
        val resolver =
            SyntaxLanguageResolver(
                projectVerdict = { _, _ ->
                    projectReads += 1
                    ProjectLanguageVerdict.Cold
                },
            )

        val selected =
            resolver.resolve(
                project = project,
                activeFile = activeFile("main.tf", "HCL"),
                fallbackLanguage = "Kotlin",
            )

        assertEquals("Kotlin", selected)
        assertEquals(1, projectReads)
    }

    @Test
    fun `every advertised language resolves from its declared native identity`() {
        SyntaxLanguageRegistry.specifications().forEach { specification ->
            val profile = specification.nativeProfiles.single()
            val preview = specification.preview.files.single()

            val selected =
                coldProjectResolver.resolve(
                    project = project,
                    activeFile =
                        activeFile(
                            preview.fileName,
                            profile.fileTypeNames.first(),
                            *profile.languageIds.toTypedArray(),
                        ),
                    fallbackLanguage = "Kotlin",
                )

            assertEquals(specification.displayName, selected, specification.displayName)
        }
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

        val selected = resolver.resolve(project, activeFile = null, fallbackLanguage = "Kotlin")

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

        resolver.resolve(project, activeFile = null, fallbackLanguage = "Kotlin")

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
                activeFile = activeFile(languageId = "UnsupportedLanguage"),
                fallbackLanguage = "Swift",
            )

        assertEquals("Swift", selected)
    }

    @Test
    fun `missing project preserves persisted catalog fallback without reading an editor`() {
        val resolver = SyntaxLanguageResolver()

        val selected = resolver.resolve(project = null, activeFile = null, fallbackLanguage = "Kotlin")

        assertEquals("Kotlin", selected)
    }

    private fun activeFile(languageId: String): ActiveFileContext = activeFile("Preview.swift", languageId, languageId)

    private fun activeFile(
        fileName: String,
        fileTypeName: String,
        vararg languageIds: String,
    ): ActiveFileContext =
        ActiveFileContext(
            fileName = fileName,
            fileTypeName = fileTypeName,
            languageIds = languageIds.toSet(),
        )
}
