package dev.ayuislands.studio.persistence

import dev.ayuislands.accent.AccentElementId
import dev.ayuislands.accent.AccentGroup
import dev.ayuislands.accent.AccentHex
import dev.ayuislands.accent.TintIntensity
import dev.ayuislands.glow.GlowIslandIds
import dev.ayuislands.glow.GlowStyle
import dev.ayuislands.settings.GlowSettings
import dev.ayuislands.studio.model.AppearanceSnapshot
import dev.ayuislands.studio.model.StudioGlowFields
import dev.ayuislands.studio.model.SystemAccentPolicy
import dev.ayuislands.studio.model.studioVcsFields
import dev.ayuislands.studio.session.StudioAccentPolicy
import dev.ayuislands.studio.session.StudioGlowProjection
import dev.ayuislands.vcs.VcsColorCategory
import dev.ayuislands.vcs.VcsColorPreset
import dev.ayuislands.vcs.VcsColorRules
import dev.ayuislands.vcs.VcsIntensity
import java.util.Collections

/** Legacy-named immutable reads; effective helpers never overwrite the stored user choices. */
internal class AyuSettingsView(
    private val values: LegacyValues,
    private val appearance: AppearanceSnapshot,
    private val systemPolicy: SystemAccentPolicy,
) {
    val mirageAccent: String? = values.text("mirageAccent")
    val darkAccent: String? = values.text("darkAccent")
    val lightAccent: String? = values.text("lightAccent")
    val externalThemeEnhancementsEnabled: Boolean = values.boolean("externalThemeEnhancementsEnabled")
    val externalThemeQuickSwitcherEnabled: Boolean = values.boolean("externalThemeQuickSwitcherEnabled")
    val externalThemeGlowEnabled: Boolean = values.boolean("externalThemeGlowEnabled")
    val externalThemeCodeGlanceProEnabled: Boolean = values.boolean("externalThemeCodeGlanceProEnabled")
    val externalThemeIndentRainbowEnabled: Boolean = values.boolean("externalThemeIndentRainbowEnabled")
    val externalThemeChromeTintEnabled: Boolean = values.boolean("externalThemeChromeTintEnabled")
    val externalThemeAccentOverrideEnabled: Boolean = values.boolean("externalThemeAccentOverrideEnabled")
    val externalThemeAccentSource: String? = values.text("externalThemeAccentSource")
    val externalThemeAccent: String? = values.text("externalThemeAccent")
    val followSystemAccent: Boolean = values.boolean("followSystemAccent")
    val followSystemAppearance: Boolean = values.boolean("followSystemAppearance")
    val lastDarkAppearanceTheme: String? = values.text("lastDarkAppearanceTheme")
    val lastLightAppearanceTheme: String? = values.text("lastLightAppearanceTheme")
    val trialExpiredNotified: Boolean = values.boolean("trialExpiredNotified")
    val trialExpiryWarningShown: Boolean = values.boolean("trialExpiryWarningShown")
    val trialExpiry3DayWarningShown: Boolean = values.boolean("trialExpiry3DayWarningShown")
    val proDefaultsApplied: Boolean = values.boolean("proDefaultsApplied")
    val everBeenPro: Boolean = values.boolean("everBeenPro")
    val lastKnownLicensedMs: Long = values.long("lastKnownLicensedMs")
    val lastConfirmedEntitlement: String? = values.text("lastConfirmedEntitlement")
    val workspaceDefaultsApplied: Boolean = values.boolean("workspaceDefaultsApplied")
    val trialWelcomeShown: Boolean = values.boolean("trialWelcomeShown")
    val freeOnboardingShown: Boolean = values.boolean("freeOnboardingShown")
    val premiumOnboardingShown: Boolean = values.boolean("premiumOnboardingShown")
    val lastAppliedAccentHex: String? = values.text("lastAppliedAccentHex")
    val lastApplyOk: Boolean = values.boolean("lastApplyOk")
    val inlayHints: Boolean = values.boolean("inlayHints")
    val caretRow: Boolean = values.boolean("caretRow")
    val progressBar: Boolean = values.boolean("progressBar")
    val scrollbar: Boolean = values.boolean("scrollbar")
    val links: Boolean = values.boolean("links")
    val bracketMatch: Boolean = values.boolean("bracketMatch")
    val searchResults: Boolean = values.boolean("searchResults")
    val matchingTag: Boolean = values.boolean("matchingTag")
    val glowEnabled: Boolean = values.boolean("glowEnabled")
    val glowShape: String? = values.text("glowShape")
    val glowPreset: String? = values.text("glowPreset")
    val glowStyle: String? = values.text("glowStyle")
    val softIntensity: Int = values.integer("softIntensity")
    val sharpNeonIntensity: Int = values.integer("sharpNeonIntensity")
    val gradientIntensity: Int = values.integer("gradientIntensity")
    val softWidth: Int = values.integer("softWidth")
    val sharpNeonWidth: Int = values.integer("sharpNeonWidth")
    val gradientWidth: Int = values.integer("gradientWidth")
    val glowAnimation: String? = values.text("glowAnimation")
    val waveformDirection: String? = values.text("waveformDirection")
    val waveformBaseline: String? = values.text("waveformBaseline")
    val waveformTraceDensity: Int = values.integer("waveformTraceDensity")
    val waveformTraceLength: Int = values.integer("waveformTraceLength")
    val waveformAmplitude: Int = values.integer("waveformAmplitude")
    val waveformIntensity: Int = values.integer("waveformIntensity")
    val waveformLoopSeconds: Float = values.float("waveformLoopSeconds")
    val glowEditor: Boolean = values.boolean("glowEditor")
    val glowProject: Boolean = values.boolean("glowProject")
    val glowTerminal: Boolean = values.boolean("glowTerminal")
    val glowRun: Boolean = values.boolean("glowRun")
    val glowDebug: Boolean = values.boolean("glowDebug")
    val glowGit: Boolean = values.boolean("glowGit")
    val glowServices: Boolean = values.boolean("glowServices")
    val glowTabMode: String? = values.text("glowTabMode")
    val tabUnderlineHeight: Int = values.integer("tabUnderlineHeight")
    val tabUnderlineGlowSync: Boolean = values.boolean("tabUnderlineGlowSync")
    val glowFocusRing: Boolean = values.boolean("glowFocusRing")
    val glowEditorPlacement: String? = values.text("glowEditorPlacement")
    val glowToolWindowPlacement: String? = values.text("glowToolWindowPlacement")
    val cgpIntegrationEnabled: Boolean = values.boolean("cgpIntegrationEnabled")
    val isCgpOwnershipMigrated: Boolean = values.boolean("isCgpOwnershipMigrated")
    val cgpOwnership: String? = values.text("cgpOwnership")
    val cgpBaseColor: String? = values.text("cgpBaseColor")
    val cgpBaseBorder: String? = values.text("cgpBaseBorder")
    val cgpBaseThickness: Int = values.integer("cgpBaseThickness")
    val cgpAppliedColor: String? = values.text("cgpAppliedColor")
    val cgpAppliedBorder: String? = values.text("cgpAppliedBorder")
    val cgpAppliedThickness: Int = values.integer("cgpAppliedThickness")
    val irIntegrationEnabled: Boolean = values.boolean("irIntegrationEnabled")
    val isIrOwnershipMigrated: Boolean = values.boolean("isIrOwnershipMigrated")
    val irOwnership: String? = values.text("irOwnership")
    val irBaseType: String? = values.text("irBaseType")
    val irBasePalette: String? = values.text("irBasePalette")
    val irBaseColorCount: Int = values.integer("irBaseColorCount")
    val irAppliedType: String? = values.text("irAppliedType")
    val irAppliedPalette: String? = values.text("irAppliedPalette")
    val irAppliedColorCount: Int = values.integer("irAppliedColorCount")
    val syncEditorScheme: Boolean = values.boolean("syncEditorScheme")
    val indentPresetName: String? = values.text("indentPresetName")
    val indentCustomAlpha: Int = values.integer("indentCustomAlpha")
    val bracketScopeEnabled: Boolean = values.boolean("bracketScopeEnabled")
    val irErrorHighlightEnabled: Boolean = values.boolean("irErrorHighlightEnabled")
    val irFailedVersion: String? = values.text("irFailedVersion")
    val ignorePluginSyntaxColorsEnabled: Boolean = values.boolean("ignorePluginSyntaxColorsEnabled")
    val hideProjectRootPath: Boolean = values.boolean("hideProjectRootPath")
    val hideProjectViewHScrollbar: Boolean = values.boolean("hideProjectViewHScrollbar")
    val hasRootPathLease: Boolean = values.boolean("hasRootPathLease")
    val wasRootPathShown: Boolean = values.boolean("wasRootPathShown")
    val wasRootPathChanged: Boolean = values.boolean("wasRootPathChanged")
    val hideEditorVScrollbar: Boolean = values.boolean("hideEditorVScrollbar")
    val hideEditorHScrollbar: Boolean = values.boolean("hideEditorHScrollbar")
    val autoFitProjectPanelWidth: Boolean = values.boolean("autoFitProjectPanelWidth")
    val autoFitMaxWidth: Int = values.integer("autoFitMaxWidth")
    val autoFitCommitPanelWidth: Boolean = values.boolean("autoFitCommitPanelWidth")
    val autoFitCommitMaxWidth: Int = values.integer("autoFitCommitMaxWidth")
    val projectPanelWidthMode: String? = values.text("projectPanelWidthMode")
    val projectPanelAutoFitMinWidth: Int = values.integer("projectPanelAutoFitMinWidth")
    val projectPanelFixedWidth: Int = values.integer("projectPanelFixedWidth")
    val commitPanelWidthMode: String? = values.text("commitPanelWidthMode")
    val commitPanelAutoFitMinWidth: Int = values.integer("commitPanelAutoFitMinWidth")
    val commitPanelFixedWidth: Int = values.integer("commitPanelFixedWidth")
    val commitPanelPathDisplayMode: String? = values.text("commitPanelPathDisplayMode")
    val commitPanelPathMinHiddenLevels: Int = values.integer("commitPanelPathMinHiddenLevels")
    val commitPanelPathMaxHiddenLevels: Int = values.integer("commitPanelPathMaxHiddenLevels")
    val gitPanelWidthMode: String? = values.text("gitPanelWidthMode")
    val gitPanelAutoFitMaxWidth: Int = values.integer("gitPanelAutoFitMaxWidth")
    val gitPanelAutoFitMinWidth: Int = values.integer("gitPanelAutoFitMinWidth")
    val gitPanelFixedWidth: Int = values.integer("gitPanelFixedWidth")
    val fontPresetEnabled: Boolean = values.boolean("fontPresetEnabled")
    val fontPresetName: String? = values.text("fontPresetName")
    val fontApplyToConsole: Boolean = values.boolean("fontApplyToConsole")
    val fontInstallTerminal: String? = values.text("fontInstallTerminal")
    val fontPresetCustomizations: Map<String, String> = values.stringMap("fontPresetCustomizations")
    val fontOwnershipVersion: Int = values.integer("fontOwnershipVersion")
    val fontOwnershipSnapshots: Map<String, String> = values.stringMap("fontOwnershipSnapshots")
    val installedFonts: Set<String> = values.stringSet("installedFonts")
    val installedFontsSeeded: Boolean = values.boolean("installedFontsSeeded")
    val installedFontFiles: Map<String, String> = values.stringMap("installedFontFiles")
    val explicitlyUninstalledFonts: Set<String> = values.stringSet("explicitlyUninstalledFonts")
    val quickSwitcherWidgetEnabled: Boolean = values.boolean("quickSwitcherWidgetEnabled")
    val accentRotationEnabled: Boolean = values.boolean("accentRotationEnabled")
    val projectIconAccentEnabled: Boolean = values.boolean("projectIconAccentEnabled")
    val acknowledgedSettingsBadges: Set<String> = values.stringSet("acknowledgedSettingsBadges")
    val settingsBadgeDeadlines: Map<String, String> = values.stringMap("settingsBadgeDeadlines")
    val settingsBadgesExpireAtMs: Long = values.long("settingsBadgesExpireAtMs")
    val accentRotationMode: String? = values.text("accentRotationMode")
    val accentRotationIntervalHours: Int = values.integer("accentRotationIntervalHours")
    val accentRotationLastSwitchMs: Long = values.long("accentRotationLastSwitchMs")
    val accentRotationPresetIndex: Int = values.integer("accentRotationPresetIndex")
    val lastShuffleColor: String? = values.text("lastShuffleColor")
    val lastSeenVersion: String? = values.text("lastSeenVersion")
    val lastWhatsNewShownVersion: String? = values.text("lastWhatsNewShownVersion")
    val settingsSelectedTab: Int = values.integer("settingsSelectedTab")
    val workspaceProjectViewExpanded: Boolean = values.boolean("workspaceProjectViewExpanded")
    val workspaceCommitPanelExpanded: Boolean = values.boolean("workspaceCommitPanelExpanded")
    val workspaceEditorExpanded: Boolean = values.boolean("workspaceEditorExpanded")
    val workspaceGitPanelExpanded: Boolean = values.boolean("workspaceGitPanelExpanded")
    val systemGroupExpanded: Boolean = values.boolean("systemGroupExpanded")
    val overridesGroupExpanded: Boolean = values.boolean("overridesGroupExpanded")
    val accentRotationGroupExpanded: Boolean = values.boolean("accentRotationGroupExpanded")
    val accentElementsGroupExpanded: Boolean = values.boolean("accentElementsGroupExpanded")
    val forceOverrides: Set<String> = values.stringSet("forceOverrides")
    val chromeTintingEnabled: Boolean = values.boolean("chromeTintingEnabled")
    val chromeStatusBar: Boolean = values.boolean("chromeStatusBar")
    val chromeMainToolbar: Boolean = values.boolean("chromeMainToolbar")
    val chromeToolWindowStripe: Boolean = values.boolean("chromeToolWindowStripe")
    val chromeNavBar: Boolean = values.boolean("chromeNavBar")
    val chromePanelBorder: Boolean = values.boolean("chromePanelBorder")
    val chromeTintIntensity: Int = values.integer("chromeTintIntensity")
    val chromeTintingGroupExpanded: Boolean = values.boolean("chromeTintingGroupExpanded")
    val vcsColorEnabled: Boolean = values.boolean("vcsColorEnabled")
    val vcsDiffPreset: String? = values.text("vcsDiffPreset")
    val vcsMergePreset: String? = values.text("vcsMergePreset")
    val vcsBlamePreset: String? = values.text("vcsBlamePreset")
    val vcsDiffSectionExpanded: Boolean = values.boolean("vcsDiffSectionExpanded")
    val vcsMergeSectionExpanded: Boolean = values.boolean("vcsMergeSectionExpanded")
    val vcsBlameSectionExpanded: Boolean = values.boolean("vcsBlameSectionExpanded")
    val vcsDiffIntensity: Int = values.integer("vcsDiffIntensity")
    val vcsProjectViewIntensity: Int = values.integer("vcsProjectViewIntensity")
    val vcsGutterIntensity: Int = values.integer("vcsGutterIntensity")
    val vcsConflictMarkerIntensity: Int = values.integer("vcsConflictMarkerIntensity")
    val vcsMerge3WayIntensity: Int = values.integer("vcsMerge3WayIntensity")
    val vcsInlineDiffIntensity: Int = values.integer("vcsInlineDiffIntensity")
    val vcsBlameIntensity: Int = values.integer("vcsBlameIntensity")
    val vcsLocalHistoryIntensity: Int = values.integer("vcsLocalHistoryIntensity")
    val vcsBranchIndicatorIntensity: Int = values.integer("vcsBranchIndicatorIntensity")
    val vcsBranchesPopupIntensity: Int = values.integer("vcsBranchesPopupIntensity")
    val vcsCommitHighlightIntensity: Int = values.integer("vcsCommitHighlightIntensity")

    fun effectiveFollowSystemAccent(): Boolean = StudioAccentPolicy.followsSystem(appearance, systemPolicy)

    fun glowSettings(): GlowSettings = StudioGlowProjection.settings(appearance)

    fun effectiveChromeTintIntensity(): TintIntensity = TintIntensity.of(chromeTintIntensity)

    fun effectiveLastAppliedAccentHex(): AccentHex? = AccentHex.of(lastAppliedAccentHex)

    fun trustedCachedAccent(): AccentHex? = effectiveLastAppliedAccentHex()?.takeIf { lastApplyOk }

    fun effectiveVcsPresetFor(category: VcsColorCategory): VcsColorPreset =
        VcsColorRules.preset(category, vcsDiffPreset, vcsMergePreset, vcsBlamePreset)

    fun effectiveVcsPerCategoryIntensities(): Map<VcsColorCategory, Int> =
        Collections.unmodifiableMap(
            studioVcsFields.entries.associate { (name, category) ->
                category to
                    values.integer(name)
            },
        )

    fun effectiveVcsIntensityFor(category: VcsColorCategory): VcsIntensity =
        VcsColorRules.intensity(
            vcsColorEnabled,
            category,
            effectiveVcsPresetFor(category),
            effectiveVcsPerCategoryIntensities().getValue(category),
        )

    fun isToggleEnabled(id: AccentElementId): Boolean =
        (id.group != AccentGroup.CHROME || chromeTintingEnabled) && values.boolean(id.settingName)

    fun hasChromeTintingSurfaceEnabled(): Boolean =
        AccentElementId.entries.any { it.group == AccentGroup.CHROME && values.boolean(it.settingName) }

    fun getIntensityForStyle(style: GlowStyle): Int =
        values.integer(StudioGlowFields.styles.single { it.style == style }.intensity)

    fun getWidthForStyle(style: GlowStyle): Int =
        values.integer(StudioGlowFields.styles.single { it.style == style }.width)

    fun isIslandEnabled(toolWindowId: String): Boolean {
        val canonical = GlowIslandIds.canonical(toolWindowId)
        val field =
            StudioGlowFields.islands.entries
                .singleOrNull { it.value == canonical }
                ?.key ?: return false
        return values.boolean(field)
    }

    fun isExternalQuickSwitcherAllowed(): Boolean = externalAllowed(externalThemeQuickSwitcherEnabled)

    fun isExternalGlowAllowed(): Boolean = externalAllowed(externalThemeGlowEnabled)

    fun isExternalCodeGlanceProAllowed(): Boolean = externalAllowed(externalThemeCodeGlanceProEnabled)

    fun isExternalIndentRainbowAllowed(): Boolean = externalAllowed(externalThemeIndentRainbowEnabled)

    fun isExternalChromeTintAllowed(): Boolean = externalAllowed(externalThemeChromeTintEnabled)

    private fun externalAllowed(enabled: Boolean): Boolean = externalThemeEnhancementsEnabled && enabled
}
