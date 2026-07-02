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
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Covers [OverridesGroupBuilder.resolvePending] and [OverridesGroupBuilder.sourcePending] —
 * the parallel in-memory resolver the settings UI uses to drive the "Currently active:
 * ... (project override)" label while the user edits the tables, before Apply commits the
 * changes.
 *
 * These behave like [AccentResolver] but read from the pending table models rather than the
 * persistent AccentMappingsSettings. Without tests, the two can silently drift — e.g. a
 * case-sensitivity mismatch on language ids would make the live label claim "language override"
 * while the resolver served the global accent after Apply.
 */
class OverridesGroupBuilderPendingTest {
    private lateinit var mappingsState: AccentMappingsState

    @BeforeTest
    fun setUp() {
        mappingsState = AccentMappingsState()
        val settings = mockk<AccentMappingsSettings>()
        every { settings.state } returns mappingsState
        mockkObject(AccentMappingsSettings.Companion)
        every { AccentMappingsSettings.getInstance() } returns settings

        mockkObject(ProjectLanguageDetector)
        every { ProjectLanguageDetector.dominant(any()) } returns null
        every { ProjectLanguageDetector.verdict(any(), any<Boolean>()) } returns ProjectLanguageVerdict.Cold

        mockkObject(LicenseChecker)
        every { LicenseChecker.isLicensedOrGrace() } returns true
    }

    @AfterTest
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `resolvePending returns project override when stored in pending model`() {
        val tmp = File(System.getProperty("java.io.tmpdir"), "pending-project").canonicalPath
        mappingsState.projectAccents[tmp] = "#ABCDEF"
        val project = stubProject(File(tmp))

        val builder = OverridesGroupBuilder().apply { loadFromState() }

        assertEquals("#ABCDEF", builder.resolvePending(project, "#FFCC66"))
        assertEquals(AccentResolver.Source.PROJECT_OVERRIDE, builder.sourcePending(project))
    }

    @Test
    fun `resolvePending returns language override when project has no pending entry`() {
        mappingsState.languageAccents["kotlin"] = "#112233"
        val project = stubProject(File(System.getProperty("java.io.tmpdir"), "pending-lang"))
        detectLanguage(project, "kotlin")

        val builder = OverridesGroupBuilder().apply { loadFromState() }

        assertEquals("#112233", builder.resolvePending(project, "#FFCC66"))
        assertEquals(AccentResolver.Source.LANGUAGE_OVERRIDE, builder.sourcePending(project))
    }

    @Test
    fun `resolvePending uses language fallback when detected language is unmapped`() {
        mappingsState.languageAccents["kotlin"] = "#112233"
        mappingsState.languageFallbackAccent = "#73D0FF"
        val project = stubProject(File(System.getProperty("java.io.tmpdir"), "pending-language-fallback"))
        detectLanguage(project, "typescript")

        val builder = OverridesGroupBuilder().apply { loadFromState() }

        assertEquals("#73D0FF", builder.resolvePending(project, "#FFCC66"))
        assertEquals(AccentResolver.Source.LANGUAGE_FALLBACK_OVERRIDE, builder.sourcePending(project))
    }

    @Test
    fun `cache-only pending preview uses language fallback for detected unmapped language`() {
        mappingsState.languageFallbackAccent = "#73D0FF"
        val project = stubProject(File(System.getProperty("java.io.tmpdir"), "pending-cache-language-fallback"))
        every { ProjectLanguageDetector.verdict(project) } returns
            ProjectLanguageVerdict.Detected("typescript", mapOf("typescript" to 1_000L))
        every { ProjectLanguageDetector.dominant(project) } throws AssertionError("dominant must not be read")

        val builder = OverridesGroupBuilder().apply { loadFromState() }

        assertEquals("#73D0FF", builder.resolvePending(project, "#FFCC66", cacheOnly = true))
        assertEquals(AccentResolver.Source.LANGUAGE_FALLBACK_OVERRIDE, builder.sourcePending(project, cacheOnly = true))
        io.mockk.verify(exactly = 0) { ProjectLanguageDetector.dominant(project) }
    }

