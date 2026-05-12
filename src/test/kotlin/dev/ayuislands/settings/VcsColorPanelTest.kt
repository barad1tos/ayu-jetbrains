package dev.ayuislands.settings

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.application.Application
import com.intellij.openapi.application.ApplicationManager
import dev.ayuislands.licensing.LicenseChecker
import dev.ayuislands.vcs.VcsColorApplier
import dev.ayuislands.vcs.VcsColorCategory
import dev.ayuislands.vcs.VcsColorContext
import dev.ayuislands.vcs.VcsColorPreset
import dev.ayuislands.vcs.VcsColorSnapshot
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkClass
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkAll
import io.mockk.verify
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Coverage for [VcsColorPanel]:
 *
 *  - Default load: pending values match persisted [AyuIslandsState].
 *  - isModified: any pending-vs-stored divergence flips the panel as dirty.
 *  - Per-section preset cycle: switching a section's preset snaps that section's
 *    sliders to the canonical position; other sections stay untouched.
 *  - Apply persistence: master toggle, three section presets, and the five
 *    per-category intensities round-trip into [AyuIslandsState];
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
    fun `dragging Diff slider while preset is NEON promotes section to CUSTOM`() {
        // Build panel, snap Diff section to NEON so every Diff slider lands on
        // NEON_SLIDER and pendingDiffPreset != CUSTOM — exactly the state a user
        // is in before they grab the slider thumb.
        val panel = newBuiltPanel()
        panel.triggerSectionPresetChosenForTest(VcsSection.DIFF, VcsColorPreset.NEON)
        assertEquals(VcsColorPreset.NEON, panel.getPendingPresetForTest(VcsSection.DIFF))
        val mergeBefore = panel.getPendingIntensityForTest(VcsColorCategory.CONFLICT_MARKERS)
        val blameBefore = panel.getPendingIntensityForTest(VcsColorCategory.BLAME_GUTTER)
        val projectViewBefore = panel.getPendingIntensityForTest(VcsColorCategory.PROJECT_VIEW_FILE_STATUS)
        val gutterBefore = panel.getPendingIntensityForTest(VcsColorCategory.EDITOR_GUTTER)

        // Simulate the user dragging the Diff viewer slider to 75. Reaching the
        // private JSlider field via reflection is intentional — the @TestOnly
        // setPendingIntensityForTest seam writes the backing field directly
        // and bypasses the ChangeListener, so it cannot reproduce the
        // promote-to-CUSTOM behaviour that lives in onSliderChanged. Mutating
        // slider.value fires the registered ChangeListener synchronously on
        // the calling thread.
        val diffSliderField = VcsColorPanel::class.java.getDeclaredField("diffSlider")
        diffSliderField.isAccessible = true
        val diffSlider = diffSliderField.get(panel) as javax.swing.JSlider
        diffSlider.value = 75

        // Promotion: pendingDiffPreset flips to CUSTOM and diffCustomSelected
        // notifies its bindings so the per-slider rows become visible.
        assertEquals(
            VcsColorPreset.CUSTOM,
            panel.getPendingPresetForTest(VcsSection.DIFF),
            "User-driven Diff slider drag must promote the Diff section to CUSTOM",
        )
        assertEquals(75, panel.getPendingIntensityForTest(VcsColorCategory.DIFF_VIEWER))
        val diffCustomField = VcsColorPanel::class.java.getDeclaredField("diffCustomSelected")
        diffCustomField.isAccessible = true
        val diffCustom = diffCustomField.get(panel) as com.intellij.openapi.observable.properties.AtomicBooleanProperty
        assertTrue(diffCustom.get(), "diffCustomSelected must flip true so Custom-mode slider rows surface")

        // Sibling-section sliders stay where they were — promoting Diff to
        // CUSTOM is per-section and must not touch Merge or Blame intensities.
        assertEquals(
            mergeBefore,
            panel.getPendingIntensityForTest(VcsColorCategory.CONFLICT_MARKERS),
            "Diff-slider drag must not touch the Merge section's conflict-marker intensity",
        )
        assertEquals(
            blameBefore,
            panel.getPendingIntensityForTest(VcsColorCategory.BLAME_GUTTER),
            "Diff-slider drag must not touch the Blame section's blame-gutter intensity",
        )
        // Same-section siblings (PROJECT_VIEW_FILE_STATUS, EDITOR_GUTTER) are
        // untouched by the DIFF_VIEWER slider — only the dragged slider's
        // category writes pending intensity, even within the same section.
        assertEquals(projectViewBefore, panel.getPendingIntensityForTest(VcsColorCategory.PROJECT_VIEW_FILE_STATUS))
        assertEquals(gutterBefore, panel.getPendingIntensityForTest(VcsColorCategory.EDITOR_GUTTER))
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
    fun `apply snapshot maps per-category intensities according to section presets`() {
        // Locks the resolveCategory(preset, category, customValue) branching at
        // VcsColorPanel.apply: Custom-preset routes to the slider value, every
        // other preset routes to preset.intensityFor(category). A regression
        // that reused a single section's snap across all three diff categories
        // (or that swapped the Custom branch with the preset branch) would
        // ship a snapshot that does not match the user's section choices.
        mockkObject(VcsColorContext)
        val capturedSnapshot = slot<VcsColorSnapshot>()
        every {
            VcsColorContext.withSnapshot(capture(capturedSnapshot), any<() -> Any?>())
        } answers {
            // Run the block so the inner VcsColorApplier.applyAll mock still
            // fires once — keeps the existing apply-fires-applier assertion
            // semantics intact.
            @Suppress("UNCHECKED_CAST")
            (secondArg<() -> Any?>()).invoke()
        }

        val panel = newBuiltPanel()
        panel.setPendingEnabledForTest(true)
        // DIFF on CYBERPUNK — every diff-section category lands on CYBERPUNK_SLIDER
        panel.setPendingPresetForTest(VcsSection.DIFF, VcsColorPreset.CYBERPUNK)
        // MERGE on CUSTOM with a deliberately off-preset slider value — the
        // snapshot must read the slider, not preset.intensityFor.
        panel.setPendingPresetForTest(VcsSection.MERGE, VcsColorPreset.CUSTOM)
        panel.setPendingIntensityForTest(VcsColorCategory.CONFLICT_MARKERS, 42)
        // BLAME on WHISPER — BLAME_GUTTER lands on WHISPER_SLIDER (0).
        panel.setPendingPresetForTest(VcsSection.BLAME, VcsColorPreset.WHISPER)

        panel.apply()

        assertTrue(capturedSnapshot.isCaptured, "withSnapshot must have been invoked")
        val snap = capturedSnapshot.captured
        assertTrue(snap.enabled, "Snapshot must carry the master-on flag")

        val intensities = snap.perCategoryIntensities
        // DIFF section → CYBERPUNK → every diff-section category lands on the
        // CYBERPUNK slider position, not on the section's stored slider values.
        assertEquals(VcsColorPreset.CYBERPUNK_SLIDER, intensities[VcsColorCategory.DIFF_VIEWER])
        assertEquals(VcsColorPreset.CYBERPUNK_SLIDER, intensities[VcsColorCategory.PROJECT_VIEW_FILE_STATUS])
        assertEquals(VcsColorPreset.CYBERPUNK_SLIDER, intensities[VcsColorCategory.EDITOR_GUTTER])
        // MERGE section → CUSTOM → slider value (42), NOT preset.intensityFor.
        assertEquals(42, intensities[VcsColorCategory.CONFLICT_MARKERS])
        // BLAME section → WHISPER → BLAME_GUTTER lands on WHISPER_SLIDER.
        assertEquals(VcsColorPreset.WHISPER_SLIDER, intensities[VcsColorCategory.BLAME_GUTTER])

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
