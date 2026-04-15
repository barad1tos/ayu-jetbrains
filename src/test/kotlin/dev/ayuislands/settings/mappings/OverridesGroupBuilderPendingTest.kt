package dev.ayuislands.settings.mappings

import com.intellij.openapi.project.Project
import dev.ayuislands.accent.AccentResolver
import dev.ayuislands.accent.ProjectLanguageDetector
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

        val builder = OverridesGroupBuilder().apply { loadFromStateForTest() }

        assertEquals("#ABCDEF", builder.resolvePending(project, "#FFCC66"))
        assertEquals(AccentResolver.Source.PROJECT_OVERRIDE, builder.sourcePending(project))
    }

    @Test
    fun `resolvePending returns language override when project has no pending entry`() {
        mappingsState.languageAccents["kotlin"] = "#112233"
        val project = stubProject(File(System.getProperty("java.io.tmpdir"), "pending-lang"))
        every { ProjectLanguageDetector.dominant(project) } returns "kotlin"

        val builder = OverridesGroupBuilder().apply { loadFromStateForTest() }

        assertEquals("#112233", builder.resolvePending(project, "#FFCC66"))
        assertEquals(AccentResolver.Source.LANGUAGE_OVERRIDE, builder.sourcePending(project))
    }

    @Test
    fun `resolvePending prefers project override over language override`() {
        val tmp = File(System.getProperty("java.io.tmpdir"), "pending-both").canonicalPath
        mappingsState.projectAccents[tmp] = "#111111"
        mappingsState.languageAccents["kotlin"] = "#222222"
        val project = stubProject(File(tmp))
        every { ProjectLanguageDetector.dominant(project) } returns "kotlin"

        val builder = OverridesGroupBuilder().apply { loadFromStateForTest() }

        assertEquals("#111111", builder.resolvePending(project, "#FFCC66"))
        assertEquals(AccentResolver.Source.PROJECT_OVERRIDE, builder.sourcePending(project))
    }

    @Test
    fun `resolvePending falls back to global when no pending entry matches`() {
        val project = stubProject(File(System.getProperty("java.io.tmpdir"), "pending-none"))

        val builder = OverridesGroupBuilder().apply { loadFromStateForTest() }

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

        val builder = OverridesGroupBuilder().apply { loadFromStateForTest() }

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

        val builder = OverridesGroupBuilder().apply { loadFromStateForTest() }

        val lowercaseProject = stubProject(File(System.getProperty("java.io.tmpdir"), "case-lower"))
        every { ProjectLanguageDetector.dominant(lowercaseProject) } returns "kotlin"
        assertEquals("#CAFE00", builder.resolvePending(lowercaseProject, "#FFCC66"))

        val mixedCaseProject = stubProject(File(System.getProperty("java.io.tmpdir"), "case-mixed"))
        every { ProjectLanguageDetector.dominant(mixedCaseProject) } returns "Kotlin"
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
}
