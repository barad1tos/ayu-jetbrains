package dev.ayuislands

import com.intellij.ide.ui.LafManager
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.util.io.FileUtil
import dev.ayuislands.accent.AccentApplicator
import dev.ayuislands.accent.AyuVariant
import dev.ayuislands.font.FontPresetApplicator
import dev.ayuislands.glow.GlowOverlayManager
import dev.ayuislands.settings.AyuIslandsSettings
import dev.ayuislands.settings.AyuIslandsState
import dev.ayuislands.syntax.SyntaxIntensityService
import dev.ayuislands.theme.AyuEditorSchemeBinder
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Verifies that [AyuIslandsLafListener.lookAndFeelChanged] invokes
 * [SyntaxIntensityService.reapplyForActiveLaf] when [AyuVariant.isAyuActive] is
 * true (Pattern J anchor), AND that the plugin.xml does NOT register a
 * duplicate `LafManagerListener` for the listener class (Pattern L
 * source-regex regression lock — extend, do not duplicate).
 *
 * Mock surface: mirrors [AyuIslandsLafListenerTest] (all listener
 * dependencies stubbed) and adds [SyntaxIntensityService.Companion] so the
 * `getInstance()` call site resolves without an [IllegalStateException]
 * from the platform `ApplicationManager`.
 */
@Suppress("UnstableApiUsage")
class AyuIslandsLafListenerSyntaxReapplyTest {
    private val state = AyuIslandsState()
    private val mockSettings = mockk<AyuIslandsSettings>(relaxed = true)
    private val mockProjectManager = mockk<ProjectManager>(relaxed = true)
    private val mockSyntaxService = mockk<SyntaxIntensityService>(relaxed = true)

    @BeforeTest
    fun setUp() {
        every { mockSettings.state } returns state
        mockkObject(AyuIslandsSettings.Companion)
        every { AyuIslandsSettings.getInstance() } returns mockSettings

        mockkStatic(ProjectManager::class)
        every { ProjectManager.getInstance() } returns mockProjectManager
        every { mockProjectManager.openProjects } returns emptyArray()

        mockkObject(AyuVariant.Companion)
        mockkObject(AyuEditorSchemeBinder)
        mockkObject(AccentApplicator)
        mockkObject(FontPresetApplicator)
        mockkObject(GlowOverlayManager)
        mockkObject(AppearanceSyncService.Companion)
        mockkObject(SyntaxIntensityService.Companion)

        every { AccentApplicator.applyForFocusedProject(any<dev.ayuislands.accent.AyuVariant>()) } returns "#FFCC66"
        every { AccentApplicator.revertAll() } returns Unit
        every { FontPresetApplicator.applyFromState() } returns Unit
        every { FontPresetApplicator.revert() } returns Unit
        every { GlowOverlayManager.syncGlowForAllProjects() } returns Unit
        every { AyuEditorSchemeBinder.bindForVariant(any()) } returns true
        every { SyntaxIntensityService.getInstance() } returns mockSyntaxService

        val mockSyncService = mockk<AppearanceSyncService>(relaxed = true)
        every { AppearanceSyncService.getInstance() } returns mockSyncService
        every { mockSyncService.programmaticSwitch } returns false
    }

    @AfterTest
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `lookAndFeelChanged when AyuVariant isAyuActive true calls reapplyForActiveLaf`() {
        // Pattern J — reapply must fire whenever the LAF lands on an Ayu variant.
        // `detect` returns non-null (passes the early-return guard) and
        // `isAyuActive` returns true (passes the source-grep gate at the call site).
        every { AyuVariant.detect() } returns AyuVariant.MIRAGE
        every { AyuVariant.isAyuActive() } returns true
        val mockLafManager = mockk<LafManager>(relaxed = true)
        every { mockLafManager.currentUIThemeLookAndFeel.name } returns "Ayu Mirage (Islands UI)"

        AyuIslandsLafListener().lookAndFeelChanged(mockLafManager)

        verify(exactly = 1) { mockSyntaxService.reapplyForActiveLaf() }
    }

    @Test
    fun `lookAndFeelChanged when AyuVariant isAyuActive false does NOT call reapplyForActiveLaf`() {
        // Pattern J — non-Ayu LAF must skip the reapply path entirely.
        // `detect` returns null which triggers the listener's early-return
        // BEFORE the syntax reapply block; `isAyuActive` returns false to
        // double-lock the gate at the call site for future audits.
        every { AyuVariant.detect() } returns null
        every { AyuVariant.isAyuActive() } returns false
        val mockLafManager = mockk<LafManager>(relaxed = true)

        AyuIslandsLafListener().lookAndFeelChanged(mockLafManager)

        verify(exactly = 0) { mockSyntaxService.reapplyForActiveLaf() }
    }

    @Test
    fun `plugin xml does NOT register a duplicate LafManagerListener (Pattern L)`() {
        // Pattern L source-regex regression lock — the syntax intensity wiring
        // must EXTEND the existing listener rather than register a parallel one.
        // A second `<listener class="dev.ayuislands.AyuIslandsLafListener" .../>`
        // would cause the platform to fire the callback twice per LAF event,
        // doubling overlay writes and the reapply cost.
        val xmlText = FileUtil.loadFile(File("src/main/resources/META-INF/plugin.xml"))
        val regex =
            Regex(
                """<listener\b[^>]*\bclass="[^"]*AyuIslandsLafListener[^"]*"""",
                RegexOption.IGNORE_CASE,
            )
        val matches = regex.findAll(xmlText).count()
        assertTrue(
            matches == 1,
            "Expected EXACTLY ONE `AyuIslandsLafListener` registration, got $matches " +
                "— the syntax intensity wiring must extend the existing listener, not duplicate it.",
        )
    }
}
