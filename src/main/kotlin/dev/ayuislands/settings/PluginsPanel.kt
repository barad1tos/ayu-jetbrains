@file:Suppress("DialogTitleCapitalization")

package dev.ayuislands.settings

import com.intellij.openapi.observable.properties.AtomicBooleanProperty
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.Panel
import com.intellij.ui.dsl.builder.RightGap
import com.intellij.ui.dsl.builder.Row
import com.intellij.ui.dsl.builder.SegmentedButton
import dev.ayuislands.accent.AccentApplicator
import dev.ayuislands.accent.AccentContext
import dev.ayuislands.accent.AccentDefaults
import dev.ayuislands.accent.AyuVariant
import dev.ayuislands.accent.ExternalAccentSource
import dev.ayuislands.accent.conflict.ConflictRegistry
import dev.ayuislands.glow.GlowOverlayManager
import dev.ayuislands.indent.IndentPreset
import dev.ayuislands.licensing.LicenseChecker
import dev.ayuislands.settings.mappings.AccentSwatchPickerRow
import dev.ayuislands.syntax.SyntaxIntensityService
import javax.swing.JCheckBox
import javax.swing.JComboBox
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.JSlider

/** Plugin's tab: third-party plugin integrations (CodeGlance Pro, Indent Rainbow). */
class PluginsPanel : AyuIslandsSettingsPanel {
    private var pendingSettings = PluginSettingsSnapshot()
    private var storedSettings = PluginSettingsSnapshot()

    private var enabledCheckbox: JCheckBox? = null
    private var cgpCheckbox: JCheckBox? = null
    private var errorHighlightCheckbox: JCheckBox? = null
    private var ignorePluginCheckbox: JCheckBox? = null
    private var externalThemeCheckbox: JCheckBox? = null
    private var externalQuickSwitcherCheckbox: JCheckBox? = null
    private var externalGlowCheckbox: JCheckBox? = null
    private var externalCodeGlanceProCheckbox: JCheckBox? = null
    private var externalIndentRainbowCheckbox: JCheckBox? = null
    private var externalChromeTintCheckbox: JCheckBox? = null
    private var externalAccentSourceCombo: JComboBox<ExternalAccentSource>? = null
    private var externalAccentPicker: AccentSwatchPickerRow? = null
    private var alphaSlider: JSlider? = null
    private var alphaValueLabel: JLabel? = null
    private var presetSegmented: SegmentedButton<IndentPreset>? = null
    private val irEnabled = AtomicBooleanProperty(false)
    private val customModeVisible = AtomicBooleanProperty(false)
    private var suppressListeners = false

    override fun buildPanel(
        panel: Panel,
        variant: AyuVariant,
    ) {
        val state = AyuIslandsSettings.getInstance().state
        val gate =
            PremiumFeatureGate(
                featureName = "Plugin integrations",
                lockedDescription =
                    "Plugin integrations are a Pro feature. " +
                        "Preview CodeGlance Pro and Indent Rainbow sync controls here.",
                requestMessage = "Unlock plugin integrations",
            )
        val licensed = gate.isUnlocked

        loadStored(state)
        customModeVisible.set(isCustomIndentControlsVisible(licensed))
        irEnabled.set(pendingSettings.isIndentRainbowEnabled || !licensed)

        val cgpDetected = ConflictRegistry.isCodeGlanceProDetected()
        val irDetected = ConflictRegistry.isIndentRainbowDetected()

        panel.row { comment("Tune how Ayu colors integrate with compatible plugins.") }
        panel.buildExternalThemeSupportGroup(licensed)
        panel.buildPluginIntegrationsGroup(
            gate = gate,
            licensed = licensed,
            isCodeGlanceProDetected = cgpDetected,
            isIndentRainbowDetected = irDetected,
            irEnabled = irEnabled,
        )
    }

    private fun loadStored(state: AyuIslandsState) {
        storedSettings = PluginSettingsSnapshot.fromState(state)
        pendingSettings = storedSettings
    }

    private fun isCustomIndentControlsVisible(licensed: Boolean): Boolean =
        IndentPreset.fromName(pendingSettings.indentPresetName) == IndentPreset.CUSTOM || !licensed

    private fun Panel.buildNoSupportedPluginsMessage() {
        row {
            comment(
                "Install CodeGlance Pro or Indent Rainbow for premium accent color sync.",
            )
        }
    }

