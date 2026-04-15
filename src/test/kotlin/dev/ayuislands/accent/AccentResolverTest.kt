package dev.ayuislands.accent

import com.intellij.openapi.project.Project
import dev.ayuislands.licensing.LicenseChecker
import dev.ayuislands.settings.AyuIslandsSettings
import dev.ayuislands.settings.mappings.AccentMappingsSettings
import dev.ayuislands.settings.mappings.AccentMappingsState
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Verifies the project > language > global priority chain and short-circuit behavior.
 * Uses real temp directories so `AccentResolver.projectKey` canonicalization runs end-to-end.
 */
class AccentResolverTest {
    private lateinit var mappingsState: AccentMappingsState
    private val globalMirageAccent = "#FFCC66"

    @BeforeTest
    fun setUp() {
        mappingsState = AccentMappingsState()

        val globalSettings = mockk<AyuIslandsSettings>()
        every { globalSettings.getAccentForVariant(AyuVariant.MIRAGE) } returns globalMirageAccent
        every { globalSettings.getAccentForVariant(AyuVariant.DARK) } returns "#E6B450"
        every { globalSettings.getAccentForVariant(AyuVariant.LIGHT) } returns "#F29718"
        mockkObject(AyuIslandsSettings.Companion)
        every { AyuIslandsSettings.getInstance() } returns globalSettings

        val mappingsSettings = mockk<AccentMappingsSettings>()
        every { mappingsSettings.state } returns mappingsState
        mockkObject(AccentMappingsSettings.Companion)
        every { AccentMappingsSettings.getInstance() } returns mappingsSettings

        mockkObject(ProjectLanguageDetector)
        every { ProjectLanguageDetector.dominant(any()) } returns null

        // Overrides are premium: default the license gate to licensed so the resolution
        // logic under test runs. Individual tests override to verify the unlicensed path.
        mockkObject(LicenseChecker)
        every { LicenseChecker.isLicensedOrGrace() } returns true
    }

    @AfterTest
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `resolve returns global when project is null`() {
        assertEquals(globalMirageAccent, AccentResolver.resolve(null, AyuVariant.MIRAGE))
    }

    @Test
    fun `resolve returns global when project has no matching override and no language mapping`() {
        val project = stubProject(File(System.getProperty("java.io.tmpdir"), "no-map-proj"))

        assertEquals(globalMirageAccent, AccentResolver.resolve(project, AyuVariant.MIRAGE))
    }

    @Test
    fun `resolve prefers project override over global`() {
        val tmp = File(System.getProperty("java.io.tmpdir"), "proj-override").canonicalPath
        mappingsState.projectAccents[tmp] = "#123456"

        val project = stubProject(File(tmp))
        assertEquals("#123456", AccentResolver.resolve(project, AyuVariant.MIRAGE))
    }

    @Test
    fun `resolve prefers language override over global when no project override`() {
        mappingsState.languageAccents["kotlin"] = "#ABCDEF"
        val project = stubProject(File(System.getProperty("java.io.tmpdir"), "kotlin-proj"))
        every { ProjectLanguageDetector.dominant(project) } returns "kotlin"

        assertEquals("#ABCDEF", AccentResolver.resolve(project, AyuVariant.MIRAGE))
    }

    @Test
    fun `resolve prefers project override over language override`() {
        val tmp = File(System.getProperty("java.io.tmpdir"), "both-overrides").canonicalPath
        mappingsState.projectAccents[tmp] = "#111111"
        mappingsState.languageAccents["kotlin"] = "#222222"

        val project = stubProject(File(tmp))
        every { ProjectLanguageDetector.dominant(project) } returns "kotlin"

        assertEquals("#111111", AccentResolver.resolve(project, AyuVariant.MIRAGE))
    }

    @Test
    fun `resolve skips language detector when language map is empty`() {
        // No language overrides configured — detector must not be consulted at all.
        val project = stubProject(File(System.getProperty("java.io.tmpdir"), "skip-detector"))

        assertEquals(globalMirageAccent, AccentResolver.resolve(project, AyuVariant.MIRAGE))
        io.mockk.verify(exactly = 0) { ProjectLanguageDetector.dominant(any()) }
    }

