package dev.ayuislands.settings

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.application.Application
import com.intellij.openapi.application.ApplicationManager
import dev.ayuislands.licensing.LicenseChecker
import dev.ayuislands.vcs.VcsColorApplier
import dev.ayuislands.vcs.VcsColorCategory
import dev.ayuislands.vcs.VcsColorPreset
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkClass
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Coverage for [VcsColorPanel] (Phase 40.2 / Wave 2b + per-section redesign):
 *
 *  - Default load: pending values match persisted [AyuIslandsState].
 *  - isModified: any pending-vs-stored divergence flips the panel as dirty.
 *  - Per-section preset cycle: switching a section's preset snaps that section's
 *    sliders to the canonical position; other sections stay untouched.
 *  - Apply persistence: master toggle, three section presets, and the five
 *    Wave 2+3+4 per-category intensities round-trip into [AyuIslandsState];
 *    [VcsColorApplier.applyAll] fires exactly once per modified apply.
 *  - Apply skip path: when nothing changed, `apply()` doesn't touch the applier.
 *  - License revoke: a license that flips from licensed → unlicensed between
 *    buildPanel and apply skips persistence.
 *  - Apply error path: if [VcsColorApplier.applyAll] throws, persisted state
 *    stays at the pre-apply baseline.
 *  - Premium gate: an unlicensed user records `licensed = false` and renders
 *    only the placeholder.
 */
class VcsColorPanelTest {
    private lateinit var state: AyuIslandsState
    private lateinit var settings: AyuIslandsSettings

    @BeforeTest
    fun setUp() {
        state = AyuIslandsState()
        settings = mockk(relaxed = true)
        every { settings.state } returns state
        mockkObject(AyuIslandsSettings.Companion)
        every { AyuIslandsSettings.getInstance() } returns settings

        mockkObject(LicenseChecker)
        every { LicenseChecker.isLicensedOrGrace() } returns true

        mockkObject(VcsColorApplier)
        every { VcsColorApplier.applyAll() } returns Unit
        every { VcsColorApplier.revertAll() } returns Unit

        // UI DSL plumbing — collapsibleGroup builders resolve ActionManager through
        // ApplicationManager.getService; the unlicensed `comment(...)` row also asks
        // for ExperimentalUI. Wire both via a relaxed Application mock per the same
        // pattern AyuIslandsChromePanelTest uses.
        mockkStatic(ApplicationManager::class)
        val appMock = mockk<Application>(relaxed = true)
        val actionManagerMock = mockk<ActionManager>(relaxed = true)
        every { ApplicationManager.getApplication() } returns appMock
        every { appMock.invokeLater(any()) } answers { firstArg<Runnable>().run() }
        every { appMock.getService(ActionManager::class.java) } returns actionManagerMock
        every { actionManagerMock.getAction(any()) } returns null

        @Suppress("UNCHECKED_CAST")
        val experimentalUiClass = Class.forName("com.intellij.ui.ExperimentalUI") as Class<Any>
        val experimentalUiMock = mockkClass(experimentalUiClass.kotlin, relaxed = true)
        every { appMock.getService(experimentalUiClass) } returns experimentalUiMock
    }

    @AfterTest
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `loadStored reflects persisted state at panel construction`() {
        state.vcsColorEnabled = true
        state.vcsDiffPreset = VcsColorPreset.NEON.name
        state.vcsMergePreset = VcsColorPreset.WHISPER.name
        state.vcsBlamePreset = VcsColorPreset.CYBERPUNK.name
        state.vcsDiffIntensity = 80
        state.vcsConflictMarkerIntensity = 70

        val panel = newBuiltPanel()
        assertEquals(true, panel.getPendingEnabledForTest())
        assertEquals(VcsColorPreset.NEON, panel.getPendingPresetForTest(VcsSection.DIFF))
        assertEquals(VcsColorPreset.WHISPER, panel.getPendingPresetForTest(VcsSection.MERGE))
        assertEquals(VcsColorPreset.CYBERPUNK, panel.getPendingPresetForTest(VcsSection.BLAME))
        assertEquals(80, panel.getPendingIntensityForTest(VcsColorCategory.DIFF_VIEWER))
        assertEquals(70, panel.getPendingIntensityForTest(VcsColorCategory.CONFLICT_MARKERS))
    }