    @Test
    fun `cache-only pending preview uses detected verdict without dominant warmup`() {
        mappingsState.languageAccents["kotlin"] = "#112233"
        val project = stubProject(File(System.getProperty("java.io.tmpdir"), "pending-cache-lang"))
        every { ProjectLanguageDetector.verdict(project) } returns
            ProjectLanguageVerdict.Detected("kotlin", mapOf("kotlin" to 1_000L))
        every { ProjectLanguageDetector.dominant(project) } throws AssertionError("dominant must not be read")

        val builder = OverridesGroupBuilder().apply { loadFromState() }

        assertEquals("#112233", builder.resolvePending(project, "#FFCC66", cacheOnly = true))
        assertEquals(AccentResolver.Source.LANGUAGE_OVERRIDE, builder.sourcePending(project, cacheOnly = true))
        io.mockk.verify(exactly = 0) { ProjectLanguageDetector.dominant(project) }
    }

    @Test
    fun `cache-only pending preview uses no-winner fallback without dominant warmup`() {
        val tmp = File(System.getProperty("java.io.tmpdir"), "pending-cache-fallback").canonicalPath
        mappingsState.projectFallbackAccents[tmp] = "#5CCFE6"
        val project = stubProject(File(tmp))
        every { ProjectLanguageDetector.verdict(project) } returns
            ProjectLanguageVerdict.NoWinner(mapOf("typescript" to 500L, "javascript" to 500L))
        every { ProjectLanguageDetector.dominant(project) } throws AssertionError("dominant must not be read")

        val builder = OverridesGroupBuilder().apply { loadFromState() }

        assertEquals("#5CCFE6", builder.resolvePending(project, "#FFCC66", cacheOnly = true))
        assertEquals(AccentResolver.Source.PROJECT_FALLBACK, builder.sourcePending(project, cacheOnly = true))
        io.mockk.verify(exactly = 0) { ProjectLanguageDetector.dominant(project) }
    }

    @Test
    fun `cache-only pending preview keeps global for cold fallback without dominant warmup`() {
        val tmp = File(System.getProperty("java.io.tmpdir"), "pending-cache-cold").canonicalPath
        mappingsState.projectFallbackAccents[tmp] = "#5CCFE6"
        val project = stubProject(File(tmp))
        every { ProjectLanguageDetector.verdict(project) } returns ProjectLanguageVerdict.Cold
        every { ProjectLanguageDetector.dominant(project) } throws AssertionError("dominant must not be read")

        val builder = OverridesGroupBuilder().apply { loadFromState() }

        assertEquals("#FFCC66", builder.resolvePending(project, "#FFCC66", cacheOnly = true))
        assertEquals(AccentResolver.Source.GLOBAL, builder.sourcePending(project, cacheOnly = true))
        io.mockk.verify(exactly = 0) { ProjectLanguageDetector.dominant(project) }
    }

    @Test
    fun `resolvePending prefers project override over language override`() {
        val tmp = File(System.getProperty("java.io.tmpdir"), "pending-both").canonicalPath
        mappingsState.projectAccents[tmp] = "#111111"
        mappingsState.languageAccents["kotlin"] = "#222222"
        val project = stubProject(File(tmp))
        every { ProjectLanguageDetector.dominant(project) } returns "kotlin"

        val builder = OverridesGroupBuilder().apply { loadFromState() }

        assertEquals("#111111", builder.resolvePending(project, "#FFCC66"))
        assertEquals(AccentResolver.Source.PROJECT_OVERRIDE, builder.sourcePending(project))
    }