    private fun Panel.buildPluginIntegrationsGroup(
        gate: PremiumFeatureGate,
        licensed: Boolean,
        isCodeGlanceProDetected: Boolean,
        isIndentRainbowDetected: Boolean,
        irEnabled: AtomicBooleanProperty,
    ) {
        group("Plugin Integrations") {
            if (isCodeGlanceProDetected || isIndentRainbowDetected) {
                premiumFeatureNotice(gate)
            }
            buildIgnorePluginRow()

            if (isCodeGlanceProDetected) {
                buildCodeGlanceProRow(licensed)
            }

            if (isIndentRainbowDetected) {
                buildIndentRainbowRows(licensed, irEnabled)
            }

            if (!isCodeGlanceProDetected && !isIndentRainbowDetected) {
                buildNoSupportedPluginsMessage()
            }
        }
    }

    private fun Panel.buildIgnorePluginRow() {
        row {
            val checkboxCell =
                checkBox(".ignore syntax colors")
                    .comment("Use Ayu colors for .ignore files")
            checkboxCell.component.isSelected = pendingSettings.isIgnorePluginSyntaxColorsEnabled
            checkboxCell.component.addActionListener {
                pendingSettings =
                    pendingSettings.copy(isIgnorePluginSyntaxColorsEnabled = checkboxCell.component.isSelected)
            }
            ignorePluginCheckbox = checkboxCell.component
        }
    }

    private fun Panel.buildExternalThemeSupportGroup(licensed: Boolean) {
        group("External Theme Support") {
            row {
                val checkboxCell =
                    checkBox("Enable Ayu enhancements on other themes")
                        .comment(
                            "Use selected Ayu features when the active IDE theme is not Ayu.",
                        )
                checkboxCell.component.isSelected = pendingSettings.isExternalThemeEnhancementsEnabled
                checkboxCell.component.addActionListener {
                    pendingSettings =
                        pendingSettings.copy(isExternalThemeEnhancementsEnabled = checkboxCell.component.isSelected)
                }
                externalThemeCheckbox = checkboxCell.component
            }

            row {
                label("Allowed features").gap(RightGap.COLUMNS)
                panel {
                    row {
                        panel {
                            row {
                                buildExternalInheritanceToggleCell(
                                    label = "Quick switcher",
                                    isSelected = ExternalInheritanceSettings::isQuickSwitcherEnabled,
                                    updateSelection = { copy(isQuickSwitcherEnabled = it) },
                                    storeCheckbox = { externalQuickSwitcherCheckbox = it },
                                )
                            }
                            row {
                                buildExternalInheritanceToggleCell(
                                    label = "CodeGlance Pro",
                                    isSelected = ExternalInheritanceSettings::isCodeGlanceProEnabled,
                                    updateSelection = { copy(isCodeGlanceProEnabled = it) },
                                    storeCheckbox = { externalCodeGlanceProCheckbox = it },
                                )
                            }
                            row {
                                buildExternalChromeTintCell(licensed)
                            }
                        }.gap(RightGap.COLUMNS)
                        panel {
                            row {
                                buildExternalInheritanceToggleCell(
                                    label = "Glow",
                                    isSelected = ExternalInheritanceSettings::isGlowEnabled,
                                    updateSelection = { copy(isGlowEnabled = it) },
                                    storeCheckbox = { externalGlowCheckbox = it },
                                )
                            }
                            row {
                                buildExternalInheritanceToggleCell(
                                    label = "Indent Rainbow",
                                    isSelected = ExternalInheritanceSettings::isIndentRainbowEnabled,
                                    updateSelection = { copy(isIndentRainbowEnabled = it) },
                                    storeCheckbox = { externalIndentRainbowCheckbox = it },
                                )
                            }
                        }
                    }
                }.align(AlignX.FILL)
            }

            val accentInheritance =
                collapsibleGroup("Accent Inheritance") {
                    row("Accent source") {
                        val combo =
                            comboBox(ExternalAccentSource.entries.toList())
                                .component
                        combo.selectedItem = ExternalAccentSource.fromName(pendingSettings.externalAccentSource)
                        combo.renderer =
                            object : SimpleListCellRenderer<ExternalAccentSource>() {
                                override fun customize(
                                    list: JList<out ExternalAccentSource>,
                                    value: ExternalAccentSource?,
                                    index: Int,
                                    selected: Boolean,
                                    hasFocus: Boolean,
                                ) {
                                    text = value?.displayName.orEmpty()
                                }
                            }
                        combo.addActionListener {
                            if (suppressListeners) return@addActionListener
                            pendingSettings =
                                pendingSettings.copy(
                                    externalAccentSource =
                                        (
                                            combo.selectedItem as? ExternalAccentSource
                                                ?: ExternalAccentSource.AUTOMATIC
                                        ).name,
                                )
                        }
                        externalAccentSourceCombo = combo
                    }
                    row {
                        comment(
                            "Automatic uses project/language pins, Material Theme accent, IDE accent, " +
                                "then External accent fallback.",
                        )
                    }
                    row("External accent") {
                        val picker =
                            AccentSwatchPickerRow { selected ->
                                pendingSettings =
                                    pendingSettings.copy(
                                        externalAccent = selected,
                                        externalAccentSource = ExternalAccentSource.MANUAL.name,
                                    )
                                externalAccentSourceCombo?.selectedItem = ExternalAccentSource.MANUAL
                            }
                        picker.selectedHex = pendingSettings.externalAccent
                        externalAccentPicker = picker
                        cell(picker)
                            .comment("Used in Manual mode and as the final Automatic fallback")
                    }
                }
            accentInheritance.expanded = false
        }
    }

