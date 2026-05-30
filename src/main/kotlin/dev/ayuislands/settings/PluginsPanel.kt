package dev.ayuislands.settings

import com.intellij.openapi.observable.properties.AtomicBooleanProperty
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.Panel
import com.intellij.ui.dsl.builder.SegmentedButton
import dev.ayuislands.accent.AccentApplicator
import dev.ayuislands.accent.AyuVariant
import dev.ayuislands.accent.conflict.ConflictRegistry
import dev.ayuislands.indent.IndentPreset
import dev.ayuislands.licensing.LicenseChecker
import javax.swing.JCheckBox
import javax.swing.JLabel
import javax.swing.JSlider

/** Plugin's tab: third-party plugin integrations (CodeGlance Pro, Indent Rainbow). */
class PluginsPanel : AyuIslandsSettingsPanel {
    private var pendingEnabled: Boolean = false
    private var storedEnabled: Boolean = false
    private var pendingPreset: String = IndentPreset.AMBIENT.name
    private var storedPreset: String = IndentPreset.AMBIENT.name
    private var pendingCustomAlpha: Int = IndentPreset.DEFAULT_ALPHA
    private var storedCustomAlpha: Int = IndentPreset.DEFAULT_ALPHA
    private var pendingCodeGlanceProIntegration: Boolean = false
    private var storedCodeGlanceProIntegration: Boolean = false
    private var pendingErrorHighlight: Boolean = true
    private var storedErrorHighlight: Boolean = true

    private var variant: AyuVariant? = null
    private var enabledCheckbox: JCheckBox? = null
    private var cgpCheckbox: JCheckBox? = null
    private var errorHighlightCheckbox: JCheckBox? = null
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
        this.variant = variant
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
        irEnabled.set(pendingEnabled || !licensed)

        val cgpDetected = ConflictRegistry.isCodeGlanceProDetected()
        val irDetected = ConflictRegistry.isIndentRainbowDetected()

        if (!cgpDetected && !irDetected) {
            panel.buildNoSupportedPluginsMessage()
            return
        }

        panel.row { comment("Sync Ayu accent colors with compatible plugins.") }
        panel.premiumFeatureNotice(gate)

        if (cgpDetected) {
            panel.buildCodeGlanceProGroup(licensed)
        }