    @Test
    fun `resolvePending reports global while unlicensed even when pending overrides exist`() {
        every { LicenseChecker.isLicensedOrGrace() } returns false

        val projectPath = File(System.getProperty("java.io.tmpdir"), "pending-unlicensed-project").canonicalPath
        val forcedPath = File(System.getProperty("java.io.tmpdir"), "pending-unlicensed-forced").canonicalPath
        val fallbackPath = File(System.getProperty("java.io.tmpdir"), "pending-unlicensed-fallback").canonicalPath
        val projectOverride = stubProject(File(projectPath))
        val forcedOverride = stubProject(File(forcedPath))
        val fallbackOverride = stubProject(File(fallbackPath))
        every { ProjectLanguageDetector.dominant(any()) } returns "typescript"
        every { ProjectLanguageDetector.verdict(any()) } returns
            ProjectLanguageVerdict.NoWinner(mapOf("typescript" to 500L, "javascript" to 500L))

        val builder =
            OverridesGroupBuilder().apply {
                seedPendingForTest(
                    projects = listOf(ProjectMapping(projectPath, "Project", "#111111")),
                    languages = listOf(LanguageMapping("typescript", "TypeScript", "#3178C6")),
                )
                seedResolutionOverridesForTest(
                    fallbackAccents = mapOf(fallbackPath to "#5CCFE6"),
                    forcedLanguages = mapOf(forcedPath to "typescript"),
                )
            }

        assertEquals("#FFCC66", builder.resolvePending(projectOverride, "#FFCC66"))
        assertEquals(AccentResolver.Source.GLOBAL, builder.sourcePending(projectOverride))
        assertEquals("#FFCC66", builder.resolvePending(forcedOverride, "#FFCC66"))
        assertEquals(AccentResolver.Source.GLOBAL, builder.sourcePending(forcedOverride))
        assertEquals("#FFCC66", builder.resolvePending(fallbackOverride, "#FFCC66"))
        assertEquals(AccentResolver.Source.GLOBAL, builder.sourcePending(fallbackOverride))
        io.mockk.verify(exactly = 0) { ProjectLanguageDetector.dominant(any()) }
        io.mockk.verify(exactly = 0) { ProjectLanguageDetector.verdict(any()) }
    }

    @Test
    fun `resolvePending uses forced project language before detected language`() {
        val tmp = File(System.getProperty("java.io.tmpdir"), "pending-forced-lang").canonicalPath
        val project = stubProject(File(tmp))
        every { ProjectLanguageDetector.dominant(project) } returns "javascript"

        val builder =
            OverridesGroupBuilder().apply {
                seedPendingForTest(
                    languages =
                        listOf(
                            LanguageMapping("typescript", "TypeScript", "#3178C6"),
                            LanguageMapping("javascript", "JavaScript", "#F7DF1E"),
                        ),
                )
                seedResolutionOverridesForTest(forcedLanguages = mapOf(tmp to "typescript"))
            }

        assertEquals("#3178C6", builder.resolvePending(project, "#FFCC66"))
        assertEquals(AccentResolver.Source.FORCED_LANGUAGE_OVERRIDE, builder.sourcePending(project))
        io.mockk.verify(exactly = 0) { ProjectLanguageDetector.dominant(project) }
    }

    @Test
    fun `resolvePending uses pending forced language removal instead of persisted forced language`() {
        val tmp = File(System.getProperty("java.io.tmpdir"), "pending-forced-removed").canonicalPath
        mappingsState.forcedProjectLanguages[tmp] = "typescript"
        mappingsState.languageAccents["typescript"] = "#3178C6"
        mappingsState.languageAccents["javascript"] = "#F7DF1E"
        val project = stubProject(File(tmp))
        every { ProjectLanguageDetector.dominant(project) } returns "typescript"
        every { ProjectLanguageDetector.verdict(project, warmCache = true) } returns
            ProjectLanguageVerdict.Detected("javascript", mapOf("javascript" to 1_000L))

        val builder = OverridesGroupBuilder().apply { loadFromState() }
        builder.setPendingForcedLanguage(tmp, null)

        assertEquals("#F7DF1E", builder.resolvePending(project, "#FFCC66"))
        assertEquals(AccentResolver.Source.LANGUAGE_OVERRIDE, builder.sourcePending(project))
        io.mockk.verify(atLeast = 1) { ProjectLanguageDetector.verdict(project, warmCache = true) }
    }

