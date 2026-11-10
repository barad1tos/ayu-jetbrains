package dev.ayuislands.settings

import dev.ayuislands.licensing.LicenseDefaults
import dev.ayuislands.licensing.confirmUnlicensed
import dev.ayuislands.studio.StudioFixtures
import dev.ayuislands.studio.persistence.AyuSettingsView
import dev.ayuislands.testing.SnapshotAssert.assertMatchesSnapshot
import kotlin.test.Test
import kotlin.test.assertEquals

class AyuIslandsStateSnapshotTest {
    @Test
    fun `fresh state matches snapshot`() {
        assertMatchesSnapshot("settings/fresh-state.txt", serializeState(AyuIslandsState()))
    }

    @Test
    fun `first Pro recipe matches snapshot`() {
        val state = AyuIslandsState()
        LicenseDefaults.enablePro(state)
        assertMatchesSnapshot("settings/after-enable-pro-defaults.txt", serializeState(state))
    }

    @Test
    fun `confirmed free entitlement preserves the complete settings snapshot`() {
        val state = AyuIslandsState()
        LicenseDefaults.enablePro(state)
        LicenseDefaults.applyWorkspace(state)
        val before = serializeState(state)

        val accepted = confirmUnlicensed(state)

        assertEquals(before, serializeState(accepted.settings))
    }

    @Test
    fun `first workspace recipe matches snapshot`() {
        val state = AyuIslandsState()
        LicenseDefaults.applyWorkspace(state)
        assertMatchesSnapshot("settings/after-apply-workspace-defaults.txt", serializeState(state))
    }
}

private fun serializeState(state: AyuIslandsState): String = serializeState(StudioFixtures.legacyView(state).settings)