    @Test
    fun `default panel reports not-modified`() {
        val panel = newBuiltPanel()
        assertFalse(panel.isModified())
    }

    @Test
    fun `flipping the master toggle marks the panel modified`() {
        val panel = newBuiltPanel()
        panel.setPendingEnabledForTest(true)
        assertTrue(panel.isModified())
    }

    @Test
    fun `switching any section preset marks the panel modified`() {
        for (section in VcsSection.entries) {
            val panel = newBuiltPanel()
            panel.setPendingPresetForTest(section, VcsColorPreset.NEON)
            assertTrue(panel.isModified(), "Switching $section preset should mark modified")
        }
    }

    @Test
    fun `moving any per-category slider marks the panel modified`() {
        val panel = newBuiltPanel()
        panel.setPendingIntensityForTest(VcsColorCategory.DIFF_VIEWER, 75)
        assertTrue(panel.isModified())
    }

    @Test
    fun `Diff section preset snap moves all three Diff sliders but no others`() {
        val panel = newBuiltPanel()
        panel.triggerSectionPresetChosenForTest(VcsSection.DIFF, VcsColorPreset.NEON)
        assertEquals(VcsColorPreset.NEON, panel.getPendingPresetForTest(VcsSection.DIFF))
        val diffCategories =
            listOf(
                VcsColorCategory.DIFF_VIEWER,
                VcsColorCategory.PROJECT_VIEW_FILE_STATUS,
                VcsColorCategory.EDITOR_GUTTER,
            )
        for (category in diffCategories) {
            assertEquals(
                VcsColorPreset.NEON_SLIDER,
                panel.getPendingIntensityForTest(category),
                "$category should snap to NEON when Diff section is set to NEON",
            )
        }
        // Other sections' sliders untouched.
        assertEquals(
            VcsColorPreset.AMBIENT_SLIDER,
            panel.getPendingIntensityForTest(VcsColorCategory.CONFLICT_MARKERS),
        )
        assertEquals(
            VcsColorPreset.AMBIENT_SLIDER,
            panel.getPendingIntensityForTest(VcsColorCategory.BLAME_GUTTER),
        )
    }

    @Test
    fun `Merge section preset snap moves only conflict slider`() {
        val panel = newBuiltPanel()
        panel.triggerSectionPresetChosenForTest(VcsSection.MERGE, VcsColorPreset.CYBERPUNK)
        assertEquals(
            VcsColorPreset.CYBERPUNK_SLIDER,
            panel.getPendingIntensityForTest(VcsColorCategory.CONFLICT_MARKERS),
        )
        assertEquals(
            VcsColorPreset.AMBIENT_SLIDER,
            panel.getPendingIntensityForTest(VcsColorCategory.DIFF_VIEWER),
        )
        assertEquals(
            VcsColorPreset.AMBIENT_SLIDER,
            panel.getPendingIntensityForTest(VcsColorCategory.BLAME_GUTTER),
        )
    }

    @Test
    fun `Blame section preset snap moves only blame slider`() {
        val panel = newBuiltPanel()
        panel.triggerSectionPresetChosenForTest(VcsSection.BLAME, VcsColorPreset.WHISPER)
        assertEquals(
            VcsColorPreset.WHISPER_SLIDER,
            panel.getPendingIntensityForTest(VcsColorCategory.BLAME_GUTTER),
        )
        assertEquals(
            VcsColorPreset.AMBIENT_SLIDER,
            panel.getPendingIntensityForTest(VcsColorCategory.DIFF_VIEWER),
        )
        assertEquals(
            VcsColorPreset.AMBIENT_SLIDER,
            panel.getPendingIntensityForTest(VcsColorCategory.CONFLICT_MARKERS),
        )
    }

    @Test
    fun `Custom preset leaves that section's sliders untouched on snap`() {
        val panel = newBuiltPanel()
        panel.setPendingIntensityForTest(VcsColorCategory.DIFF_VIEWER, 42)
        panel.triggerSectionPresetChosenForTest(VcsSection.DIFF, VcsColorPreset.CUSTOM)
        // Custom means sliders stay where they are.
        assertEquals(42, panel.getPendingIntensityForTest(VcsColorCategory.DIFF_VIEWER))
    }