    @Test
    fun `resolvePending forced language without mapped accent and no fallback resolves global without detector`() {
        val tmp = File(System.getProperty("java.io.tmpdir"), "pending-forced-no-map").canonicalPath
        val project = stubProject(File(tmp))
        every { ProjectLanguageDetector.dominant(project) } returns "javascript"
        val noWinnerVerdict = ProjectLanguageVerdict.NoWinner(mapOf("typescript" to 500L, "javascript" to 500L))
        every { ProjectLanguageDetector.verdict(project) } returns noWinnerVerdict
        every { ProjectLanguageDetector.verdict(project, warmCache = true) } returns noWinnerVerdict

        val builder =
            OverridesGroupBuilder().apply {
                seedPendingForTest(
                    languages = listOf(LanguageMapping("javascript", "JavaScript", "#F7DF1E")),
                )
                seedResolutionOverridesForTest(forcedLanguages = mapOf(tmp to "typescript"))
            }

        assertEquals("#FFCC66", builder.resolvePending(project, "#FFCC66"))
        assertEquals(AccentResolver.Source.GLOBAL, builder.sourcePending(project))
        io.mockk.verify(exactly = 0) { ProjectLanguageDetector.dominant(project) }
        io.mockk.verify(exactly = 0) { ProjectLanguageDetector.verdict(project) }
    }

    @Test
    fun `resolvePending forced language uses fallback when exact language is unmapped`() {
        val tmp = File(System.getProperty("java.io.tmpdir"), "pending-forced-language-fallback").canonicalPath
        val project = stubProject(File(tmp))
        every { ProjectLanguageDetector.dominant(project) } returns "javascript"

        val builder =
            OverridesGroupBuilder().apply {
                seedResolutionOverridesForTest(
                    forcedLanguages = mapOf(tmp to "typescript"),
                    languageFallbackAccent = "#73D0FF",
                )
            }

        assertEquals("#73D0FF", builder.resolvePending(project, "#FFCC66"))
        assertEquals(AccentResolver.Source.LANGUAGE_FALLBACK_OVERRIDE, builder.sourcePending(project))
        io.mockk.verify(exactly = 0) { ProjectLanguageDetector.dominant(project) }
    }

    @Test
    fun `resolvePending forced language without mapped accent warms fallback and resolves project fallback`() {
        val tmp = File(System.getProperty("java.io.tmpdir"), "pending-forced-fallback").canonicalPath
        val project = stubProject(File(tmp))
        every { ProjectLanguageDetector.dominant(project) } returns "javascript"
        val noWinnerVerdict = ProjectLanguageVerdict.NoWinner(mapOf("typescript" to 500L, "javascript" to 500L))
        every { ProjectLanguageDetector.verdict(project) } returns noWinnerVerdict
        every { ProjectLanguageDetector.verdict(project, warmCache = true) } returns noWinnerVerdict

        val builder =
            OverridesGroupBuilder().apply {
                seedPendingForTest(
                    languages = listOf(LanguageMapping("javascript", "JavaScript", "#F7DF1E")),
                )
                seedResolutionOverridesForTest(
                    fallbackAccents = mapOf(tmp to "#5CCFE6"),
                    forcedLanguages = mapOf(tmp to "typescript"),
                )
            }

        assertEquals("#5CCFE6", builder.resolvePending(project, "#FFCC66"))
        assertEquals(AccentResolver.Source.PROJECT_FALLBACK, builder.sourcePending(project))
        io.mockk.verify(atLeast = 1) { ProjectLanguageDetector.verdict(project, warmCache = true) }
    }