    @Test
    fun `source reports PROJECT_OVERRIDE when project mapped`() {
        val tmp = File(System.getProperty("java.io.tmpdir"), "src-proj").canonicalPath
        mappingsState.projectAccents[tmp] = "#111111"
        val project = stubProject(File(tmp))

        assertEquals(AccentResolver.Source.PROJECT_OVERRIDE, AccentResolver.source(project))
    }

    @Test
    fun `source reports LANGUAGE_OVERRIDE when only language mapped`() {
        mappingsState.languageAccents["python"] = "#222222"
        val project = stubProject(File(System.getProperty("java.io.tmpdir"), "src-lang"))
        every { ProjectLanguageDetector.dominant(project) } returns "python"

        assertEquals(AccentResolver.Source.LANGUAGE_OVERRIDE, AccentResolver.source(project))
    }

    @Test
    fun `source reports GLOBAL when nothing matches`() {
        val project = stubProject(File(System.getProperty("java.io.tmpdir"), "src-global"))
        assertEquals(AccentResolver.Source.GLOBAL, AccentResolver.source(project))
    }

    @Test
    fun `projectKey returns canonical path for valid project`() {
        val dir = File(System.getProperty("java.io.tmpdir"), "canonical")
        val project = stubProject(dir)

        assertEquals(dir.canonicalPath, AccentResolver.projectKey(project))
    }

    @Test
    fun `resolve returns global when project is default`() {
        val tmp = File(System.getProperty("java.io.tmpdir"), "default-proj").canonicalPath
        mappingsState.projectAccents[tmp] = "#111111"

        val project = mockk<Project>()
        every { project.isDefault } returns true
        every { project.isDisposed } returns false
        every { project.basePath } returns tmp
        every { project.name } returns "default-proj"

        assertEquals(globalMirageAccent, AccentResolver.resolve(project, AyuVariant.MIRAGE))
    }

    @Test
    fun `resolve returns global when project is disposed`() {
        val tmp = File(System.getProperty("java.io.tmpdir"), "disposed-proj").canonicalPath
        mappingsState.projectAccents[tmp] = "#111111"

        val project = mockk<Project>()
        every { project.isDefault } returns false
        every { project.isDisposed } returns true
        every { project.basePath } returns tmp
        every { project.name } returns "disposed-proj"

        assertEquals(globalMirageAccent, AccentResolver.resolve(project, AyuVariant.MIRAGE))
    }

    @Test
    fun `projectKey returns null when basePath is null`() {
        val project = mockk<Project>()
        every { project.basePath } returns null
        every { project.isDefault } returns false
        every { project.isDisposed } returns false
        every { project.name } returns "no-path"

        assertNull(AccentResolver.projectKey(project))
        // And resolve should still fall through to global without throwing.
        assertEquals(globalMirageAccent, AccentResolver.resolve(project, AyuVariant.MIRAGE))
    }

    @Test
    fun `resolve returns global when unlicensed even with stored project override`() {
        // Guards against trial expiry and manual XML import leaking premium behavior.
        val tmp = File(System.getProperty("java.io.tmpdir"), "unlicensed-proj").canonicalPath
        mappingsState.projectAccents[tmp] = "#111111"
        every { LicenseChecker.isLicensedOrGrace() } returns false

        val project = stubProject(File(tmp))
        assertEquals(globalMirageAccent, AccentResolver.resolve(project, AyuVariant.MIRAGE))
    }

    @Test
    fun `resolve returns global when unlicensed even with stored language override`() {
        mappingsState.languageAccents["kotlin"] = "#222222"
        every { LicenseChecker.isLicensedOrGrace() } returns false

        val project = stubProject(File(System.getProperty("java.io.tmpdir"), "unlicensed-lang"))
        every { ProjectLanguageDetector.dominant(project) } returns "kotlin"

        assertEquals(globalMirageAccent, AccentResolver.resolve(project, AyuVariant.MIRAGE))
    }