    private fun Row.buildExternalInheritanceToggleCell(
        label: String,
        isSelected: (ExternalInheritanceSettings) -> Boolean,
        updateSelection: ExternalInheritanceSettings.(Boolean) -> ExternalInheritanceSettings,
        storeCheckbox: (JCheckBox) -> Unit,
    ) {
        val checkboxCell = checkBox(label)
        checkboxCell.component.isSelected = isSelected(pendingSettings.externalInheritance)
        checkboxCell.component.addActionListener {
            pendingSettings =
                pendingSettings.copy(
                    externalInheritance =
                        pendingSettings.externalInheritance.updateSelection(checkboxCell.component.isSelected),
                )
        }
        storeCheckbox(checkboxCell.component)
    }

    private fun Row.buildExternalChromeTintCell(licensed: Boolean) {
        externalChromeTintCheckbox =
            buildLicensedCheckbox(
                labelText = "Chrome tint",
                description =
                    "Tint selected chrome surfaces and the active tab underline with the Ayu accent. " +
                        "Configure surfaces on the Accent tab while an Ayu theme is active.",
                licensed = licensed,
                isSelected = pendingSettings.externalInheritance.isChromeTintEnabled,
            ) { isSelected ->
                pendingSettings =
                    pendingSettings.copy(
                        externalInheritance =
                            pendingSettings.externalInheritance.copy(isChromeTintEnabled = isSelected),
                    )
            }
        newFeatureBadge("chrome-tint-external-themes")
    }

    private fun Row.buildLicensedCheckbox(
        labelText: String,
        description: String,
        licensed: Boolean,
        isSelected: Boolean,
        onSelectionChanged: (Boolean) -> Unit,
    ): JCheckBox {
        val checkboxCell =
            checkBox(labelText)
                .comment(description)
        checkboxCell.component.isSelected = isSelected
        checkboxCell.component.isEnabled = licensed
        checkboxCell.component.addActionListener {
            if (!licensed) return@addActionListener
            onSelectionChanged(checkboxCell.component.isSelected)
        }
        return checkboxCell.component
    }

    private fun Panel.buildCodeGlanceProRow(licensed: Boolean) {
        row {
            cgpCheckbox =
                buildLicensedCheckbox(
                    labelText = "CodeGlance Pro viewport",
                    description = "Apply accent color to the CodeGlance Pro viewport",
                    licensed = licensed,
                    isSelected = pendingSettings.isCodeGlanceProEnabled,
                ) { isSelected ->
                    pendingSettings = pendingSettings.copy(isCodeGlanceProEnabled = isSelected)
                }

            browserLink("Plugin page", "https://plugins.jetbrains.com/plugin/18824-codeglance-pro")
        }
    }

