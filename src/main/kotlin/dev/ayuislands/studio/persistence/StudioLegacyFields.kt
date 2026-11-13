package dev.ayuislands.studio.persistence

import dev.ayuislands.settings.AyuIslandsState
import dev.ayuislands.settings.mappings.AccentMappingsState
import dev.ayuislands.studio.model.FieldOrigin
import dev.ayuislands.studio.model.LegacyFieldKey
import dev.ayuislands.studio.model.LegacyStore
import dev.ayuislands.studio.model.RawValue
import dev.ayuislands.studio.model.SourcePresence
import dev.ayuislands.studio.model.StudioField
import dev.ayuislands.studio.model.StudioFieldCatalog
import dev.ayuislands.studio.model.StudioFields
import dev.ayuislands.studio.model.StudioList
import dev.ayuislands.studio.model.matchesLegacyType
import dev.ayuislands.syntax.SyntaxIntensityBaseState
import kotlin.reflect.KProperty1

/** Typed reads of the original beans; the field catalog owns types and the beans own defaults. */
internal object StudioLegacyFields {
    fun capture(
        settings: AyuIslandsState,
        syntax: SyntaxIntensityBaseState,
        mappings: AccentMappingsState,
        origins: Map<LegacyFieldKey, FieldOrigin> = emptyMap(),
    ): StudioFields {
        val sources =
            mapOf(
                LegacyStore.SETTINGS to settingsValues(settings),
                LegacyStore.SYNTAX to syntaxValues(syntax),
                LegacyStore.MAPPINGS to mappingValues(mappings),
            )
        val records =
            sources.flatMap { (source, values) ->
                values.map { (name, value) ->
                    val key = LegacyFieldKey(source, name)
                    StudioField(key, value, origins[key] ?: FieldOrigin(SourcePresence.ABSENT))
                }
            }
        val typesByKey = StudioFieldCatalog.fields.associate { LegacyFieldKey(it.source, it.name) to it.type }
        require(records.map { it.key }.toSet() == typesByKey.keys) { "Incomplete typed legacy capture" }
        for ((key, value) in records) {
            require(value.matchesLegacyType(typesByKey.getValue(key))) {
                "Incorrect value type for ${key.source}/${key.name}"
            }
        }
        return StudioFields(StudioList(records))
    }

    private val textSettings =
        listOf(
            AyuIslandsState::mirageAccent,
            AyuIslandsState::darkAccent,
            AyuIslandsState::lightAccent,
            AyuIslandsState::accentRotationMode,
            AyuIslandsState::glowTabMode,
            AyuIslandsState::fontPresetName,
            AyuIslandsState::glowShape,
            AyuIslandsState::glowPreset,
            AyuIslandsState::glowStyle,
            AyuIslandsState::glowAnimation,
            AyuIslandsState::waveformDirection,
            AyuIslandsState::waveformBaseline,
            AyuIslandsState::glowEditorPlacement,
            AyuIslandsState::glowToolWindowPlacement,
            AyuIslandsState::vcsDiffPreset,
            AyuIslandsState::vcsMergePreset,
            AyuIslandsState::vcsBlamePreset,
            AyuIslandsState::externalThemeAccentSource,
            AyuIslandsState::externalThemeAccent,
            AyuIslandsState::indentPresetName,
            AyuIslandsState::projectPanelWidthMode,
            AyuIslandsState::commitPanelWidthMode,
            AyuIslandsState::commitPanelPathDisplayMode,
            AyuIslandsState::gitPanelWidthMode,
            AyuIslandsState::lastDarkAppearanceTheme,
            AyuIslandsState::lastLightAppearanceTheme,
            AyuIslandsState::fontInstallTerminal,
            AyuIslandsState::lastAppliedAccentHex,
            AyuIslandsState::cgpOwnership,
            AyuIslandsState::cgpBaseColor,
            AyuIslandsState::cgpBaseBorder,
            AyuIslandsState::cgpAppliedColor,
            AyuIslandsState::cgpAppliedBorder,
            AyuIslandsState::irOwnership,
            AyuIslandsState::irBaseType,
            AyuIslandsState::irBasePalette,
            AyuIslandsState::irAppliedType,
            AyuIslandsState::irAppliedPalette,
            AyuIslandsState::irFailedVersion,
            AyuIslandsState::lastConfirmedEntitlement,
            AyuIslandsState::lastShuffleColor,
            AyuIslandsState::lastSeenVersion,
            AyuIslandsState::lastWhatsNewShownVersion,
        )

