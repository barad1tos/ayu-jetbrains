package dev.ayuislands.accent

import com.intellij.openapi.project.Project
import dev.ayuislands.licensing.LicenseChecker
import dev.ayuislands.settings.AyuIslandsSettings
import dev.ayuislands.settings.AyuIslandsState
import dev.ayuislands.settings.mappings.AccentMappingsSettings
import dev.ayuislands.settings.mappings.AccentMappingsState
import dev.ayuislands.studio.StudioFixtures
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
import io.mockk.verify
import java.awt.Color
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

class AccentCandidateTest {
    private val mappings = AccentMappingsState()
    private val projectPath = File(System.getProperty("java.io.tmpdir"), "accent-candidate").canonicalPath
    private val project = mockk<Project>()
    private val request: AccentResolutionRequest
        get() =
            AccentResolutionRequest(
                PersistedAccentMappingsView {
                    StudioFixtures.legacyView(AyuIslandsState(), mappings = mappings).mappings
                },
                AccentDetectorLookup.StrictCacheOnlyLookup,
                AccentHexPolicy.STRICT,
            )

    @BeforeTest
    fun setUp() {
        every { project.basePath } returns projectPath
        every { project.name } returns "accent-candidate"
        every { project.isDefault } returns false
        every { project.isDisposed } returns false
        mockkObject(AyuIslandsSettings.Companion, AccentMappingsSettings.Companion)
        every { AyuIslandsSettings.getInstance() } throws IllegalStateException("Unexpected persisted settings read")
        every { AccentMappingsSettings.getInstance() } throws
            IllegalStateException("Unexpected persisted mappings read")
        mockkObject(LicenseChecker, ProjectLanguageDetector)
        every { LicenseChecker.isLicensedOrGrace() } returns true
        every { ProjectLanguageDetector.verdict(any()) } returns ProjectLanguageVerdict.Cold
        every { ProjectLanguageDetector.verdict(any(), true) } throws IllegalStateException("Unexpected cache warmup")
        every { ProjectLanguageDetector.dominant(any()) } throws IllegalStateException("Unexpected language scan")
    }

    @AfterTest
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `candidate global and project colors use explicit inputs without reading persisted settings`() {
        val fallback = AccentFallback.Global("#112233")
        assertEquals(ResolvedAccent(AccentResolver.Source.GLOBAL, "#112233"), resolve(fallback))
        mappings.projectAccents[projectPath] = "#445566"
        assertEquals(ResolvedAccent(AccentResolver.Source.PROJECT_OVERRIDE, "#445566"), resolve(fallback))
        assertEquals("#445566", mappings.projectAccents[projectPath])
        verify(exactly = 0) { ProjectLanguageDetector.verdict(any()) }
    }

    @Test
    fun `candidate forced language and project fallback keep the shared resolution order`() {
        mappings.forcedProjectLanguages[projectPath] = "kotlin"
        mappings.languageAccents["kotlin"] = "#778899"
        mappings.projectFallbackAccents[projectPath] = "#AABBCC"
        every { ProjectLanguageDetector.verdict(project) } returns
            ProjectLanguageVerdict.NoWinner(linkedMapOf("java" to 50L, "kotlin" to 50L))
        val fallback = AccentFallback.Global("#112233")
        assertEquals(ResolvedAccent(AccentResolver.Source.FORCED_LANGUAGE_OVERRIDE, "#778899"), resolve(fallback))
        mappings.languageAccents.clear()
        assertEquals(ResolvedAccent(AccentResolver.Source.PROJECT_FALLBACK, "#AABBCC"), resolve(fallback))
    }

    @Test
    fun `manual candidate bypasses mappings and native colors including invalid stored hex`() {
        mappings.projectAccents[projectPath] = "#445566"
        AccentResolver.withUiColorProviderForTest({ error("Manual accent must not consult native colors") }) {
            val manual = AccentFallback.External(ExternalAccentSource.MANUAL, "#778899")
            assertEquals(ResolvedAccent(AccentResolver.Source.EXTERNAL_ACCENT, "#778899"), resolve(manual))
            val invalid = manual.copy(hex = "unknown")
            assertEquals(AyuVariant.MIRAGE.defaultAccent, resolve(invalid).hex)
            assertEquals(AyuVariant.MIRAGE.defaultAccent, resolve(manual.copy(hex = null)).hex)
        }
    }

    @Test
    fun `automatic external candidate preserves project then native precedence`() {
        val fallback = AccentFallback.External(ExternalAccentSource.AUTOMATIC, "#112233")
        mappings.projectAccents[projectPath] = "#445566"
        AccentResolver.withUiColorProviderForTest({ Color(0x778899) }) {
            assertEquals(ResolvedAccent(AccentResolver.Source.PROJECT_OVERRIDE, "#445566"), resolve(fallback))
            mappings.projectAccents.clear()
            assertEquals(ResolvedAccent(AccentResolver.Source.MATERIAL_THEME, "#778899"), resolve(fallback))
        }
    }

    @Test
    fun `unlicensed candidate does not activate premium mappings or consult detector`() {
        mappings.projectAccents[projectPath] = "#445566"
        mappings.languageAccents["kotlin"] = "#778899"
        every { LicenseChecker.isLicensedOrGrace() } returns false
        val fallback = AccentFallback.Global("#112233")
        assertEquals(ResolvedAccent(AccentResolver.Source.GLOBAL, "#112233"), resolve(fallback))
        verify(exactly = 0) { ProjectLanguageDetector.verdict(any()) }
    }

    private fun resolve(fallback: AccentFallback): ResolvedAccent = AccentResolver.resolve(project, request, fallback)
}