    private fun Panel.buildIndentRainbowRows(
        licensed: Boolean,
        irEnabled: AtomicBooleanProperty,
    ) {
        row {
            enabledCheckbox =
                buildLicensedCheckbox(
                    labelText = "Indent Rainbow guides",
                    description = "Sync the Ayu palette with Indent Rainbow indentation guides",
                    licensed = licensed,
                    isSelected = pendingSettings.isIndentRainbowEnabled,
                ) { isSelected ->
                    pendingSettings = pendingSettings.copy(isIndentRainbowEnabled = isSelected)
                    irEnabled.set(isSelected)
                }

            browserLink(
                "Plugin page",
                "https://plugins.jetbrains.com/plugin/13308-indent-rainbow",
            )
        }

        buildIndentRainbowPresetRow(licensed, irEnabled)
        buildIndentRainbowErrorRow(licensed, irEnabled)
        buildIndentRainbowAlphaRow(licensed)
    }

    private fun Panel.buildIndentRainbowPresetRow(
        licensed: Boolean,
        irEnabled: AtomicBooleanProperty,
    ) {
        row {
            label("Preset")
            val segmented =
                segmentedButton(IndentPreset.entries) { preset ->
                    text = preset.displayName
                }
            segmented.maxButtonsCount(IndentPreset.entries.size)
            segmented.selectedItem = IndentPreset.fromName(pendingSettings.indentPresetName)
            segmented.enabled(licensed)
            @Suppress("UnstableApiUsage")
            segmented.whenItemSelected { preset ->
                if (!suppressListeners && licensed) {
                    pendingSettings = pendingSettings.copy(indentPresetName = preset.name)
                    customModeVisible.set(preset == IndentPreset.CUSTOM)
                }
            }
            presetSegmented = segmented
        }.visibleIf(irEnabled)
    }

    private fun Panel.buildIndentRainbowErrorRow(
        licensed: Boolean,
        irEnabled: AtomicBooleanProperty,
    ) {
        row {
            errorHighlightCheckbox =
                buildLicensedCheckbox(
                    labelText = "Highlight indent errors",
                    description = "When off, lines with irregular indentation blend into the color gradient",
                    licensed = licensed,
                    isSelected = pendingSettings.isErrorHighlightEnabled,
                ) { isSelected ->
                    pendingSettings = pendingSettings.copy(isErrorHighlightEnabled = isSelected)
                }
        }.visibleIf(irEnabled)
    }

    private fun Panel.buildIndentRainbowAlphaRow(licensed: Boolean) {
        row {
            label("Alpha")
            val slider = JSlider(MIN_ALPHA, MAX_ALPHA, pendingSettings.indentCustomAlpha)
            slider.paintTicks = true
            slider.majorTickSpacing = ALPHA_MAJOR_TICK
            slider.isEnabled = licensed
            val valueLabel = JLabel("${slider.value}")
            slider.addChangeListener {
                if (!suppressListeners && licensed) {
                    pendingSettings = pendingSettings.copy(indentCustomAlpha = slider.value)
                    valueLabel.text = "${slider.value}"
                }
            }
            alphaSlider = slider
            alphaValueLabel = valueLabel
            cell(slider).resizableColumn().align(Align.FILL)
            cell(valueLabel)
        }.visibleIf(customModeVisible)
    }

    override fun isModified(): Boolean = pendingSettings != storedSettings

    private fun hasExternalChanges(): Boolean = pendingSettings.hasExternalChangesFrom(storedSettings)

    private fun hasPremiumChanges(): Boolean = pendingSettings.hasPremiumChangesFrom(storedSettings)