    @Test
    fun `source reports GLOBAL when unlicensed even with stored overrides`() {
        // UI "Currently active: ..." must not claim a project/language override is live
        // when the resolver itself would skip it because the license check fails.
        val tmp = File(System.getProperty("java.io.tmpdir"), "unlicensed-src").canonicalPath
        mappingsState.projectAccents[tmp] = "#111111"
        mappingsState.languageAccents["kotlin"] = "#222222"
        every { LicenseChecker.isLicensedOrGrace() } returns false

        val project = stubProject(File(tmp))
        every { ProjectLanguageDetector.dominant(project) } returns "kotlin"

        assertEquals(AccentResolver.Source.GLOBAL, AccentResolver.source(project))
    }

    @Test
    fun `resolve falls through to global when dominant language is not mapped`() {
        // Row 6 of the truth table: LM=Y, D=Y, L=N → global wins.
        mappingsState.languageAccents["kotlin"] = "#ABCDEF"
        val project = stubProject(File(System.getProperty("java.io.tmpdir"), "python-only"))
        every { ProjectLanguageDetector.dominant(project) } returns "python"

        assertEquals(globalMirageAccent, AccentResolver.resolve(project, AyuVariant.MIRAGE))
        assertEquals(AccentResolver.Source.GLOBAL, AccentResolver.source(project))
    }

    @Test
    fun `source returns GLOBAL when project is null even with stored mappings`() {
        // Mirrors the null-project guard tested in `resolve returns global when project is null`
        // for the source() projection. Together they lock the null-project branch of
        // findOverride from both sides — resolve and source must agree.
        val tmp = File(System.getProperty("java.io.tmpdir"), "null-src-proj").canonicalPath
        mappingsState.projectAccents[tmp] = "#FACADE"

        assertEquals(AccentResolver.Source.GLOBAL, AccentResolver.source(null))
    }

    @Test
    fun `projectKey logs once per project on canonicalization failure then dedups`() {
        // Hot-path callers (focus swap, rotation tick) must not flood idea.log when a
        // project's basePath is unresolvable. The dedup set ages out with project disposal.
        // Capture warns via LoggedErrorProcessor so the dedup contract is assertable — the
        // previous test returned null from all three calls even if the dedup was silently
        // broken (warn fired 3 times instead of 1). Now the test FAILS if the dedup goes
        // away. Path with embedded NUL causes File.canonicalPath to throw on most JVMs.
        val project = mockk<Project>()
        every { project.basePath } returns "/tmp/path-with-nul-\u0000-byte/project"
        every { project.isDefault } returns false
        every { project.isDisposed } returns false
        every { project.name } returns "weird-project"

        val capturedWarns = mutableListOf<String>()
        val processor =
            object : com.intellij.testFramework.LoggedErrorProcessor() {
                override fun processWarn(
                    category: String,
                    message: String,
                    throwable: Throwable?,
                ): Boolean {
                    capturedWarns += message
                    return false
                }
            }

        com.intellij.testFramework.LoggedErrorProcessor.executeWith<RuntimeException>(processor) {
            repeat(3) { assertNull(AccentResolver.projectKey(project)) }
        }

        assertEquals(
            1,
            capturedWarns.count { it.contains("canonicalize basePath") },
            "Dedup must collapse 3 calls with the same project into exactly 1 warn; " +
                "got: $capturedWarns",
        )
    }

    @Test
    fun `projectKey degrades to null when basePath access throws AlreadyDisposedException`() {
        // Race condition: between the dispose check in findOverride and the basePath read in
        // projectKey, the project finishes disposing on another thread. basePath access then
        // throws — we must catch it and return null instead of escalating out of the resolver.
        val project = mockk<Project>()
        every { project.basePath } throws IllegalStateException("Already disposed: Project")
        every { project.isDefault } returns false
        every { project.isDisposed } returns false

        assertNull(AccentResolver.projectKey(project))
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
