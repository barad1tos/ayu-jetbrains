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
 * Coverage for [VcsColorPanel] (Phase 40.2 / Wave 2b):
 *
 *  - Default load: pending values match the persisted [AyuIslandsState].
 *  - isModified: any pending-vs-stored divergence flips the panel as dirty.
 *  - Preset cycle: switching to a non-Custom preset snaps every per-category
 *    slider to that preset's canonical slider position; switching to Custom
 *    leaves them alone.
 *  - Apply persistence: the five Wave 2 state fields round-trip into
 *    [AyuIslandsState] and [VcsColorApplier.applyAll] fires exactly once per
 *    modified apply.
 *  - Apply skip path: when nothing changed, `apply()` does NOT touch the
 *    applier.
 *  - Apply license-revoke path: a license that goes from licensed to
 *    unlicensed between buildPanel and apply skips persistence — same shape
 *    as [AyuIslandsChromePanel.apply].
 *  - Apply error path: if [VcsColorApplier.applyAll] throws, persisted state
 *    stays at the pre-apply baseline so the user can retry.
 *  - Premium gate: an unlicensed user sees no checkbox / preset / slider —
 *    the panel records `licensed = false` and renders only the placeholder
 *    comment + Pro upgrade link.
 *
 * Tests use the panel's `@TestOnly` seams rather than walking a built
 * DialogPanel — mirrors the project convention established by
 * [AyuIslandsChromePanelTest].
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
        state.vcsColorPreset = VcsColorPreset.NEON.name
        state.vcsDiffIntensity = 80
        state.vcsProjectViewIntensity = 70
        state.vcsGutterIntensity = 60

        val panel = newBuiltPanel()
        assertEquals(true, panel.getPendingEnabledForTest())
        assertEquals(VcsColorPreset.NEON, panel.getPendingPresetForTest())
        assertEquals(80, panel.getPendingIntensityForTest(VcsColorCategory.DIFF_VIEWER))
        assertEquals(70, panel.getPendingIntensityForTest(VcsColorCategory.PROJECT_VIEW_FILE_STATUS))
        assertEquals(60, panel.getPendingIntensityForTest(VcsColorCategory.EDITOR_GUTTER))
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
    fun `switching preset marks the panel modified`() {
        val panel = newBuiltPanel()
        panel.setPendingPresetForTest(VcsColorPreset.NEON)
        assertTrue(panel.isModified())
    }

    @Test
    fun `moving any per-category slider marks the panel modified`() {
        val panel = newBuiltPanel()
        panel.setPendingIntensityForTest(VcsColorCategory.DIFF_VIEWER, 75)
        assertTrue(panel.isModified())
    }

    @Test
    fun `triggering a non-Custom preset snaps all sliders to that preset's slider value`() {
        val panel = newBuiltPanel()
        panel.triggerPresetChosenForTest(VcsColorPreset.NEON)
        assertEquals(VcsColorPreset.NEON, panel.getPendingPresetForTest())
        val wave2Categories =
            listOf(
                VcsColorCategory.DIFF_VIEWER,
                VcsColorCategory.PROJECT_VIEW_FILE_STATUS,
                VcsColorCategory.EDITOR_GUTTER,
            )
        for (category in wave2Categories) {
            assertEquals(
                VcsColorPreset.NEON_SLIDER,
                panel.getPendingIntensityForTest(category),
                "$category should snap to NEON slider value",
            )
        }
    }

    @Test
    fun `triggering Custom preset leaves per-category sliders untouched`() {
        val panel = newBuiltPanel()
        panel.setPendingIntensityForTest(VcsColorCategory.DIFF_VIEWER, 42)
        panel.setPendingIntensityForTest(VcsColorCategory.PROJECT_VIEW_FILE_STATUS, 88)
        panel.triggerPresetChosenForTest(VcsColorPreset.CUSTOM)
        assertEquals(42, panel.getPendingIntensityForTest(VcsColorCategory.DIFF_VIEWER))
        assertEquals(88, panel.getPendingIntensityForTest(VcsColorCategory.PROJECT_VIEW_FILE_STATUS))
    }

    @Test
    fun `apply persists all five fields and calls applier once`() {
        val panel = newBuiltPanel()
        panel.setPendingEnabledForTest(true)
        panel.setPendingPresetForTest(VcsColorPreset.NEON)
        panel.setPendingIntensityForTest(VcsColorCategory.DIFF_VIEWER, VcsColorPreset.NEON_SLIDER)
        panel.setPendingIntensityForTest(VcsColorCategory.PROJECT_VIEW_FILE_STATUS, VcsColorPreset.NEON_SLIDER)
        panel.setPendingIntensityForTest(VcsColorCategory.EDITOR_GUTTER, VcsColorPreset.NEON_SLIDER)

        panel.apply()

        assertEquals(true, state.vcsColorEnabled)
        assertEquals(VcsColorPreset.NEON.name, state.vcsColorPreset)
        assertEquals(VcsColorPreset.NEON_SLIDER, state.vcsDiffIntensity)
        assertEquals(VcsColorPreset.NEON_SLIDER, state.vcsProjectViewIntensity)
        assertEquals(VcsColorPreset.NEON_SLIDER, state.vcsGutterIntensity)
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

        // License flip mid-session.
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
        panel.setPendingPresetForTest(VcsColorPreset.NEON)

        panel.apply()

        assertFalse(state.vcsColorEnabled, "Apply throw must roll back persistence")
        assertEquals(VcsColorPreset.AMBIENT.name, state.vcsColorPreset)
    }

    @Test
    fun `reset reloads pending fields from persisted state`() {
        val panel = newBuiltPanel()
        panel.setPendingEnabledForTest(true)
        panel.setPendingPresetForTest(VcsColorPreset.CYBERPUNK)
        panel.setPendingIntensityForTest(VcsColorCategory.DIFF_VIEWER, 90)

        panel.reset()

        assertFalse(panel.getPendingEnabledForTest())
        assertEquals(VcsColorPreset.AMBIENT, panel.getPendingPresetForTest())
        assertEquals(
            VcsColorPreset.AMBIENT_SLIDER,
            panel.getPendingIntensityForTest(VcsColorCategory.DIFF_VIEWER),
        )
    }

    @Test
    fun `unlicensed buildPanel records the gate without binding controls`() {
        every { LicenseChecker.isLicensedOrGrace() } returns false
        val panel = newBuiltPanel()
        assertFalse(panel.licensedForTest(), "Unlicensed build must record the gate")
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