    override fun apply() {
        if (!isModified()) return
        val state = AyuIslandsSettings.getInstance().state
        val externalChanged = hasExternalChanges()
        if (externalChanged) {
            pendingSettings.applyExternalTo(state)
            storedSettings =
                storedSettings.copy(
                    isExternalThemeEnhancementsEnabled = pendingSettings.isExternalThemeEnhancementsEnabled,
                    externalInheritance = pendingSettings.externalInheritance,
                    externalAccentSource = pendingSettings.externalAccentSource,
                    externalAccent = pendingSettings.externalAccent,
                )
        }

        val ignorePluginChanged =
            pendingSettings.isIgnorePluginSyntaxColorsEnabled != storedSettings.isIgnorePluginSyntaxColorsEnabled
        if (ignorePluginChanged) {
            state.ignorePluginSyntaxColorsEnabled = pendingSettings.isIgnorePluginSyntaxColorsEnabled
            storedSettings =
                storedSettings.copy(
                    isIgnorePluginSyntaxColorsEnabled = pendingSettings.isIgnorePluginSyntaxColorsEnabled,
                )
            SyntaxIntensityService.getInstance().reapplyForActiveLaf()
        }

        val premiumChanged = hasPremiumChanges()
        val premiumApplied =
            if (premiumChanged && LicenseChecker.isLicensedOrGrace()) {
                pendingSettings.applyPremiumTo(state)
                storedSettings =
                    storedSettings.copy(
                        isIndentRainbowEnabled = pendingSettings.isIndentRainbowEnabled,
                        indentPresetName = pendingSettings.indentPresetName,
                        indentCustomAlpha = pendingSettings.indentCustomAlpha,
                        isCodeGlanceProEnabled = pendingSettings.isCodeGlanceProEnabled,
                        isErrorHighlightEnabled = pendingSettings.isErrorHighlightEnabled,
                    )
                true
            } else {
                false
            }

        if (externalChanged || premiumApplied) {
            applyVisibleAccentSurfaces(externalChanged)
        }
    }

    private fun applyVisibleAccentSurfaces(externalChanged: Boolean) {
        val context = AccentContext.detect()
        when {
            context != null -> {
                AccentApplicator.applyForFocusedProject(context)
                if (context == AccentContext.External) {
                    GlowOverlayManager.syncGlowForAllProjects()
                }
            }

            externalChanged -> {
                AccentApplicator.revertAll()
                GlowOverlayManager.syncGlowForAllProjects()
            }
        }
    }

    override fun reset() {
        pendingSettings = storedSettings

        suppressListeners = true
        enabledCheckbox?.isSelected = storedSettings.isIndentRainbowEnabled
        cgpCheckbox?.isSelected = storedSettings.isCodeGlanceProEnabled
        errorHighlightCheckbox?.isSelected = storedSettings.isErrorHighlightEnabled
        ignorePluginCheckbox?.isSelected = storedSettings.isIgnorePluginSyntaxColorsEnabled
        externalThemeCheckbox?.isSelected = storedSettings.isExternalThemeEnhancementsEnabled
        externalQuickSwitcherCheckbox?.isSelected = storedSettings.externalInheritance.isQuickSwitcherEnabled
        externalGlowCheckbox?.isSelected = storedSettings.externalInheritance.isGlowEnabled
        externalCodeGlanceProCheckbox?.isSelected = storedSettings.externalInheritance.isCodeGlanceProEnabled
        externalIndentRainbowCheckbox?.isSelected = storedSettings.externalInheritance.isIndentRainbowEnabled
        externalChromeTintCheckbox?.isSelected = storedSettings.externalInheritance.isChromeTintEnabled
        externalAccentSourceCombo?.selectedItem = ExternalAccentSource.fromName(storedSettings.externalAccentSource)
        externalAccentPicker?.selectedHex = storedSettings.externalAccent
        presetSegmented?.selectedItem = IndentPreset.fromName(storedSettings.indentPresetName)
        alphaSlider?.value = storedSettings.indentCustomAlpha
        alphaValueLabel?.text = "${storedSettings.indentCustomAlpha}"
        val licensed = LicenseChecker.isLicensedOrGrace()
        irEnabled.set(pendingSettings.isIndentRainbowEnabled || !licensed)
        customModeVisible.set(isCustomIndentControlsVisible(licensed))
        suppressListeners = false
    }