@Suppress("LongMethod")
private fun serializeState(state: AyuSettingsView): String =
    buildString {
        appendLine("accentRotationEnabled=${state.accentRotationEnabled}")
        appendLine("accentRotationIntervalHours=${state.accentRotationIntervalHours}")
        appendLine("accentRotationLastSwitchMs=${state.accentRotationLastSwitchMs}")
        appendLine("accentRotationMode=${state.accentRotationMode}")
        appendLine("accentRotationPresetIndex=${state.accentRotationPresetIndex}")
        appendLine("autoFitCommitMaxWidth=${state.autoFitCommitMaxWidth}")
        appendLine("autoFitCommitPanelWidth=${state.autoFitCommitPanelWidth}")
        appendLine("autoFitMaxWidth=${state.autoFitMaxWidth}")
        appendLine("autoFitProjectPanelWidth=${state.autoFitProjectPanelWidth}")
        appendLine("bracketMatch=${state.bracketMatch}")
        appendLine("bracketScopeEnabled=${state.bracketScopeEnabled}")
        appendLine("caretRow=${state.caretRow}")
        appendLine("cgpAppliedBorder=${state.cgpAppliedBorder}")
        appendLine("cgpAppliedColor=${state.cgpAppliedColor}")
        appendLine("cgpAppliedThickness=${state.cgpAppliedThickness}")
        appendLine("cgpBaseBorder=${state.cgpBaseBorder}")
        appendLine("cgpBaseColor=${state.cgpBaseColor}")
        appendLine("cgpBaseThickness=${state.cgpBaseThickness}")
        appendLine("cgpIntegrationEnabled=${state.cgpIntegrationEnabled}")
        appendLine("cgpOwnership=${state.cgpOwnership}")
        appendLine("commitPanelAutoFitMinWidth=${state.commitPanelAutoFitMinWidth}")
        appendLine("commitPanelFixedWidth=${state.commitPanelFixedWidth}")
        appendLine("commitPanelWidthMode=${state.commitPanelWidthMode}")
        appendLine("darkAccent=${state.darkAccent}")
        appendLine("everBeenPro=${state.everBeenPro}")
        appendLine("followSystemAccent=${state.followSystemAccent}")
        appendLine("followSystemAppearance=${state.followSystemAppearance}")
        appendLine("fontApplyToConsole=${state.fontApplyToConsole}")
        appendLine("fontInstallTerminal=${state.fontInstallTerminal}")
        appendLine("fontPresetCustomizations=${state.fontPresetCustomizations}")
        appendLine("fontPresetEnabled=${state.fontPresetEnabled}")
        appendLine("fontPresetName=${state.fontPresetName}")
        appendLine("forceOverrides=${state.forceOverrides}")
        appendLine("freeOnboardingShown=${state.freeOnboardingShown}")
        appendLine("gitPanelAutoFitMaxWidth=${state.gitPanelAutoFitMaxWidth}")
        appendLine("gitPanelAutoFitMinWidth=${state.gitPanelAutoFitMinWidth}")
        appendLine("gitPanelFixedWidth=${state.gitPanelFixedWidth}")
        appendLine("gitPanelWidthMode=${state.gitPanelWidthMode}")
        appendLine("glowAnimation=${state.glowAnimation}")
        appendLine("glowDebug=${state.glowDebug}")
        appendLine("glowEditor=${state.glowEditor}")
        appendLine("glowEnabled=${state.glowEnabled}")
        appendLine("glowFocusRing=${state.glowFocusRing}")
        appendLine("glowGit=${state.glowGit}")
        appendLine("glowPreset=${state.glowPreset}")
        appendLine("glowProject=${state.glowProject}")
        appendLine("glowRun=${state.glowRun}")
        appendLine("glowServices=${state.glowServices}")
        appendLine("glowStyle=${state.glowStyle}")
        appendLine("glowTabMode=${state.glowTabMode}")
        appendLine("glowTerminal=${state.glowTerminal}")
        appendLine("gradientIntensity=${state.gradientIntensity}")
        appendLine("gradientWidth=${state.gradientWidth}")
        appendLine("hideEditorHScrollbar=${state.hideEditorHScrollbar}")
        appendLine("hideEditorVScrollbar=${state.hideEditorVScrollbar}")
        appendLine("hideProjectRootPath=${state.hideProjectRootPath}")
        appendLine("hideProjectViewHScrollbar=${state.hideProjectViewHScrollbar}")
        appendLine("indentCustomAlpha=${state.indentCustomAlpha}")
        appendLine("indentPresetName=${state.indentPresetName}")
        appendLine("inlayHints=${state.inlayHints}")
        appendLine("installedFonts=${state.installedFonts}")
        appendLine("installedFontsSeeded=${state.installedFontsSeeded}")
        appendLine("irErrorHighlightEnabled=${state.irErrorHighlightEnabled}")
        appendLine("irFailedVersion=${state.irFailedVersion}")
        appendLine("irIntegrationEnabled=${state.irIntegrationEnabled}")
        appendLine("irAppliedColorCount=${state.irAppliedColorCount}")
        appendLine("irAppliedPalette=${state.irAppliedPalette}")
        appendLine("irAppliedType=${state.irAppliedType}")
        appendLine("irBaseColorCount=${state.irBaseColorCount}")
        appendLine("irBasePalette=${state.irBasePalette}")
        appendLine("irBaseType=${state.irBaseType}")
        appendLine("irOwnership=${state.irOwnership}")
        appendLine("lastDarkAppearanceTheme=${state.lastDarkAppearanceTheme}")
        appendLine("lastKnownLicensedMs=${state.lastKnownLicensedMs}")
        appendLine("lastLightAppearanceTheme=${state.lastLightAppearanceTheme}")
        appendLine("lastSeenVersion=${state.lastSeenVersion}")
        appendLine("lastShuffleColor=${state.lastShuffleColor}")
        appendLine("lightAccent=${state.lightAccent}")
        appendLine("links=${state.links}")
        appendLine("matchingTag=${state.matchingTag}")
        appendLine("mirageAccent=${state.mirageAccent}")
        appendLine("premiumOnboardingShown=${state.premiumOnboardingShown}")
        appendLine("proDefaultsApplied=${state.proDefaultsApplied}")
        appendLine("progressBar=${state.progressBar}")
        appendLine("projectPanelAutoFitMinWidth=${state.projectPanelAutoFitMinWidth}")
        appendLine("projectPanelFixedWidth=${state.projectPanelFixedWidth}")
        appendLine("projectPanelWidthMode=${state.projectPanelWidthMode}")
        appendLine("scrollbar=${state.scrollbar}")
        appendLine("searchResults=${state.searchResults}")
        appendLine("settingsSelectedTab=${state.settingsSelectedTab}")
        appendLine("sharpNeonIntensity=${state.sharpNeonIntensity}")
        appendLine("sharpNeonWidth=${state.sharpNeonWidth}")
        appendLine("softIntensity=${state.softIntensity}")
        appendLine("softWidth=${state.softWidth}")
        appendLine("tabUnderlineGlowSync=${state.tabUnderlineGlowSync}")
        appendLine("tabUnderlineHeight=${state.tabUnderlineHeight}")
        appendLine("trialExpiredNotified=${state.trialExpiredNotified}")
        appendLine("trialExpiry3DayWarningShown=${state.trialExpiry3DayWarningShown}")
        appendLine("trialExpiryWarningShown=${state.trialExpiryWarningShown}")
        appendLine("trialWelcomeShown=${state.trialWelcomeShown}")
        appendLine("workspaceCommitPanelExpanded=${state.workspaceCommitPanelExpanded}")
        appendLine("workspaceDefaultsApplied=${state.workspaceDefaultsApplied}")
        appendLine("workspaceEditorExpanded=${state.workspaceEditorExpanded}")
        appendLine("workspaceGitPanelExpanded=${state.workspaceGitPanelExpanded}")
        appendLine("workspaceProjectViewExpanded=${state.workspaceProjectViewExpanded}")
    }