        if (irDetected) {
            panel.buildIndentRainbowGroup(licensed, irEnabled)
        }
    }

    private fun loadStored(state: AyuIslandsState) {
        storedCodeGlanceProIntegration = state.cgpIntegrationEnabled
        pendingCodeGlanceProIntegration = storedCodeGlanceProIntegration
        storedEnabled = state.irIntegrationEnabled
        pendingEnabled = storedEnabled
        storedErrorHighlight = state.irErrorHighlightEnabled
        pendingErrorHighlight = storedErrorHighlight
        storedPreset = state.indentPresetName ?: IndentPreset.AMBIENT.name
        pendingPreset = storedPreset
        storedCustomAlpha = state.indentCustomAlpha
        pendingCustomAlpha = storedCustomAlpha
    }

    private fun isCustomIndentControlsVisible(licensed: Boolean): Boolean =
        IndentPreset.fromName(pendingPreset) == IndentPreset.CUSTOM || !licensed

    private fun Panel.buildNoSupportedPluginsMessage() {
        group("Plugins") {
            row {
                comment(
                    "No supported plugins detected." +
                        " Install CodeGlance Pro or Indent Rainbow for accent color sync.",
                )
            }
        }
    }

    private fun Panel.buildCodeGlanceProGroup(licensed: Boolean) {
        group("CodeGlance Pro") {
            row {
                val checkboxCell =
                    checkBox("Sync color with CodeGlance")
                        .comment("Apply accent color to CodeGlance Pro viewport")
                checkboxCell.component.isSelected = pendingCodeGlanceProIntegration
                checkboxCell.component.isEnabled = licensed
                checkboxCell.component.addActionListener {
                    if (!licensed) return@addActionListener
                    pendingCodeGlanceProIntegration = checkboxCell.component.isSelected
                }
                cgpCheckbox = checkboxCell.component

                browserLink("Plugin page", "https://plugins.jetbrains.com/plugin/18824-codeglance-pro")
            }
        }
    }

    private fun Panel.buildIndentRainbowGroup(
        licensed: Boolean,
        irEnabled: AtomicBooleanProperty,
    ) {
        group("Indent Rainbow") {
            buildIndentRainbowEnableRow(licensed, irEnabled)
            buildIndentRainbowPresetRow(licensed, irEnabled)
            buildIndentRainbowErrorRow(licensed, irEnabled)
            buildIndentRainbowAlphaRow(licensed)
        }
    }

    private fun Panel.buildIndentRainbowEnableRow(
        licensed: Boolean,
        irEnabled: AtomicBooleanProperty,
    ) {
        row {
            val checkboxCell =
                checkBox("Sync colors with Indent Rainbow")
                    .comment("Apply Ayu color palette to Indent Rainbow indentation guides")
            checkboxCell.component.isSelected = pendingEnabled
            checkboxCell.component.isEnabled = licensed
            checkboxCell.component.addActionListener {
                if (!licensed) return@addActionListener
                pendingEnabled = checkboxCell.component.isSelected
                irEnabled.set(checkboxCell.component.isSelected)
            }
            enabledCheckbox = checkboxCell.component

            browserLink(
                "Plugin page",
                "https://plugins.jetbrains.com/plugin/13308-indent-rainbow",
            )
        }
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
            segmented.selectedItem = IndentPreset.fromName(pendingPreset)
            segmented.enabled(licensed)
            @Suppress("UnstableApiUsage")
            segmented.whenItemSelected { preset ->
                if (!suppressListeners && licensed) {
                    pendingPreset = preset.name
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
            val checkboxCell =
                checkBox("Highlight indent errors")
                    .comment("When off, lines with irregular indentation blend into the color gradient")
            checkboxCell.component.isSelected = pendingErrorHighlight
            checkboxCell.component.isEnabled = licensed
            checkboxCell.component.addActionListener {
                if (!licensed) return@addActionListener
                pendingErrorHighlight = checkboxCell.component.isSelected
            }
            errorHighlightCheckbox = checkboxCell.component
        }.visibleIf(irEnabled)
    }

    private fun Panel.buildIndentRainbowAlphaRow(licensed: Boolean) {
        row {
            label("Alpha")
            val slider = JSlider(MIN_ALPHA, MAX_ALPHA, pendingCustomAlpha)
            slider.paintTicks = true
            slider.majorTickSpacing = ALPHA_MAJOR_TICK
            slider.isEnabled = licensed
            val valueLabel = JLabel("${slider.value}")
            slider.addChangeListener {
                if (!suppressListeners && licensed) {
                    pendingCustomAlpha = slider.value
                    valueLabel.text = "${slider.value}"
                }
            }
            alphaSlider = slider
            alphaValueLabel = valueLabel
            cell(slider).resizableColumn().align(Align.FILL)
            cell(valueLabel)
        }.visibleIf(customModeVisible)
    }

    override fun isModified(): Boolean =
        pendingEnabled != storedEnabled ||
            pendingPreset != storedPreset ||
            pendingCustomAlpha != storedCustomAlpha ||
            pendingCodeGlanceProIntegration != storedCodeGlanceProIntegration ||
            pendingErrorHighlight != storedErrorHighlight

    override fun apply() {
        if (!isModified()) return
        if (!LicenseChecker.isLicensedOrGrace()) return
        val state = AyuIslandsSettings.getInstance().state

        state.irIntegrationEnabled = pendingEnabled
        state.indentPresetName = pendingPreset
        state.indentCustomAlpha = pendingCustomAlpha
        state.cgpIntegrationEnabled = pendingCodeGlanceProIntegration
        state.irErrorHighlightEnabled = pendingErrorHighlight

        storedEnabled = pendingEnabled
        storedPreset = pendingPreset
        storedCustomAlpha = pendingCustomAlpha
        storedCodeGlanceProIntegration = pendingCodeGlanceProIntegration
        storedErrorHighlight = pendingErrorHighlight

        // Route through AccentApplicator.applyForFocusedProject so per-project/per-language
        // overrides are preserved during the settings apply cycle (PluginsPanel runs after
        // AccentPanel in Configurable.apply).
        val currentVariant = variant ?: return
        AccentApplicator.applyForFocusedProject(currentVariant)
    }

    override fun reset() {
        pendingEnabled = storedEnabled
        pendingPreset = storedPreset
        pendingCustomAlpha = storedCustomAlpha
        pendingCodeGlanceProIntegration = storedCodeGlanceProIntegration
        pendingErrorHighlight = storedErrorHighlight

        suppressListeners = true
        enabledCheckbox?.isSelected = storedEnabled
        cgpCheckbox?.isSelected = storedCodeGlanceProIntegration
        errorHighlightCheckbox?.isSelected = storedErrorHighlight
        presetSegmented?.selectedItem = IndentPreset.fromName(storedPreset)
        alphaSlider?.value = storedCustomAlpha
        alphaValueLabel?.text = "$storedCustomAlpha"
        val licensed = LicenseChecker.isLicensedOrGrace()
        irEnabled.set(pendingEnabled || !licensed)
        customModeVisible.set(isCustomIndentControlsVisible(licensed))
        suppressListeners = false
    }

    companion object {
        private const val MIN_ALPHA = 0x0D
        private const val MAX_ALPHA = 0x99
        private const val ALPHA_MAJOR_TICK = 0x1A
    }
}