    private fun settingsValues(state: AyuIslandsState): Map<String, RawValue> =
        buildMap {
            putAll(
                read(
                    state,
                    textSettings,
                    ::text,
                ),
            )
            putAll(
                read(
                    state,
                    listOf(
                        AyuIslandsState::followSystemAccent,
                        AyuIslandsState::inlayHints,
                        AyuIslandsState::caretRow,
                        AyuIslandsState::progressBar,
                        AyuIslandsState::scrollbar,
                        AyuIslandsState::links,
                        AyuIslandsState::bracketMatch,
                        AyuIslandsState::searchResults,
                        AyuIslandsState::matchingTag,
                        AyuIslandsState::bracketScopeEnabled,
                        AyuIslandsState::chromeTintingEnabled,
                        AyuIslandsState::chromeStatusBar,
                        AyuIslandsState::chromeMainToolbar,
                        AyuIslandsState::chromeToolWindowStripe,
                        AyuIslandsState::chromeNavBar,
                        AyuIslandsState::chromePanelBorder,
                        AyuIslandsState::accentRotationEnabled,
                        AyuIslandsState::tabUnderlineGlowSync,
                        AyuIslandsState::fontPresetEnabled,
                        AyuIslandsState::fontApplyToConsole,
                        AyuIslandsState::glowEnabled,
                        AyuIslandsState::glowEditor,
                        AyuIslandsState::glowProject,
                        AyuIslandsState::glowTerminal,
                        AyuIslandsState::glowRun,
                        AyuIslandsState::glowDebug,
                        AyuIslandsState::glowGit,
                        AyuIslandsState::glowServices,
                        AyuIslandsState::glowFocusRing,
                        AyuIslandsState::vcsColorEnabled,
                        AyuIslandsState::externalThemeEnhancementsEnabled,
                        AyuIslandsState::externalThemeQuickSwitcherEnabled,
                        AyuIslandsState::externalThemeGlowEnabled,
                        AyuIslandsState::externalThemeCodeGlanceProEnabled,
                        AyuIslandsState::externalThemeIndentRainbowEnabled,
                        AyuIslandsState::externalThemeChromeTintEnabled,
                        AyuIslandsState::externalThemeAccentOverrideEnabled,
                        AyuIslandsState::cgpIntegrationEnabled,
                        AyuIslandsState::irIntegrationEnabled,
                        AyuIslandsState::irErrorHighlightEnabled,
                        AyuIslandsState::ignorePluginSyntaxColorsEnabled,
                        AyuIslandsState::hideProjectRootPath,
                        AyuIslandsState::hideProjectViewHScrollbar,
                        AyuIslandsState::hideEditorVScrollbar,
                        AyuIslandsState::hideEditorHScrollbar,
                        AyuIslandsState::autoFitProjectPanelWidth,
                        AyuIslandsState::autoFitCommitPanelWidth,
                        AyuIslandsState::followSystemAppearance,
                        AyuIslandsState::syncEditorScheme,
                        AyuIslandsState::quickSwitcherWidgetEnabled,
                        AyuIslandsState::projectIconAccentEnabled,
                        AyuIslandsState::lastApplyOk,
                        AyuIslandsState::isCgpOwnershipMigrated,
                        AyuIslandsState::isIrOwnershipMigrated,
                        AyuIslandsState::hasRootPathLease,
                        AyuIslandsState::wasRootPathShown,
                        AyuIslandsState::wasRootPathChanged,
                        AyuIslandsState::everBeenPro,
                        AyuIslandsState::installedFontsSeeded,
                        AyuIslandsState::trialExpiredNotified,
                        AyuIslandsState::trialExpiryWarningShown,
                        AyuIslandsState::trialExpiry3DayWarningShown,
                        AyuIslandsState::trialWelcomeShown,
                        AyuIslandsState::freeOnboardingShown,
                        AyuIslandsState::premiumOnboardingShown,
                        AyuIslandsState::workspaceProjectViewExpanded,
                        AyuIslandsState::workspaceCommitPanelExpanded,
                        AyuIslandsState::workspaceEditorExpanded,
                        AyuIslandsState::workspaceGitPanelExpanded,
                        AyuIslandsState::systemGroupExpanded,
                        AyuIslandsState::overridesGroupExpanded,
                        AyuIslandsState::accentRotationGroupExpanded,
                        AyuIslandsState::accentElementsGroupExpanded,
                        AyuIslandsState::chromeTintingGroupExpanded,
                        AyuIslandsState::vcsDiffSectionExpanded,
                        AyuIslandsState::vcsMergeSectionExpanded,
                        AyuIslandsState::vcsBlameSectionExpanded,
                        AyuIslandsState::proDefaultsApplied,
                        AyuIslandsState::workspaceDefaultsApplied,
                    ),
                    RawValue::BooleanValue,
                ),
            )
            putAll(
                read(
                    state,
                    listOf(
                        AyuIslandsState::forceOverrides,
                        AyuIslandsState::installedFonts,
                        AyuIslandsState::explicitlyUninstalledFonts,
                        AyuIslandsState::acknowledgedSettingsBadges,
                    ),
                    ::orderedSet,
                ),
            )
            putAll(
                read(
                    state,
                    listOf(
                        AyuIslandsState::chromeTintIntensity,
                        AyuIslandsState::accentRotationIntervalHours,
                        AyuIslandsState::tabUnderlineHeight,
                        AyuIslandsState::softIntensity,
                        AyuIslandsState::sharpNeonIntensity,
                        AyuIslandsState::gradientIntensity,
                        AyuIslandsState::softWidth,
                        AyuIslandsState::sharpNeonWidth,
                        AyuIslandsState::gradientWidth,
                        AyuIslandsState::waveformTraceDensity,
                        AyuIslandsState::waveformTraceLength,
                        AyuIslandsState::waveformAmplitude,
                        AyuIslandsState::waveformIntensity,
                        AyuIslandsState::vcsDiffIntensity,
                        AyuIslandsState::vcsProjectViewIntensity,
                        AyuIslandsState::vcsGutterIntensity,
                        AyuIslandsState::vcsConflictMarkerIntensity,
                        AyuIslandsState::vcsMerge3WayIntensity,
                        AyuIslandsState::vcsInlineDiffIntensity,
                        AyuIslandsState::vcsBlameIntensity,
                        AyuIslandsState::vcsLocalHistoryIntensity,
                        AyuIslandsState::vcsBranchIndicatorIntensity,
                        AyuIslandsState::vcsBranchesPopupIntensity,
                        AyuIslandsState::vcsCommitHighlightIntensity,
                        AyuIslandsState::indentCustomAlpha,
                        AyuIslandsState::autoFitMaxWidth,
                        AyuIslandsState::autoFitCommitMaxWidth,
                        AyuIslandsState::projectPanelAutoFitMinWidth,
                        AyuIslandsState::projectPanelFixedWidth,
                        AyuIslandsState::commitPanelAutoFitMinWidth,
                        AyuIslandsState::commitPanelFixedWidth,
                        AyuIslandsState::commitPanelPathMinHiddenLevels,
                        AyuIslandsState::commitPanelPathMaxHiddenLevels,
                        AyuIslandsState::gitPanelAutoFitMaxWidth,
                        AyuIslandsState::gitPanelAutoFitMinWidth,
                        AyuIslandsState::gitPanelFixedWidth,
                        AyuIslandsState::cgpBaseThickness,
                        AyuIslandsState::cgpAppliedThickness,
                        AyuIslandsState::irBaseColorCount,
                        AyuIslandsState::irAppliedColorCount,
                        AyuIslandsState::fontOwnershipVersion,
                        AyuIslandsState::accentRotationPresetIndex,
                        AyuIslandsState::settingsSelectedTab,
                    ),
                    RawValue::Integer,
                ),
            )
            putAll(
                read(
                    state,
                    listOf(
                        AyuIslandsState::fontPresetCustomizations,
                        AyuIslandsState::fontOwnershipSnapshots,
                        AyuIslandsState::installedFontFiles,
                        AyuIslandsState::settingsBadgeDeadlines,
                    ),
                    ::orderedMap,
                ),
            )
            putAll(
                read(
                    state,
                    listOf(
                        AyuIslandsState::waveformLoopSeconds,
                    ),
                    ::floatBits,
                ),
            )
            putAll(
                read(
                    state,
                    listOf(
                        AyuIslandsState::accentRotationLastSwitchMs,
                        AyuIslandsState::lastKnownLicensedMs,
                        AyuIslandsState::settingsBadgesExpireAtMs,
                    ),
                    RawValue::LongValue,
                ),
            )
        }

