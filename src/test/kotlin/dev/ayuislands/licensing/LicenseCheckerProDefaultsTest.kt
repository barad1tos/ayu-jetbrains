package dev.ayuislands.licensing

import dev.ayuislands.glow.GlowAnimation
import dev.ayuislands.glow.GlowPreset
import dev.ayuislands.glow.GlowStyle
import dev.ayuislands.settings.AyuIslandsState
import dev.ayuislands.settings.PanelWidthMode
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LicenseCheckerProDefaultsTest {
    private lateinit var state: AyuIslandsState

    @BeforeTest
    fun setUp() {
        state = AyuIslandsState()
    }

    // enableProDefaults — glow settings

    @Test
    fun `enableProDefaults enables glow`() {
        assertFalse(state.glowEnabled)
        LicenseDefaults.enablePro(state)
        assertTrue(state.glowEnabled)
    }

    @Test
    fun `enableProDefaults sets glow style to SHARP_NEON`() {
        LicenseDefaults.enablePro(state)
        assertEquals(GlowStyle.SHARP_NEON.name, state.glowStyle)
    }

    @Test
    fun `enableProDefaults sets glow preset to CUSTOM`() {
        LicenseDefaults.enablePro(state)
        assertEquals(GlowPreset.CUSTOM.name, state.glowPreset)
    }

    @Test
    fun `enableProDefaults sets animation to BREATHE`() {
        LicenseDefaults.enablePro(state)
        assertEquals(GlowAnimation.BREATHE.name, state.glowAnimation)
    }

    @Test
    fun `enableProDefaults sets intensity to 100 and width to 2`() {
        LicenseDefaults.enablePro(state)
        assertEquals(100, state.sharpNeonIntensity)
        assertEquals(2, state.sharpNeonWidth)
    }

    @Test
    fun `enableProDefaults sets all island toggles to true`() {
        state.glowEditor = false
        state.glowProject = false
        state.glowTerminal = false
        state.glowRun = false
        state.glowDebug = false
        state.glowGit = false
        state.glowServices = false

        LicenseDefaults.enablePro(state)

        assertTrue(state.glowEditor)
        assertTrue(state.glowProject)
        assertTrue(state.glowTerminal)
        assertTrue(state.glowRun)
        assertTrue(state.glowDebug)
        assertTrue(state.glowGit)
        assertTrue(state.glowServices)
    }

    @Test
    fun `enableProDefaults enables focus ring`() {
        state.glowFocusRing = false
        LicenseDefaults.enablePro(state)
        assertTrue(state.glowFocusRing)
    }

    @Test
    fun `enableProDefaults sets proDefaultsApplied flag`() {
        assertFalse(state.proDefaultsApplied)
        LicenseDefaults.enablePro(state)
        assertTrue(state.proDefaultsApplied)
    }

    // enableProDefaults does NOT set workspace defaults (b22c629: prevent overwriting customizations)

    @Test
    fun `enableProDefaults does not change panel width modes`() {
        assertEquals(PanelWidthMode.DEFAULT.name, state.projectPanelWidthMode)
        LicenseDefaults.enablePro(state)
        assertEquals(PanelWidthMode.DEFAULT.name, state.projectPanelWidthMode)
    }

    @Test
    fun `enableProDefaults does not change project root path or scrollbar`() {
        assertFalse(state.hideProjectRootPath)
        assertFalse(state.hideProjectViewHScrollbar)
        LicenseDefaults.enablePro(state)
        assertFalse(state.hideProjectRootPath)
        assertFalse(state.hideProjectViewHScrollbar)
    }

    @Test
    fun `enableProDefaults does not set workspaceDefaultsApplied`() {
        assertFalse(state.workspaceDefaultsApplied)
        LicenseDefaults.enablePro(state)
        assertFalse(state.workspaceDefaultsApplied)
    }

    // applyWorkspaceDefaults (standalone)

    @Test
    fun `applyWorkspaceDefaults sets all three panel modes to AUTO_FIT`() {
        LicenseDefaults.applyWorkspace(state)

        assertEquals(PanelWidthMode.AUTO_FIT.name, state.projectPanelWidthMode)
        assertEquals(PanelWidthMode.AUTO_FIT.name, state.commitPanelWidthMode)
        assertEquals(PanelWidthMode.AUTO_FIT.name, state.gitPanelWidthMode)
    }

    @Test
    fun `applyWorkspaceDefaults hides root path and scrollbar`() {
        LicenseDefaults.applyWorkspace(state)

        assertTrue(state.hideProjectRootPath)
        assertTrue(state.hideProjectViewHScrollbar)
    }

    @Test
    fun `applyWorkspaceDefaults sets workspaceDefaultsApplied flag`() {
        LicenseDefaults.applyWorkspace(state)
        assertTrue(state.workspaceDefaultsApplied)
    }
}