    private data class PluginSettingsSnapshot(
        val isIndentRainbowEnabled: Boolean = false,
        val indentPresetName: String = IndentPreset.AMBIENT.name,
        val indentCustomAlpha: Int = IndentPreset.DEFAULT_ALPHA,
        val isCodeGlanceProEnabled: Boolean = false,
        val isErrorHighlightEnabled: Boolean = true,
        val isIgnorePluginSyntaxColorsEnabled: Boolean = true,
        val isExternalThemeEnhancementsEnabled: Boolean = false,
        val externalInheritance: ExternalInheritanceSettings = ExternalInheritanceSettings(),
        val externalAccentSource: String = ExternalAccentSource.AUTOMATIC.name,
        val externalAccent: String = AccentDefaults.MIRAGE_HEX,
    ) {
        fun hasExternalChangesFrom(stored: PluginSettingsSnapshot): Boolean =
            listOf(
                isExternalThemeEnhancementsEnabled != stored.isExternalThemeEnhancementsEnabled,
                externalInheritance != stored.externalInheritance,
                externalAccentSource != stored.externalAccentSource,
                externalAccent != stored.externalAccent,
            ).any { it }

        fun hasPremiumChangesFrom(stored: PluginSettingsSnapshot): Boolean =
            listOf(
                isIndentRainbowEnabled != stored.isIndentRainbowEnabled,
                indentPresetName != stored.indentPresetName,
                indentCustomAlpha != stored.indentCustomAlpha,
                isCodeGlanceProEnabled != stored.isCodeGlanceProEnabled,
                isErrorHighlightEnabled != stored.isErrorHighlightEnabled,
            ).any { it }

        fun applyExternalTo(state: AyuIslandsState) {
            state.externalThemeEnhancementsEnabled = isExternalThemeEnhancementsEnabled
            externalInheritance.applyTo(state)
            state.externalThemeAccentSource = externalAccentSource
            state.externalThemeAccent = externalAccent
        }

        fun applyPremiumTo(state: AyuIslandsState) {
            state.irIntegrationEnabled = isIndentRainbowEnabled
            state.indentPresetName = indentPresetName
            state.indentCustomAlpha = indentCustomAlpha
            state.cgpIntegrationEnabled = isCodeGlanceProEnabled
            state.irErrorHighlightEnabled = isErrorHighlightEnabled
        }

        companion object {
            fun fromState(state: AyuIslandsState): PluginSettingsSnapshot =
                PluginSettingsSnapshot(
                    isIndentRainbowEnabled = state.irIntegrationEnabled,
                    indentPresetName = state.indentPresetName ?: IndentPreset.AMBIENT.name,
                    indentCustomAlpha = state.indentCustomAlpha,
                    isCodeGlanceProEnabled = state.cgpIntegrationEnabled,
                    isErrorHighlightEnabled = state.irErrorHighlightEnabled,
                    isIgnorePluginSyntaxColorsEnabled = state.ignorePluginSyntaxColorsEnabled,
                    isExternalThemeEnhancementsEnabled = state.externalThemeEnhancementsEnabled,
                    externalInheritance = ExternalInheritanceSettings.fromState(state),
                    externalAccentSource = state.externalThemeAccentSource ?: ExternalAccentSource.AUTOMATIC.name,
                    externalAccent = state.externalThemeAccent ?: AccentDefaults.MIRAGE_HEX,
                )
        }
    }

    private data class ExternalInheritanceSettings(
        val isQuickSwitcherEnabled: Boolean = true,
        val isGlowEnabled: Boolean = false,
        val isCodeGlanceProEnabled: Boolean = true,
        val isIndentRainbowEnabled: Boolean = true,
        val isChromeTintEnabled: Boolean = false,
    ) {
        fun applyTo(state: AyuIslandsState) {
            state.externalThemeQuickSwitcherEnabled = isQuickSwitcherEnabled
            state.externalThemeGlowEnabled = isGlowEnabled
            state.externalThemeCodeGlanceProEnabled = isCodeGlanceProEnabled
            state.externalThemeIndentRainbowEnabled = isIndentRainbowEnabled
            state.externalThemeChromeTintEnabled = isChromeTintEnabled
        }

        companion object {
            fun fromState(state: AyuIslandsState): ExternalInheritanceSettings =
                ExternalInheritanceSettings(
                    isQuickSwitcherEnabled = state.externalThemeQuickSwitcherEnabled,
                    isGlowEnabled = state.externalThemeGlowEnabled,
                    isCodeGlanceProEnabled = state.externalThemeCodeGlanceProEnabled,
                    isIndentRainbowEnabled = state.externalThemeIndentRainbowEnabled,
                    isChromeTintEnabled = state.externalThemeChromeTintEnabled,
                )
        }
    }

    companion object {
        private const val MIN_ALPHA = 0x0D
        private const val MAX_ALPHA = 0x99
        private const val ALPHA_MAJOR_TICK = 0x1A
    }
}