    private fun syntaxValues(state: SyntaxIntensityBaseState): Map<String, RawValue> =
        buildMap {
            putAll(
                read(
                    state,
                    listOf(
                        SyntaxIntensityBaseState::selectedPreset,
                        SyntaxIntensityBaseState::subordinatePreset,
                    ),
                    ::text,
                ),
            )
            putAll(
                read(
                    state,
                    listOf(
                        SyntaxIntensityBaseState::customOverrides,
                        SyntaxIntensityBaseState::customStyles,
                        SyntaxIntensityBaseState::customEmphasis,
                    ),
                    ::orderedMap,
                ),
            )
            putAll(
                read(
                    state,
                    listOf(
                        SyntaxIntensityBaseState::dimComments,
                        SyntaxIntensityBaseState::softenDocumentation,
                        SyntaxIntensityBaseState::quietOperators,
                        SyntaxIntensityBaseState::emphasizeDeclarations,
                    ),
                    RawValue::BooleanValue,
                ),
            )
            putAll(
                read(
                    state,
                    listOf(
                        SyntaxIntensityBaseState::schemaVersion,
                    ),
                    RawValue::Integer,
                ),
            )
        }

    private fun mappingValues(state: AccentMappingsState): Map<String, RawValue> =
        buildMap {
            putAll(
                read(
                    state,
                    listOf(
                        AccentMappingsState::projectAccents,
                        AccentMappingsState::languageAccents,
                        AccentMappingsState::projectDisplayNames,
                        AccentMappingsState::projectFallbackAccents,
                        AccentMappingsState::forcedProjectLanguages,
                    ),
                    ::orderedMap,
                ),
            )
            putAll(
                read(
                    state,
                    listOf(
                        AccentMappingsState::languageFallbackAccent,
                    ),
                    ::text,
                ),
            )
            putAll(
                read(
                    state,
                    listOf(
                        AccentMappingsState::dominanceThreshold,
                        AccentMappingsState::dominanceMarginRatio,
                        AccentMappingsState::dominanceFloor,
                        AccentMappingsState::tiebreakMinShare,
                    ),
                    ::floatBits,
                ),
            )
        }

    private fun <Bean, Value> read(
        bean: Bean,
        properties: List<KProperty1<Bean, Value>>,
        convert: (Value) -> RawValue,
    ): Map<String, RawValue> = properties.associate { it.name to convert(it.get(bean)) }

    private fun text(value: String?): RawValue = value?.let(RawValue::Text) ?: RawValue.Null

    private fun floatBits(value: Float): RawValue = RawValue.FloatBits(value.toRawBits())

    private fun orderedMap(value: Map<String, String>): RawValue =
        RawValue.OrderedMap(
            StudioList(value.map { (key, item) -> RawValue.Entry(key, RawValue.Text(item)) }),
        )

    private fun orderedSet(value: Set<String>): RawValue = RawValue.OrderedSet(StudioList(value.toList()))
}