    @Test
    fun `resolvePending project fallback applies only for no winner`() {
        val coldPath = File(System.getProperty("java.io.tmpdir"), "pending-fallback-cold").canonicalPath
        val coldProject = stubProject(File(coldPath))
        every { ProjectLanguageDetector.dominant(coldProject) } returns null
        every { ProjectLanguageDetector.verdict(coldProject) } returns ProjectLanguageVerdict.Cold
        every { ProjectLanguageDetector.verdict(coldProject, warmCache = true) } returns ProjectLanguageVerdict.Cold

        val noWinnerPath = File(System.getProperty("java.io.tmpdir"), "pending-fallback-no-winner").canonicalPath
        val noWinnerProject = stubProject(File(noWinnerPath))
        every { ProjectLanguageDetector.dominant(noWinnerProject) } returns null
        val noWinnerVerdict = ProjectLanguageVerdict.NoWinner(mapOf("kotlin" to 500L, "java" to 500L))
        every { ProjectLanguageDetector.verdict(noWinnerProject) } returns noWinnerVerdict
        every { ProjectLanguageDetector.verdict(noWinnerProject, warmCache = true) } returns noWinnerVerdict

        val builder =
            OverridesGroupBuilder().apply {
                seedResolutionOverridesForTest(
                    fallbackAccents =
                        mapOf(
                            coldPath to "#5CCFE6",
                            noWinnerPath to "#FFB454",
                        ),
                )
            }

        assertEquals("#FFCC66", builder.resolvePending(coldProject, "#FFCC66"))
        assertEquals(AccentResolver.Source.GLOBAL, builder.sourcePending(coldProject))
        assertEquals("#FFB454", builder.resolvePending(noWinnerProject, "#FFCC66"))
        assertEquals(AccentResolver.Source.PROJECT_FALLBACK, builder.sourcePending(noWinnerProject))
    }

    @Test
    fun `resolvePending falls back to global when no pending entry matches`() {
        val project = stubProject(File(System.getProperty("java.io.tmpdir"), "pending-none"))

        val builder = OverridesGroupBuilder().apply { loadFromState() }

        assertEquals("#FFCC66", builder.resolvePending(project, "#FFCC66"))
        assertEquals(AccentResolver.Source.GLOBAL, builder.sourcePending(project))
    }

    @Test
    fun `resolvePending skips default and disposed projects`() {
        val tmp = File(System.getProperty("java.io.tmpdir"), "pending-default").canonicalPath
        mappingsState.projectAccents[tmp] = "#111111"
        val defaultProject =
            mockk<Project>().apply {
                every { isDefault } returns true
                every { isDisposed } returns false
                every { basePath } returns tmp
                every { name } returns "default"
            }

        val builder = OverridesGroupBuilder().apply { loadFromState() }

        assertEquals("#FFCC66", builder.resolvePending(defaultProject, "#FFCC66"))
        assertEquals(AccentResolver.Source.GLOBAL, builder.sourcePending(defaultProject))
    }

    @Test
    fun `resolvePending matches lowercase language id exactly, not case-insensitively`() {
        // LanguageMapping.init enforces lowercase, ProjectLanguageDetector returns lowercase —
        // case-sensitive equality is the consistent choice across the codebase, mirroring
        // LanguageMappingsTableModel.containsLanguage. Both halves of the invariant matter:
        //  - lowercase detector output matches stored lowercase id (positive)
        //  - non-lowercase detector output does NOT match (negative — locks the contract from
        //    both sides; reintroducing `ignoreCase = true` in resolvePending would silently
        //    pass the positive assertion alone)
        mappingsState.languageAccents["kotlin"] = "#CAFE00"

        val builder = OverridesGroupBuilder().apply { loadFromState() }

        val lowercaseProject = stubProject(File(System.getProperty("java.io.tmpdir"), "case-lower"))
        detectLanguage(lowercaseProject, "kotlin")
        assertEquals("#CAFE00", builder.resolvePending(lowercaseProject, "#FFCC66"))

        val mixedCaseProject = stubProject(File(System.getProperty("java.io.tmpdir"), "case-mixed"))
        detectLanguage(mixedCaseProject, "Kotlin")
        assertEquals(
            "#FFCC66",
            builder.resolvePending(mixedCaseProject, "#FFCC66"),
            "Mixed-case detector output must not match lowercase stored id",
        )
    }

    private fun stubProject(baseDir: File): Project {
        val project = mockk<Project>()
        every { project.isDefault } returns false
        every { project.isDisposed } returns false
        every { project.basePath } returns baseDir.path
        every { project.name } returns baseDir.name
        return project
    }

    private fun detectLanguage(
        project: Project,
        languageId: String,
    ) {
        every { ProjectLanguageDetector.verdict(project, warmCache = true) } returns
            ProjectLanguageVerdict.Detected(languageId, mapOf(languageId to 1_000L))
    }
}