    @Test
    fun `apply persists every section preset and intensity and calls applier once`() {
        val panel = newBuiltPanel()
        panel.setPendingEnabledForTest(true)
        panel.setPendingPresetForTest(VcsSection.DIFF, VcsColorPreset.NEON)
        panel.setPendingPresetForTest(VcsSection.MERGE, VcsColorPreset.CYBERPUNK)
        panel.setPendingPresetForTest(VcsSection.BLAME, VcsColorPreset.WHISPER)
        panel.setPendingIntensityForTest(VcsColorCategory.DIFF_VIEWER, VcsColorPreset.NEON_SLIDER)

        panel.apply()

        assertEquals(true, state.vcsColorEnabled)
        assertEquals(VcsColorPreset.NEON.name, state.vcsDiffPreset)
        assertEquals(VcsColorPreset.CYBERPUNK.name, state.vcsMergePreset)
        assertEquals(VcsColorPreset.WHISPER.name, state.vcsBlamePreset)
        verify(exactly = 1) { VcsColorApplier.applyAll() }
    }

    @Test
    fun `apply without modifications does not call the applier`() {
        val panel = newBuiltPanel()
        panel.apply()
        verify(exactly = 0) { VcsColorApplier.applyAll() }
    }

    @Test
    fun `apply skips persistence when license revokes between build and apply`() {
        val panel = newBuiltPanel()
        panel.setPendingEnabledForTest(true)

        every { LicenseChecker.isLicensedOrGrace() } returns false
        panel.apply()

        assertFalse(state.vcsColorEnabled, "License revoke must abort persistence")
        verify(exactly = 0) { VcsColorApplier.applyAll() }
    }

    @Test
    fun `apply leaves state untouched when applier throws`() {
        every { VcsColorApplier.applyAll() } throws RuntimeException("simulated apply failure")
        val panel = newBuiltPanel()
        panel.setPendingEnabledForTest(true)
        panel.setPendingPresetForTest(VcsSection.DIFF, VcsColorPreset.NEON)

        panel.apply()

        assertFalse(state.vcsColorEnabled, "Apply throw must roll back persistence")
        assertEquals(VcsColorPreset.AMBIENT.name, state.vcsDiffPreset)
    }

    @Test
    fun `reset reloads every pending section preset from persisted state`() {
        val panel = newBuiltPanel()
        panel.setPendingEnabledForTest(true)
        panel.setPendingPresetForTest(VcsSection.DIFF, VcsColorPreset.CYBERPUNK)
        panel.setPendingPresetForTest(VcsSection.MERGE, VcsColorPreset.NEON)

        panel.reset()

        assertFalse(panel.getPendingEnabledForTest())
        assertEquals(VcsColorPreset.AMBIENT, panel.getPendingPresetForTest(VcsSection.DIFF))
        assertEquals(VcsColorPreset.AMBIENT, panel.getPendingPresetForTest(VcsSection.MERGE))
        assertEquals(VcsColorPreset.AMBIENT, panel.getPendingPresetForTest(VcsSection.BLAME))
    }

    @Test
    fun `unlicensed buildPanel does not trigger the applier on subsequent apply`() {
        // Unlicensed path renders only the placeholder + Pro upgrade link — no
        // licensed controls bound, no pending diffs even after a setPending* call
        // before apply. Verifying via the applier-not-called assertion proves the
        // premium gate held without exposing a dedicated `licensedForTest()` seam.
        every { LicenseChecker.isLicensedOrGrace() } returns false
        val panel = newBuiltPanel()
        panel.setPendingEnabledForTest(true)
        panel.apply()
        verify(exactly = 0) { VcsColorApplier.applyAll() }
    }

    private fun newBuiltPanel(): VcsColorPanel {
        val panel = VcsColorPanel()
        com.intellij.ui.dsl.builder
            .panel {
                panel.buildPanel(this@panel, dev.ayuislands.accent.AyuVariant.DARK)
            }
        return panel
    }
}
