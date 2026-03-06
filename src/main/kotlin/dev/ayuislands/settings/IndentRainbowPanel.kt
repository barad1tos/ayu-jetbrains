@file:Suppress("DialogTitleCapitalization")

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

/** Indent Rainbow integration settings — collapsible panel with opacity presets. */
class IndentRainbowPanel : AyuIslandsSettingsPanel {
    private var pendingEnabled: Boolean = false
    private var storedEnabled: Boolean = false
    private var pendingPreset: String = IndentPreset.AMBIENT.name
    private var storedPreset: String = IndentPreset.AMBIENT.name
    private var pendingCustomAlpha: Int = DEFAULT_ALPHA
    private var storedCustomAlpha: Int = DEFAULT_ALPHA

    private var variant: AyuVariant? = null
    private var enabledCheckbox: JCheckBox? = null
    private var alphaSlider: JSlider? = null
    private var alphaValueLabel: JLabel? = null
    private var presetSegmented: SegmentedButton<IndentPreset>? = null
    private val customModeVisible = AtomicBooleanProperty(false)
    private var suppressListeners = false

    override fun buildPanel(
        panel: Panel,
        variant: AyuVariant,
    ) {
        this.variant = variant
        if (!ConflictRegistry.isIndentRainbowDetected()) return

        val state = AyuIslandsSettings.getInstance().state
        val licensed = LicenseChecker.isLicensedOrGrace()

        storedEnabled = state.irIntegrationEnabled
        pendingEnabled = storedEnabled

        storedPreset = state.indentPresetName ?: IndentPreset.AMBIENT.name
        pendingPreset = storedPreset

        storedCustomAlpha = state.indentCustomAlpha
        pendingCustomAlpha = storedCustomAlpha

        customModeVisible.set(
            IndentPreset.fromName(pendingPreset) == IndentPreset.CUSTOM,
        )

        val irEnabled = AtomicBooleanProperty(pendingEnabled)

        panel.collapsibleGroup("Indent Rainbow") {
            row {
                val cb =
                    checkBox("Sync colors with Indent Rainbow")
                        .comment("Apply Ayu color palette to Indent Rainbow indentation guides")
                cb.component.isSelected = pendingEnabled
                cb.component.isEnabled = licensed
                cb.component.addActionListener {
                    pendingEnabled = cb.component.isSelected
                    irEnabled.set(cb.component.isSelected)
                }
                enabledCheckbox = cb.component

                browserLink(
                    "Plugin page",
                    "https://plugins.jetbrains.com/plugin/13308-indent-rainbow",
                )
            }

            // Preset row (visible when IR integration enabled)
            row {
                label("Opacity")
                val segmented =
                    segmentedButton(IndentPreset.entries) { preset ->
                        text = preset.displayName
                    }
                segmented.maxButtonsCount(IndentPreset.entries.size)
                segmented.selectedItem = IndentPreset.fromName(pendingPreset)
                @Suppress("UnstableApiUsage")
                segmented.whenItemSelected { preset ->
                    if (!suppressListeners) {
                        pendingPreset = preset.name
                        customModeVisible.set(preset == IndentPreset.CUSTOM)
                    }
                }
                presetSegmented = segmented
            }.visibleIf(irEnabled)

            // Custom alpha slider (visible only in CUSTOM mode AND IR enabled)
            row {
                label("Alpha")
                val slider = JSlider(MIN_ALPHA, MAX_ALPHA, pendingCustomAlpha)
                slider.paintTicks = true
                slider.majorTickSpacing = ALPHA_MAJOR_TICK
                slider.isEnabled = licensed
                val valueLabel = JLabel("${slider.value}")
                slider.addChangeListener {
                    if (!suppressListeners) {
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
    }

    override fun isModified(): Boolean =
        pendingEnabled != storedEnabled ||
            pendingPreset != storedPreset ||
            pendingCustomAlpha != storedCustomAlpha

    override fun apply() {
        if (!isModified()) return
        val state = AyuIslandsSettings.getInstance().state

        state.irIntegrationEnabled = pendingEnabled
        state.indentPresetName = pendingPreset
        state.indentCustomAlpha = pendingCustomAlpha

        storedEnabled = pendingEnabled
        storedPreset = pendingPreset
        storedCustomAlpha = pendingCustomAlpha

        // Trigger IR sync immediately
        val currentVariant = variant ?: return
        val accentHex = AyuIslandsSettings.getInstance().getAccentForVariant(currentVariant)
        AccentApplicator.apply(accentHex)
    }

    override fun reset() {
        pendingEnabled = storedEnabled
        pendingPreset = storedPreset
        pendingCustomAlpha = storedCustomAlpha

        suppressListeners = true
        enabledCheckbox?.isSelected = storedEnabled
        presetSegmented?.selectedItem = IndentPreset.fromName(storedPreset)
        alphaSlider?.value = storedCustomAlpha
        alphaValueLabel?.text = "$storedCustomAlpha"
        customModeVisible.set(
            IndentPreset.fromName(pendingPreset) == IndentPreset.CUSTOM,
        )
        suppressListeners = false
    }

    companion object {
        private const val DEFAULT_ALPHA = 0x2E
        private const val MIN_ALPHA = 0x0D
        private const val MAX_ALPHA = 0x99
        private const val ALPHA_MAJOR_TICK = 0x1A
    }
}
