package dev.ayuislands.accent

import com.intellij.openapi.project.Project
import dev.ayuislands.licensing.LicenseChecker
import dev.ayuislands.settings.AyuIslandsSettings
import dev.ayuislands.settings.AyuIslandsState
import dev.ayuislands.settings.mappings.AccentMappingsSettings
import dev.ayuislands.settings.mappings.AccentMappingsState
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
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Verifies [AccentResolver.resolveChain] decision tracing: the ordered
 * [AccentResolutionStep] list, the [AccentResolutionChain.winner], and the
 * [AccentResolutionChain.verdict] (when language detection is consulted).
 *
 * Each test locks down one path through the priority chain to guard against
 * regressions in the step-construction, short-circuit, and license-gate logic.
 *
 * ### Step-count notes
 *
 * The GLOBAL winner is appended to [steps] only when no [StepOutcome.WON]
 * step exists among the overrides (i.e. GLOBAL is the actual winner). So the
 * step count varies:
 * - No active project / unlicensed: 4 premium steps + 1 GLOBAL = **5**
 * - Global wins without language mappings: 3 premium + 1 GLOBAL = **4**
 * - Global wins with language mappings: 4 premium + 1 GLOBAL = **5**
 * - Override wins (short-circuit): 1-3 steps, no GLOBAL appended
 */
class AccentResolverChainTest {
    private lateinit var mappingsState: AccentMappingsState
    private val globalMirageAccent = "#FFCC66"
    private val stubsDir = File(System.getProperty("java.io.tmpdir"), "chain-test")

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
        every { ProjectLanguageDetector.verdict(any()) } returns ProjectLanguageVerdict.Cold
        every { ProjectLanguageDetector.verdict(any(), any<Boolean>()) } returns ProjectLanguageVerdict.Cold

        // Default: licensed. Individual tests override to verify the unlicensed path.
        mockkObject(LicenseChecker)
        every { LicenseChecker.isLicensedOrGrace() } returns true

