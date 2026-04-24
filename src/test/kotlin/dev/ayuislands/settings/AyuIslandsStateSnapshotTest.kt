package dev.ayuislands.settings

import com.intellij.openapi.application.ApplicationManager
import dev.ayuislands.accent.AccentApplicator
import dev.ayuislands.accent.AyuVariant
import dev.ayuislands.glow.GlowOverlayManager
import dev.ayuislands.licensing.LicenseChecker
import dev.ayuislands.rotation.AccentRotationService
import dev.ayuislands.testing.SnapshotAssert.assertMatchesSnapshot
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.runs
import io.mockk.unmockkAll
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test

class AyuIslandsStateSnapshotTest {
    @BeforeTest
    fun setUp() {
        mockkObject(AyuIslandsSettings.Companion)
        mockkObject(AccentApplicator)
        mockkObject(GlowOverlayManager.Companion)
        mockkStatic(ApplicationManager::class)
        every { AccentApplicator.apply(any()) } returns true
        every { GlowOverlayManager.syncGlowForAllProjects() } just runs
        val rotationService = mockk<AccentRotationService>(relaxed = true)
        val appMock = mockk<com.intellij.openapi.application.Application>(relaxed = true)
        every { ApplicationManager.getApplication() } returns appMock
        every { appMock.invokeLater(any()) } answers { firstArg<Runnable>().run() }
        every { appMock.getService(AccentRotationService::class.java) } returns rotationService
    }

    @AfterTest
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `fresh state matches snapshot`() {
        val state = AyuIslandsState()
        assertMatchesSnapshot("settings/fresh-state.txt", serializeState(state))
    }

    @Test
    fun `after enableProDefaults matches snapshot`() {
        val state = AyuIslandsState()
        val settings = mockk<AyuIslandsSettings>(relaxed = true)
        every { settings.state } returns state
        every { AyuIslandsSettings.getInstance() } returns settings
        LicenseChecker.enableProDefaults()
        assertMatchesSnapshot("settings/after-enable-pro-defaults.txt", serializeState(state))
    }

    @Test
    fun `after revertToFreeDefaults matches snapshot`() {
        val state = AyuIslandsState()
        val settings = mockk<AyuIslandsSettings>(relaxed = true)
        every { settings.state } returns state
        every { settings.getAccentForVariant(any()) } returns "#FFCC66"
        every { AyuIslandsSettings.getInstance() } returns settings
        LicenseChecker.enableProDefaults()
        LicenseChecker.revertToFreeDefaults(AyuVariant.MIRAGE)
        assertMatchesSnapshot(
            "settings/after-revert-to-free-defaults.txt",
            serializeState(state),
        )
    }

    @Test
    fun `after applyWorkspaceDefaults matches snapshot`() {
        val state = AyuIslandsState()
        val settings = mockk<AyuIslandsSettings>(relaxed = true)
        every { settings.state } returns state
        every { AyuIslandsSettings.getInstance() } returns settings
        LicenseChecker.applyWorkspaceDefaults()
        assertMatchesSnapshot(
            "settings/after-apply-workspace-defaults.txt",
            serializeState(state),
        )
    }
}

@Suppress("LongMethod")
private fun serializeState(state: AyuIslandsState): String =
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
        appendLine("cgpIntegrationEnabled=${state.cgpIntegrationEnabled}")
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
