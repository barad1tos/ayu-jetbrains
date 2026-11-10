package dev.ayuislands.licensing

import dev.ayuislands.glow.GlowAnimation
import dev.ayuislands.glow.GlowPreset
import dev.ayuislands.glow.GlowStyle
import dev.ayuislands.settings.AyuIslandsState
import dev.ayuislands.settings.PanelWidthMode

/** Existing first-activation recipes; callers own the state and qualification of the initialization. */
internal object LicenseDefaults {
    private const val PRO_DEFAULT_NEON_INTENSITY = 100
    private const val PRO_DEFAULT_NEON_WIDTH = 2

    fun enablePro(state: AyuIslandsState) {
        if (state.everBeenPro) {
            state.proDefaultsApplied = true
            return
        }
        state.glowEnabled = true
        state.glowStyle = GlowStyle.SHARP_NEON.name
        state.glowPreset = GlowPreset.CUSTOM.name
        state.sharpNeonIntensity = PRO_DEFAULT_NEON_INTENSITY
        state.sharpNeonWidth = PRO_DEFAULT_NEON_WIDTH
        state.glowAnimation = GlowAnimation.BREATHE.name
        state.glowEditor = true
        state.glowProject = true
        state.glowTerminal = true
        state.glowRun = true
        state.glowDebug = true
        state.glowGit = true
        state.glowServices = true
        state.glowFocusRing = true
        state.everBeenPro = true
        state.proDefaultsApplied = true
    }

    fun applyWorkspace(state: AyuIslandsState) {
        state.projectPanelWidthMode = PanelWidthMode.AUTO_FIT.name
        state.commitPanelWidthMode = PanelWidthMode.AUTO_FIT.name
        state.gitPanelWidthMode = PanelWidthMode.AUTO_FIT.name
        state.hideProjectRootPath = true
        state.hideProjectViewHScrollbar = true
        state.workspaceDefaultsApplied = true
    }
}