        AccentResolver.resetWarnGatesForTest()
        AccentResolver.resetUiColorProviderForTest()
    }

    @AfterTest
    fun tearDown() {
        unmockkAll()
    }

    // Null, default, and disposed project

    @Test
    fun `resolveChain returns global winner with NOT_APPLICABLE premium steps when project is null`() {
        val chain = AccentResolver.resolveChain(null, AyuVariant.MIRAGE)

        // 4 NOT_APPLICABLE premium steps + 1 GLOBAL step
        assertEquals(5, chain.steps.size)

        val premiumSteps = chain.steps.take(4)
        premiumSteps.forEach { step ->
            assertEquals(StepOutcome.NOT_APPLICABLE, step.outcome, "${step.source} should be NOT_APPLICABLE")
            assertEquals("No project open", step.detail)
            assertNull(step.hex)
        }
        val sourceSet = premiumSteps.map { it.source }.toSet()
        assertEquals(
            setOf(
                AccentResolver.Source.PROJECT_OVERRIDE,
                AccentResolver.Source.FORCED_LANGUAGE_OVERRIDE,
                AccentResolver.Source.LANGUAGE_OVERRIDE,
                AccentResolver.Source.PROJECT_FALLBACK,
            ),
            sourceSet,
        )

        assertEquals(AccentResolver.Source.GLOBAL, chain.winner.source)
        assertEquals(globalMirageAccent, chain.winner.hex)
        assertEquals(StepOutcome.WON, chain.winner.outcome)
        assertNull(chain.verdict, "Verdict should be null when no project")
    }

    @Test
    fun `resolveChain returns global winner when project is default`() {
        val project = mockk<Project>()
        every { project.isDefault } returns true
        every { project.isDisposed } returns false
        every { project.basePath } returns File(stubsDir, "default-proj").path
        every { project.name } returns "default-proj"

        val chain = AccentResolver.resolveChain(project, AyuVariant.MIRAGE)

        assertEquals(5, chain.steps.size)
        chain.steps.take(4).forEach { step ->
            assertEquals(StepOutcome.NOT_APPLICABLE, step.outcome, "${step.source} should be NOT_APPLICABLE")
        }
        assertEquals(AccentResolver.Source.GLOBAL, chain.winner.source)
        assertNull(chain.verdict)
    }

    @Test
    fun `resolveChain returns global winner when project is disposed`() {
        val project = mockk<Project>()
        every { project.isDefault } returns false
        every { project.isDisposed } returns true
        every { project.basePath } returns File(stubsDir, "disposed-proj").path
        every { project.name } returns "disposed-proj"

        val chain = AccentResolver.resolveChain(project, AyuVariant.MIRAGE)

        assertEquals(5, chain.steps.size)
        chain.steps.take(4).forEach { step ->
            assertEquals(StepOutcome.NOT_APPLICABLE, step.outcome, "${step.source} should be NOT_APPLICABLE")
        }
        assertEquals(AccentResolver.Source.GLOBAL, chain.winner.source)
        assertNull(chain.verdict)
    }

    // Unlicensed

    @Test
    fun `resolveChain returns global winner when unlicensed`() {
        every { LicenseChecker.isLicensedOrGrace() } returns false

        val chain = AccentResolver.resolveChain(null, AyuVariant.MIRAGE)

        // 4 LICENSE_BLOCKED premium steps + 1 GLOBAL step
        assertEquals(5, chain.steps.size)

        chain.steps.take(4).forEach { step ->
            assertEquals(StepOutcome.LICENSE_BLOCKED, step.outcome, "${step.source} should be LICENSE_BLOCKED")
            assertEquals("Premium feature — license required", step.detail)
            assertNull(step.hex)
        }

        assertEquals(AccentResolver.Source.GLOBAL, chain.winner.source)
        assertEquals(globalMirageAccent, chain.winner.hex)
        assertEquals(StepOutcome.WON, chain.winner.outcome)
        assertNull(chain.verdict, "Verdict should be null when unlicensed")
    }

    @Test
    fun `resolveChain returns global winner when unlicensed even with stored mappings`() {
        every { LicenseChecker.isLicensedOrGrace() } returns false
        val tmp = File(stubsDir, "unlicensed-proj").canonicalPath
        mappingsState.projectAccents[tmp] = "#111111"
        mappingsState.languageAccents["kotlin"] = "#222222"
        val project = stubProject(File(tmp))
        every { ProjectLanguageDetector.dominant(project) } returns "kotlin"

        val chain = AccentResolver.resolveChain(project, AyuVariant.MIRAGE)

        assertEquals(5, chain.steps.size)
        chain.steps.take(4).forEach { step ->
            assertEquals(StepOutcome.LICENSE_BLOCKED, step.outcome, "${step.source} should be LICENSE_BLOCKED")
        }
        assertEquals(AccentResolver.Source.GLOBAL, chain.winner.source)
        assertNull(chain.verdict)
    }

    // Project override wins

    @Test
    fun `resolveChain project override wins and short-circuits remaining steps`() {
        val tmp = File(stubsDir, "proj-override").canonicalPath
        mappingsState.projectAccents[tmp] = "#123456"
        val project = stubProject(File(tmp))

        val chain = AccentResolver.resolveChain(project, AyuVariant.MIRAGE)

        assertEquals(1, chain.steps.size, "Only PROJECT_OVERRIDE step should exist (short-circuit)")

        val step = chain.steps.single()
        assertEquals(AccentResolver.Source.PROJECT_OVERRIDE, step.source)
        assertEquals("#123456", step.hex)
        assertEquals(StepOutcome.WON, step.outcome)
        assertEquals("Pinned accent for this project", step.detail)

        assertEquals(step, chain.winner)
        assertNull(chain.verdict, "Verdict should be null after early short-circuit")
    }

    @Test
    fun `resolveChain project override with invalid hex falls through and global wins`() {
        val tmp = File(stubsDir, "proj-invalid-hex").canonicalPath
        mappingsState.projectAccents[tmp] = "not-a-hex"
        val project = stubProject(File(tmp))

        val chain = AccentResolver.resolveChain(project, AyuVariant.MIRAGE)

        // PROJECT_OVERRIDE step has NOT_SET (invalid hex), then GLOBAL wins
        val projectStep = chain.steps.first()
        assertEquals(AccentResolver.Source.PROJECT_OVERRIDE, projectStep.source)
        assertEquals(StepOutcome.NOT_SET, projectStep.outcome)
        assertNull(projectStep.hex)
        assertEquals("Invalid hex in project override", projectStep.detail)

        assertEquals(AccentResolver.Source.GLOBAL, chain.winner.source)
        assertEquals(globalMirageAccent, chain.winner.hex)
    }

    // Forced language override wins

    @Test
    fun `resolveChain forced language override wins`() {
        val tmp = File(stubsDir, "forced-lang").canonicalPath
        mappingsState.forcedProjectLanguages[tmp] = "typescript"
        mappingsState.languageAccents["typescript"] = "#3178C6"
        val project = stubProject(File(tmp))

        val chain = AccentResolver.resolveChain(project, AyuVariant.MIRAGE)

        assertEquals(2, chain.steps.size, "Should short-circuit after forced language wins")

        // Step 0: PROJECT_OVERRIDE not set
        assertEquals(AccentResolver.Source.PROJECT_OVERRIDE, chain.steps[0].source)
        assertEquals(StepOutcome.NOT_SET, chain.steps[0].outcome)

        // Step 1: FORCED_LANGUAGE_OVERRIDE wins
        val forcedStep = chain.steps[1]
        assertEquals(AccentResolver.Source.FORCED_LANGUAGE_OVERRIDE, forcedStep.source)
        assertEquals("#3178C6", forcedStep.hex)
        assertEquals(StepOutcome.WON, forcedStep.outcome)
        assertNotNull(forcedStep.detail)
        assertEquals(forcedStep, chain.winner)

        assertNull(chain.verdict)
    }

    @Test
    fun `resolveChain forced language override with missing accent mapping falls through to global`() {
        val tmp = File(stubsDir, "forced-no-map").canonicalPath
        mappingsState.forcedProjectLanguages[tmp] = "typescript"
        // No languageAccent for "typescript"
        val project = stubProject(File(tmp))

        val chain = AccentResolver.resolveChain(project, AyuVariant.MIRAGE)

        // PROJECT_OVERRIDE NOT_SET, FORCED_LANGUAGE_OVERRIDE NO_MAPPING,
        // LANGUAGE_OVERRIDE NOT_APPLICABLE, then GLOBAL wins
        assertEquals(4, chain.steps.size)

        assertEquals(StepOutcome.NOT_SET, chain.steps[0].outcome)
        assertEquals(StepOutcome.NO_MAPPING, chain.steps[1].outcome)
        assertEquals(StepOutcome.NOT_APPLICABLE, chain.steps[2].outcome)

        assertEquals(AccentResolver.Source.GLOBAL, chain.winner.source)
        assertNull(chain.verdict)
    }

    @Test
    fun `resolveChain forced language missing accent warms cache before project fallback`() {
        val tmp = File(stubsDir, "forced-no-map-project-fallback").canonicalPath
        mappingsState.forcedProjectLanguages[tmp] = "typescript"
        mappingsState.projectFallbackAccents[tmp] = "#5CCFE6"
        val project = stubProject(File(tmp))
        val noWinnerVerdict =
            ProjectLanguageVerdict.NoWinner(
                mapOf("typescript" to 500L, "javascript" to 500L),
            )
        every { ProjectLanguageDetector.verdict(project) } returns noWinnerVerdict
        every { ProjectLanguageDetector.verdict(project, warmCache = true) } returns noWinnerVerdict

        val chain = AccentResolver.resolveChain(project, AyuVariant.MIRAGE)

        assertEquals(AccentResolver.Source.PROJECT_FALLBACK, chain.winner.source)
        assertEquals("#5CCFE6", chain.winner.hex)
        assertEquals(noWinnerVerdict, chain.verdict)
        verify(atLeast = 1) { ProjectLanguageDetector.verdict(project, warmCache = true) }
    }

    @Test
    fun `resolveChain forced language uses language fallback when exact mapping is missing`() {
        val tmp = File(stubsDir, "forced-language-fallback").canonicalPath
        mappingsState.forcedProjectLanguages[tmp] = "typescript"
        mappingsState.languageFallbackAccent = "#73D0FF"
        val project = stubProject(File(tmp))

        val chain = AccentResolver.resolveChain(project, AyuVariant.MIRAGE)

        assertEquals("#73D0FF", AccentResolver.resolve(project, AyuVariant.MIRAGE))
        assertEquals(AccentResolver.Source.LANGUAGE_FALLBACK_OVERRIDE, AccentResolver.source(project))
        assertTrue(
            chain.steps.any {
                it.source == AccentResolver.Source.FORCED_LANGUAGE_OVERRIDE &&
                    it.outcome == StepOutcome.NO_MAPPING
            },
            "Chain should keep the missing forced-language mapping diagnostic",
        )
        assertEquals(AccentResolver.Source.LANGUAGE_FALLBACK_OVERRIDE, chain.winner.source)
        assertEquals("#73D0FF", chain.winner.hex)
        assertEquals(StepOutcome.WON, chain.winner.outcome)
        assertNull(chain.verdict)
    }

    @Test
    fun `resolveChain forced language override short-circuits before language override step`() {
        val tmp = File(stubsDir, "forced-skip-lang").canonicalPath
        mappingsState.forcedProjectLanguages[tmp] = "typescript"
        mappingsState.languageAccents["typescript"] = "#3178C6"
        val project = stubProject(File(tmp))
        every { ProjectLanguageDetector.dominant(any()) } returns "javascript"

        val chain = AccentResolver.resolveChain(project, AyuVariant.MIRAGE)

        // Only 2 steps — language-override step was never collected (short-circuit)
        assertEquals(2, chain.steps.size)
        assertEquals(AccentResolver.Source.PROJECT_OVERRIDE, chain.steps[0].source)
        assertEquals(AccentResolver.Source.FORCED_LANGUAGE_OVERRIDE, chain.steps[1].source)
        assertEquals(StepOutcome.WON, chain.steps[1].outcome)
    }

    // Language override wins

    @Test
    fun `resolveChain language override wins with verdict`() {
        mappingsState.languageAccents["kotlin"] = "#ABCDEF"
        val project = stubProject(File(stubsDir, "lang-override"))

        every { ProjectLanguageDetector.dominant(project) } returns "kotlin"
        val verdict =
            ProjectLanguageVerdict.Detected(
                languageId = "kotlin",
                weights = mapOf("kotlin" to 800L, "java" to 200L),
            )
        every { ProjectLanguageDetector.verdict(project) } returns verdict

        val chain = AccentResolver.resolveChain(project, AyuVariant.MIRAGE)

        assertEquals(3, chain.steps.size, "Should short-circuit after language override wins")

        // PROJECT_OVERRIDE NOT_SET
        assertEquals(StepOutcome.NOT_SET, chain.steps[0].outcome)
        // FORCED_LANGUAGE_OVERRIDE NOT_SET
        assertEquals(StepOutcome.NOT_SET, chain.steps[1].outcome)
        // LANGUAGE_OVERRIDE WON
        val langStep = chain.steps[2]
        assertEquals(AccentResolver.Source.LANGUAGE_OVERRIDE, langStep.source)
        assertEquals("#ABCDEF", langStep.hex)
        assertEquals(StepOutcome.WON, langStep.outcome)
        assertEquals("Detected kotlin 80%, java 20%", langStep.detail)
        assertEquals(langStep, chain.winner)

        assertNotNull(chain.verdict, "Verdict should be present when language detection consulted")
        assertEquals(verdict, chain.verdict)
    }

    @Test
    fun `resolveChain language override with NO_MAPPING when language not in accent map falls through to global`() {
        mappingsState.languageAccents["kotlin"] = "#ABCDEF"
        val project = stubProject(File(stubsDir, "lang-no-map"))
        every { ProjectLanguageDetector.dominant(project) } returns "python"
        every { ProjectLanguageDetector.verdict(project) } returns
            ProjectLanguageVerdict.Detected("python", mapOf("python" to 1_000L))

        val chain = AccentResolver.resolveChain(project, AyuVariant.MIRAGE)

        // 4 premium steps + 1 GLOBAL = 5
        assertEquals(5, chain.steps.size)

        val langStep = chain.steps[2]
        assertEquals(AccentResolver.Source.LANGUAGE_OVERRIDE, langStep.source)
        assertEquals(StepOutcome.NO_MAPPING, langStep.outcome)
        assertNull(langStep.hex)
        assertTrue(langStep.detail.contains("python"))

        // Falls through to GLOBAL
        assertEquals(AccentResolver.Source.GLOBAL, chain.winner.source)
    }

    @Test
    fun `resolveChain language fallback wins when detected language has no exact mapping`() {
        mappingsState.languageFallbackAccent = "#73D0FF"
        val project = stubProject(File(stubsDir, "detected-language-fallback"))
        every { ProjectLanguageDetector.dominant(project) } returns "python"
        val verdict = ProjectLanguageVerdict.Detected("python", mapOf("python" to 1_000L))
        every { ProjectLanguageDetector.verdict(project) } returns verdict

        val chain = AccentResolver.resolveChain(project, AyuVariant.MIRAGE)

        assertEquals("#73D0FF", AccentResolver.resolve(project, AyuVariant.MIRAGE))
        assertEquals(AccentResolver.Source.LANGUAGE_FALLBACK_OVERRIDE, AccentResolver.source(project))
        assertTrue(
            chain.steps.any {
                it.source == AccentResolver.Source.LANGUAGE_OVERRIDE &&
                    it.outcome == StepOutcome.NO_MAPPING
            },
            "Chain should explain that the detected language has no exact mapping",
        )
        assertEquals(AccentResolver.Source.LANGUAGE_FALLBACK_OVERRIDE, chain.winner.source)
        assertEquals("#73D0FF", chain.winner.hex)
        assertEquals(verdict, chain.verdict)
    }

    @Test
    fun `resolveChain language override shows Cold verdict when detection not yet run`() {
        mappingsState.languageAccents["kotlin"] = "#ABCDEF"
        val project = stubProject(File(stubsDir, "lang-cold"))
        every { ProjectLanguageDetector.dominant(project) } returns null
        every { ProjectLanguageDetector.verdict(project) } returns ProjectLanguageVerdict.Cold

        val chain = AccentResolver.resolveChain(project, AyuVariant.MIRAGE)

        assertEquals(5, chain.steps.size)

        val langStep = chain.steps[2]
        assertEquals(AccentResolver.Source.LANGUAGE_OVERRIDE, langStep.source)
        assertEquals(StepOutcome.NOT_SET, langStep.outcome)
        assertEquals("Detection pending", langStep.detail)

        assertEquals(ProjectLanguageVerdict.Cold, chain.verdict)
        assertEquals(AccentResolver.Source.GLOBAL, chain.winner.source)
    }

    // Project fallback wins

    @Test
    fun `resolveChain project fallback wins when verdict is NoWinner`() {
        val tmp = File(stubsDir, "fallback-no-winner").canonicalPath
        mappingsState.languageAccents["kotlin"] = "#ABCDEF"
        mappingsState.projectFallbackAccents[tmp] = "#5CCFE6"
        val project = stubProject(File(tmp))

        every { ProjectLanguageDetector.dominant(project) } returns null
        val noWinnerVerdict =
            ProjectLanguageVerdict.NoWinner(
                mapOf("typescript" to 500L, "javascript" to 500L),
            )
        every { ProjectLanguageDetector.verdict(project) } returns noWinnerVerdict
        every { ProjectLanguageDetector.verdict(project, warmCache = true) } returns noWinnerVerdict

        val chain = AccentResolver.resolveChain(project, AyuVariant.MIRAGE)

        assertEquals(4, chain.steps.size)

        // PROJECT_OVERRIDE NOT_SET
        assertEquals(StepOutcome.NOT_SET, chain.steps[0].outcome)
        // FORCED_LANGUAGE_OVERRIDE NOT_SET
        assertEquals(StepOutcome.NOT_SET, chain.steps[1].outcome)
        // LANGUAGE_OVERRIDE NOT_DOMINANT
        assertEquals(StepOutcome.NOT_DOMINANT, chain.steps[2].outcome)
        // PROJECT_FALLBACK WON
        val fallbackStep = chain.steps[3]
        assertEquals(AccentResolver.Source.PROJECT_FALLBACK, fallbackStep.source)
        assertEquals("#5CCFE6", fallbackStep.hex)
        assertEquals(StepOutcome.WON, fallbackStep.outcome)
        assertEquals(fallbackStep, chain.winner)

        assertEquals(noWinnerVerdict, chain.verdict)
    }

    @Test
    fun `resolveChain project fallback wins when no language accents are configured`() {
        val tmp = File(stubsDir, "fallback-no-language-accents").canonicalPath
        mappingsState.projectFallbackAccents[tmp] = "#5CCFE6"
        val project = stubProject(File(tmp))

        every { ProjectLanguageDetector.dominant(project) } returns null
        val noWinnerVerdict =
            ProjectLanguageVerdict.NoWinner(
                mapOf("typescript" to 500L, "javascript" to 500L),
            )
        every { ProjectLanguageDetector.verdict(project) } returns noWinnerVerdict
        every { ProjectLanguageDetector.verdict(project, warmCache = true) } returns noWinnerVerdict

        val chain = AccentResolver.resolveChain(project, AyuVariant.MIRAGE)

        assertEquals("#5CCFE6", AccentResolver.resolve(project, AyuVariant.MIRAGE))
        assertEquals(AccentResolver.Source.PROJECT_FALLBACK, AccentResolver.source(project))
        assertEquals(AccentResolver.Source.PROJECT_FALLBACK, chain.winner.source)
        assertEquals("#5CCFE6", chain.winner.hex)
        assertEquals(StepOutcome.WON, chain.winner.outcome)
        assertEquals(noWinnerVerdict, chain.verdict)
    }

    @Test
    fun `resolveChain project fallback does not apply for Cold verdict`() {
        val tmp = File(stubsDir, "fallback-cold").canonicalPath
        mappingsState.projectFallbackAccents[tmp] = "#5CCFE6"
        mappingsState.languageAccents["kotlin"] = "#ABCDEF"
        val project = stubProject(File(tmp))

        every { ProjectLanguageDetector.verdict(project) } returns ProjectLanguageVerdict.Cold

        val chain = AccentResolver.resolveChain(project, AyuVariant.MIRAGE)

        // 4 premium steps + 1 GLOBAL = 5
        assertEquals(5, chain.steps.size)

        val fallbackStep = chain.steps[3]
        assertEquals(AccentResolver.Source.PROJECT_FALLBACK, fallbackStep.source)
        assertEquals(StepOutcome.NOT_APPLICABLE, fallbackStep.outcome)
        assertEquals("Fallback only applies when no dominant language", fallbackStep.detail)
        assertNull(fallbackStep.hex)

        assertEquals(AccentResolver.Source.GLOBAL, chain.winner.source)
    }

    @Test
    fun `resolveChain project fallback does not apply for Detected verdict when language has accent`() {
        val tmp = File(stubsDir, "fallback-detected").canonicalPath
        mappingsState.projectFallbackAccents[tmp] = "#5CCFE6"
        mappingsState.languageAccents["kotlin"] = "#ABCDEF"
        val project = stubProject(File(tmp))

        every { ProjectLanguageDetector.dominant(project) } returns "kotlin"
        every { ProjectLanguageDetector.verdict(project) } returns
            ProjectLanguageVerdict.Detected("kotlin", mapOf("kotlin" to 1_000L))

        val chain = AccentResolver.resolveChain(project, AyuVariant.MIRAGE)

        // Language wins, short-circuits at step 2
        assertEquals(3, chain.steps.size)
        val langStep = chain.steps[2]
        assertEquals(StepOutcome.WON, langStep.outcome)
        assertEquals(langStep, chain.winner)
    }

    @Test
    fun `resolveChain project fallback with invalid hex falls through to global`() {
        val tmp = File(stubsDir, "fallback-invalid").canonicalPath
        mappingsState.languageAccents["kotlin"] = "#ABCDEF"
        mappingsState.projectFallbackAccents[tmp] = "bad-hex"
        val project = stubProject(File(tmp))

        every { ProjectLanguageDetector.dominant(project) } returns null
        every { ProjectLanguageDetector.verdict(project) } returns
            ProjectLanguageVerdict.NoWinner(mapOf("typescript" to 500L, "javascript" to 500L))

        val chain = AccentResolver.resolveChain(project, AyuVariant.MIRAGE)

        val fallbackStep = chain.steps[3]
        assertEquals(AccentResolver.Source.PROJECT_FALLBACK, fallbackStep.source)
        assertEquals(StepOutcome.NOT_SET, fallbackStep.outcome)
        assertEquals("Invalid hex in project fallback", fallbackStep.detail)

        assertEquals(AccentResolver.Source.GLOBAL, chain.winner.source)
    }

    // Global wins, no overrides

    @Test
    fun `resolveChain global wins when no overrides configured and no language mappings`() {
        val project = stubProject(File(stubsDir, "no-overrides"))

        val chain = AccentResolver.resolveChain(project, AyuVariant.MIRAGE)

        // Empty state: 3 premium NOT_SET steps + 1 GLOBAL = 4
        assertEquals(4, chain.steps.size)

        // First 3 are NOT_SET (LANGUAGE_OVERRIDE skips fallback when no language mappings)
        chain.steps.take(3).forEach { step ->
            assertEquals(StepOutcome.NOT_SET, step.outcome, "${step.source} should be NOT_SET")
            assertNull(step.hex)
        }

        // Last step is GLOBAL winner
        val globalStep = chain.steps.last()
        assertEquals(AccentResolver.Source.GLOBAL, globalStep.source)
        assertEquals(globalMirageAccent, globalStep.hex)
        assertEquals(StepOutcome.WON, globalStep.outcome)
        assertEquals("Default accent for mirage", globalStep.detail)

        assertEquals(globalStep, chain.winner)
        assertNull(chain.verdict, "Verdict should be null — detector not consulted")
    }

    @Test
    fun `resolveChain global uses correct variant accent`() {
        val project = stubProject(File(stubsDir, "dark-variant"))

        val chain = AccentResolver.resolveChain(project, AyuVariant.DARK)

        val globalStep = chain.steps.last()
        assertEquals(AccentResolver.Source.GLOBAL, globalStep.source)
        assertEquals("#E6B450", globalStep.hex)
        assertEquals("Default accent for dark", globalStep.detail)
    }

    // External context with manual accent

    @Test
    fun `resolveChain external manual accent wins immediately`() {
        val state = AyuIslandsState()
        state.externalThemeAccentSource = ExternalAccentSource.MANUAL.name
        state.externalThemeAccent = "#13579B"

        val settings = mockk<AyuIslandsSettings>()
        every { settings.state } returns state
        every { settings.getAccentForVariant(any()) } returns globalMirageAccent
        mockkObject(AyuIslandsSettings.Companion)
        every { AyuIslandsSettings.getInstance() } returns settings

        val chain = AccentResolver.resolveChain(null, AccentContext.External)

        assertEquals(1, chain.steps.size, "Only the manual external step should exist")

        val step = chain.steps.single()
        assertEquals(AccentResolver.Source.EXTERNAL_ACCENT, step.source)
        assertEquals("#13579B", step.hex)
        assertEquals(StepOutcome.WON, step.outcome)
        assertEquals(step, chain.winner)
        assertNull(chain.verdict)
    }

    @Test
    fun `resolveChain external manual accent ignores project overrides`() {
        val state = AyuIslandsState()
        state.externalThemeAccentSource = ExternalAccentSource.MANUAL.name
        state.externalThemeAccent = "#97531B"

        val settings = mockk<AyuIslandsSettings>()
        every { settings.state } returns state
        every { settings.getAccentForVariant(any()) } returns globalMirageAccent
        mockkObject(AyuIslandsSettings.Companion)
        every { AyuIslandsSettings.getInstance() } returns settings

        val tmp = File(stubsDir, "external-manual-override").canonicalPath
        mappingsState.projectAccents[tmp] = "#111111"
        val project = stubProject(File(tmp))

        val chain = AccentResolver.resolveChain(project, AccentContext.External)

        assertEquals(1, chain.steps.size)
        assertEquals(AccentResolver.Source.EXTERNAL_ACCENT, chain.winner.source)
        assertEquals("#97531B", chain.winner.hex)
    }

    @Test
    fun `resolveChain external manual with invalid hex falls back to mirage default`() {
        val state = AyuIslandsState()
        state.externalThemeAccentSource = ExternalAccentSource.MANUAL.name
        state.externalThemeAccent = "not-a-hex"

        val settings = mockk<AyuIslandsSettings>()
        every { settings.state } returns state
        every { settings.getAccentForVariant(any()) } returns globalMirageAccent
        mockkObject(AyuIslandsSettings.Companion)
        every { AyuIslandsSettings.getInstance() } returns settings

        val chain = AccentResolver.resolveChain(null, AccentContext.External)

        assertEquals(1, chain.steps.size)
        assertEquals(AyuVariant.MIRAGE.defaultAccent, chain.winner.hex)
    }

    // External automatic chain

    @Test
    fun `resolveChain external automatic with project override wins`() {
        val state = AyuIslandsState()
        state.externalThemeAccentSource = ExternalAccentSource.AUTOMATIC.name
        state.externalThemeAccent = "#445566"

        val settings = mockk<AyuIslandsSettings>()
        every { settings.state } returns state
        every { settings.getAccentForVariant(any()) } returns globalMirageAccent
        mockkObject(AyuIslandsSettings.Companion)
        every { AyuIslandsSettings.getInstance() } returns settings

        val tmp = File(stubsDir, "external-override").canonicalPath
        mappingsState.projectAccents[tmp] = "#112233"
        val project = stubProject(File(tmp))

        AccentResolver.withUiColorProviderForTest({ null }) {
            val chain = AccentResolver.resolveChain(project, AccentContext.External)

            val projectStep = chain.steps[0]
            assertEquals(AccentResolver.Source.PROJECT_OVERRIDE, projectStep.source)
            assertEquals("#112233", projectStep.hex)
            assertEquals(StepOutcome.WON, projectStep.outcome)
            assertEquals(projectStep, chain.winner)
        }
    }

    @Test
    fun `resolveChain external automatic uses material accent before stored fallback`() {
        val state = AyuIslandsState()
        state.externalThemeAccentSource = ExternalAccentSource.AUTOMATIC.name
        state.externalThemeAccent = "#445566"

        val settings = mockk<AyuIslandsSettings>()
        every { settings.state } returns state
        every { settings.getAccentForVariant(any()) } returns globalMirageAccent
        mockkObject(AyuIslandsSettings.Companion)
        every { AyuIslandsSettings.getInstance() } returns settings

        AccentResolver.withUiColorProviderForTest(
            { key ->
                if (key == "material.accent") {
                    Color(0x0A, 0x14, 0x1E)
                } else {
                    null
                }
            },
        ) {
            val chain = AccentResolver.resolveChain(null, AccentContext.External)

            assertEquals("#0A141E", AccentResolver.resolve(null, AccentContext.External))
            assertEquals(
                AccentResolver.Source.MATERIAL_THEME,
                AccentResolver.source(null, AccentContext.External),
            )
            assertEquals(AccentResolver.Source.MATERIAL_THEME, chain.winner.source)
            assertEquals("#0A141E", chain.winner.hex)
            assertEquals(StepOutcome.WON, chain.winner.outcome)
        }
    }

    @Test
    fun `resolveChain external automatic uses IDE accent before stored fallback`() {
        val state = AyuIslandsState()
        state.externalThemeAccentSource = ExternalAccentSource.AUTOMATIC.name
        state.externalThemeAccent = "#445566"

        val settings = mockk<AyuIslandsSettings>()
        every { settings.state } returns state
        every { settings.getAccentForVariant(any()) } returns globalMirageAccent
        mockkObject(AyuIslandsSettings.Companion)
        every { AyuIslandsSettings.getInstance() } returns settings

        AccentResolver.withUiColorProviderForTest(
            { key ->
                if (key == "Component.accentColor") {
                    Color(0xC8, 0x64, 0x32)
                } else {
                    null
                }
            },
        ) {
            val chain = AccentResolver.resolveChain(null, AccentContext.External)

            assertEquals("#C86432", AccentResolver.resolve(null, AccentContext.External))
            assertEquals(
                AccentResolver.Source.IDE_ACCENT,
                AccentResolver.source(null, AccentContext.External),
            )
            assertEquals(AccentResolver.Source.IDE_ACCENT, chain.winner.source)
            assertEquals("#C86432", chain.winner.hex)
            assertEquals(StepOutcome.WON, chain.winner.outcome)
        }
    }

    // Helpers

    private fun stubProject(baseDir: File): Project {
        val project = mockk<Project>()
        every { project.isDefault } returns false
        every { project.isDisposed } returns false
        every { project.basePath } returns baseDir.path
        every { project.name } returns baseDir.name
        return project
    }
}
