package dev.ayuislands.settings

import com.intellij.openapi.util.SystemInfo
import com.intellij.ui.ColorPicker
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.Cell
import com.intellij.ui.dsl.builder.Panel
import com.intellij.ui.dsl.builder.selected
import dev.ayuislands.accent.AYU_ACCENT_PRESETS
import dev.ayuislands.accent.AccentApplicator
import dev.ayuislands.accent.AyuVariant
import dev.ayuislands.accent.SystemAccentProvider
import dev.ayuislands.licensing.LicenseChecker
import dev.ayuislands.rotation.AccentRotationMode
import dev.ayuislands.rotation.AccentRotationService
import dev.ayuislands.rotation.ContrastAwareColorGenerator
import java.awt.Color

/** Accent color section for the Ayu Islands settings panel. */
class AyuIslandsAccentPanel : AyuIslandsSettingsPanel {
    private var variant: AyuVariant? = null
    private var pendingAccent: String = ""
    private var storedAccent: String = ""
    private var pendingCustomColor: String? = null
    private var accentPanel: AccentColorPanel? = null
    var onAccentChanged: ((String) -> Unit)? = null

    private var pendingFollowSystem: Boolean = false
    private var storedFollowSystem: Boolean = false
    private var followSystemCheckbox: JBCheckBox? = null

    private var pendingRotationEnabled: Boolean = false
    private var storedRotationEnabled: Boolean = false
    private var pendingRotationMode: String = AccentRotationMode.PRESET.name
    private var storedRotationMode: String = AccentRotationMode.PRESET.name
    private var pendingRotationInterval: Int = AyuIslandsState.DEFAULT_ROTATION_INTERVAL_HOURS
    private var storedRotationInterval: Int = AyuIslandsState.DEFAULT_ROTATION_INTERVAL_HOURS
    private var rotationEnabledCheckbox: JBCheckBox? = null

    override fun buildPanel(
        panel: Panel,
        variant: AyuVariant,
    ) {
        initializeState(variant)
        val colorPanel = createAccentColorPanel()
        applyInitialSelection(colorPanel, storedAccent)
        updateHeroGlow()
        panel.buildAccentColorGroup(colorPanel)
        panel.buildAccentRotationGroup()
        updatePanelEnabled()
    }

    private fun initializeState(variant: AyuVariant) {
        this.variant = variant
        val settings = AyuIslandsSettings.getInstance()
        storedAccent = settings.getAccentForVariant(variant)
        pendingAccent = storedAccent
        storedFollowSystem = settings.state.followSystemAccent
        pendingFollowSystem = storedFollowSystem
        storedRotationEnabled = settings.state.accentRotationEnabled
        pendingRotationEnabled = storedRotationEnabled
        storedRotationMode =
            settings.state.accentRotationMode
                ?: AccentRotationMode.PRESET.name
        pendingRotationMode = storedRotationMode
        storedRotationInterval =
            settings.state.accentRotationIntervalHours
        pendingRotationInterval = storedRotationInterval
    }

    private fun createAccentColorPanel(): AccentColorPanel {
        val colorPanel =
            AccentColorPanel(
                presets = AYU_ACCENT_PRESETS,
                onPresetSelected = { accent ->
                    pendingAccent = accent.hex
                    accentPanel?.selectedPreset = accent.hex
                    onAccentChanged?.invoke(accent.hex)
                    if (pendingRotationEnabled) {
                        pendingRotationEnabled = false
                        rotationEnabledCheckbox?.isSelected = false
                        updateHeroGlow()
                    }
                    accentPanel?.showThirteenthSwatchImmediate(accent.hex)
                    AyuIslandsSettings.getInstance().state.lastShuffleColor = null
                },
                onCustomTrigger = { handleCustomTrigger() },
                onReset = { handleReset() },
                onShuffleTrigger = {
                    val currentVariant = variant ?: return@AccentColorPanel
                    val randomHex = ContrastAwareColorGenerator.generate(currentVariant)
                    val settings = AyuIslandsSettings.getInstance()
                    settings.state.lastShuffleColor = randomHex
                    accentPanel?.showThirteenthSwatch(randomHex)
                    // Auto-apply the shuffled color immediately
                    pendingAccent = randomHex
                    pendingCustomColor = randomHex
                    accentPanel?.selectedPreset = null
                    accentPanel?.customColor = randomHex
                    // Trigger preview refresh
                    onAccentChanged?.invoke(randomHex)
                },
                onThirteenthSwatchClicked = { hex ->
                    pendingAccent = hex
                    accentPanel?.selectedPreset = null
                    accentPanel?.customColor = hex
                    pendingCustomColor = hex
                    onAccentChanged?.invoke(hex)
                    if (pendingRotationEnabled) {
                        pendingRotationEnabled = false
                        rotationEnabledCheckbox?.isSelected = false
                        updateHeroGlow()
                    }
                },
            )
        accentPanel = colorPanel

        val lastShuffle = AyuIslandsSettings.getInstance().state.lastShuffleColor
        if (lastShuffle != null) {
            colorPanel.showThirteenthSwatchImmediate(lastShuffle)
        } else if (storedAccent.isNotEmpty()) {
            colorPanel.showThirteenthSwatchImmediate(storedAccent)
        }

        return colorPanel
    }

    private fun Panel.buildAccentColorGroup(colorPanel: AccentColorPanel) {
        group("Accent Color") {
            row {
                comment("Choose your accent color. Swatches are shared across all variants.")
            }
            if (SystemInfo.isMac) {
                row {
                    val checkbox =
                        checkBox("Follow system accent color")
                            .component
                    checkbox.isSelected = pendingFollowSystem
                    checkbox.addActionListener {
                        pendingFollowSystem = checkbox.isSelected
                        updatePanelEnabled()
                        if (pendingFollowSystem && pendingRotationEnabled) {
                            pendingRotationEnabled = false
                            rotationEnabledCheckbox?.isSelected = false
                        }
                        if (pendingFollowSystem) {
                            SystemAccentProvider.resolve()?.let { hex ->
                                applyInitialSelection(colorPanel, hex)
                                onAccentChanged?.invoke(hex)
                            }
                        } else {
                            val settings =
                                AyuIslandsSettings.getInstance()
                            val manualAccent =
                                getManualAccent(
                                    variant ?: return@addActionListener,
                                    settings,
                                )
                            pendingAccent = manualAccent
                            applyInitialSelection(
                                colorPanel,
                                manualAccent,
                            )
                            onAccentChanged?.invoke(manualAccent)
                        }
                    }
                    followSystemCheckbox = checkbox
                }
            }
            row { cell(colorPanel).resizableColumn().align(Align.FILL) }
        }
    }

    private fun Panel.buildAccentRotationGroup() {
        group("Accent Rotation") {
            row {
                comment(
                    "Automatically change your accent color on a schedule.",
                )
            }
            val rotationCheckboxCell = buildRotationEnableRow()
            buildRotationModeRow(rotationCheckboxCell)
            buildRotationIntervalRow(rotationCheckboxCell)
        }
    }

    private fun Panel.buildRotationEnableRow(): Cell<JBCheckBox> {
        lateinit var cell: Cell<JBCheckBox>
        row {
            cell = checkBox("Enable accent rotation")
            val checkbox = cell.component
            checkbox.isSelected = pendingRotationEnabled
            checkbox.isEnabled =
                LicenseChecker.isLicensedOrGrace()
            checkbox.addActionListener {
                pendingRotationEnabled = checkbox.isSelected
                if (pendingRotationEnabled && pendingFollowSystem) {
                    pendingFollowSystem = false
                    followSystemCheckbox?.isSelected = false
                    updatePanelEnabled()
                }
            }
            rotationEnabledCheckbox = checkbox
            if (!LicenseChecker.isLicensedOrGrace()) {
                comment("Pro feature")
            }
        }
        return cell
    }

    private fun Panel.buildRotationModeRow(rotationCheckboxCell: Cell<JBCheckBox>) {
        row {
            label("Mode:")
            val modeCombo =
                comboBox(
                    listOf("Preset cycle", "Random color"),
                ).component
            modeCombo.selectedIndex =
                if (pendingRotationMode == AccentRotationMode.RANDOM.name) 1 else 0
            modeCombo.addActionListener {
                pendingRotationMode =
                    if (modeCombo.selectedIndex == 1) {
                        AccentRotationMode.RANDOM.name
                    } else {
                        AccentRotationMode.PRESET.name
                    }
            }
        }.visibleIf(rotationCheckboxCell.selected)
    }

    private fun Panel.buildRotationIntervalRow(rotationCheckboxCell: Cell<JBCheckBox>) {
        row {
            label("Interval:")
            val intervalCombo =
                comboBox(
                    listOf("1 hour", "3 hours", "6 hours", "12 hours", "24 hours"),
                ).component
            intervalCombo.selectedIndex =
                INTERVAL_VALUES
                    .indexOf(pendingRotationInterval)
                    .coerceAtLeast(0)
            intervalCombo.addActionListener {
                pendingRotationInterval =
                    INTERVAL_VALUES.getOrElse(intervalCombo.selectedIndex) {
                        AyuIslandsState.DEFAULT_ROTATION_INTERVAL_HOURS
                    }
            }
        }.visibleIf(rotationCheckboxCell.selected)
    }

    private fun handleCustomTrigger() {
        val panel = accentPanel ?: return
        val existingCustom = pendingCustomColor

        if (existingCustom != null && selectedPreset != null) {
            pendingAccent = existingCustom
            panel.selectedPreset = null
            panel.customColor = existingCustom
            onAccentChanged?.invoke(existingCustom)
            return
        }

        val parent = panel.topLevelAncestor ?: panel
        val chosen =
            ColorPicker.showDialog(
                parent,
                "Choose Accent Color",
                null,
                true,
                emptyList(),
                false,
            )
        if (chosen != null) {
            val hex = colorToHex(chosen)
            pendingAccent = hex
            pendingCustomColor = hex
            panel.customColor = hex
            panel.selectedPreset = null
            onAccentChanged?.invoke(hex)
            if (pendingRotationEnabled) {
                pendingRotationEnabled = false
                rotationEnabledCheckbox?.isSelected = false
                updateHeroGlow()
            }
            accentPanel?.showThirteenthSwatchImmediate(hex)
            AyuIslandsSettings.getInstance().state.lastShuffleColor = null
        }
    }

    private fun handleReset() {
        val panel = accentPanel ?: return
        pendingAccent = ""
        pendingCustomColor = null
        panel.selectedPreset = null
        panel.customColor = null
        onAccentChanged?.invoke("")
        accentPanel?.hideThirteenthSwatch()
        AyuIslandsSettings.getInstance().state.lastShuffleColor = null
    }

    private val selectedPreset: String?
        get() = accentPanel?.selectedPreset

    private fun applyInitialSelection(
        colorPanel: AccentColorPanel,
        accent: String,
    ) {
        if (accent.isEmpty()) {
            colorPanel.selectedPreset = null
            colorPanel.customColor = null
            pendingCustomColor = null
            return
        }

        val matchesPreset = AYU_ACCENT_PRESETS.any { it.hex.equals(accent, ignoreCase = true) }
        if (matchesPreset) {
            colorPanel.selectedPreset = accent
        } else {
            colorPanel.selectedPreset = null
            colorPanel.customColor = accent
            pendingCustomColor = accent
        }
    }

    private fun getManualAccent(
        variant: AyuVariant,
        settings: AyuIslandsSettings,
    ): String =
        when (variant) {
            AyuVariant.MIRAGE -> settings.state.mirageAccent ?: variant.defaultAccent
            AyuVariant.DARK -> settings.state.darkAccent ?: variant.defaultAccent
            AyuVariant.LIGHT -> settings.state.lightAccent ?: variant.defaultAccent
        }

    private fun updatePanelEnabled() {
        val following = pendingFollowSystem
        accentPanel?.let { panel ->
            panel.components.forEach { it.isEnabled = !following }
        }
    }

    fun resetToDefault() {
        pendingAccent = ""
        pendingCustomColor = null
        accentPanel?.selectedPreset = null
        accentPanel?.customColor = null
        onAccentChanged?.invoke("")
    }

    override fun isModified(): Boolean {
        if (pendingFollowSystem != storedFollowSystem) return true
        if (pendingRotationEnabled != storedRotationEnabled) return true
        if (pendingRotationMode != storedRotationMode) return true
        if (pendingRotationInterval != storedRotationInterval) return true
        if (pendingFollowSystem) return false
        return pendingAccent != storedAccent
    }

    override fun apply() {
        val currentVariant = variant ?: return
        if (!isModified()) return
        val settings = AyuIslandsSettings.getInstance()

        if (pendingFollowSystem != storedFollowSystem) {
            settings.state.followSystemAccent = pendingFollowSystem
            storedFollowSystem = pendingFollowSystem
        }

        val effectiveAccent =
            if (pendingFollowSystem) {
                settings.getAccentForVariant(currentVariant)
            } else {
                settings.setAccentForVariant(currentVariant, pendingAccent)
                pendingAccent
            }

        if (effectiveAccent.isEmpty()) {
            settings.setAccentForVariant(currentVariant, "")
            AccentApplicator.revertAll()
        } else {
            AccentApplicator.apply(effectiveAccent)
        }
        storedAccent = effectiveAccent

        val rotationChanged =
            pendingRotationEnabled != storedRotationEnabled ||
                pendingRotationMode != storedRotationMode ||
                pendingRotationInterval != storedRotationInterval

        if (rotationChanged) {
            settings.state.accentRotationEnabled = pendingRotationEnabled
            settings.state.accentRotationMode = pendingRotationMode
            settings.state.accentRotationIntervalHours = pendingRotationInterval
            storedRotationEnabled = pendingRotationEnabled
            storedRotationMode = pendingRotationMode
            storedRotationInterval = pendingRotationInterval

            val service = AccentRotationService.getInstance()
            if (pendingRotationEnabled) {
                val mode = AccentRotationMode.fromName(pendingRotationMode)
                when (mode) {
                    AccentRotationMode.RANDOM -> {
                        val rotationHex = ContrastAwareColorGenerator.generate(
                            variant ?: return,
                        )
                        settings.setAccentForVariant(currentVariant, rotationHex)
                        settings.state.accentRotationLastSwitchMs = System.currentTimeMillis()
                        AccentApplicator.apply(rotationHex)
                        storedAccent = rotationHex
                        pendingAccent = rotationHex
                        accentPanel?.selectedPreset = null
                        accentPanel?.customColor = rotationHex
                        pendingCustomColor = rotationHex
                        settings.state.lastShuffleColor = rotationHex
                        accentPanel?.showThirteenthSwatchImmediate(rotationHex)
                        onAccentChanged?.invoke(rotationHex)
                        service.startRotation()
                    }
                    AccentRotationMode.PRESET -> {
                        val currentIndex = settings.state.accentRotationPresetIndex
                        val nextIndex = (currentIndex + 1) % AYU_ACCENT_PRESETS.size
                        settings.state.accentRotationPresetIndex = nextIndex
                        val presetHex = AYU_ACCENT_PRESETS[nextIndex].hex
                        settings.setAccentForVariant(currentVariant, presetHex)
                        settings.state.accentRotationLastSwitchMs = System.currentTimeMillis()
                        AccentApplicator.apply(presetHex)
                        storedAccent = presetHex
                        pendingAccent = presetHex
                        pendingCustomColor = null
                        accentPanel?.selectedPreset = presetHex
                        accentPanel?.customColor = null
                        accentPanel?.showThirteenthSwatchImmediate(presetHex)
                        settings.state.lastShuffleColor = null
                        updateHeroGlow()
                        onAccentChanged?.invoke(presetHex)
                        service.startRotation()
                    }
                }
            } else {
                service.stopRotation()
            }
        }
    }

    override fun reset() {
        pendingAccent = storedAccent
        pendingFollowSystem = storedFollowSystem
        followSystemCheckbox?.isSelected = storedFollowSystem
        pendingRotationEnabled = storedRotationEnabled
        pendingRotationMode = storedRotationMode
        pendingRotationInterval = storedRotationInterval
        rotationEnabledCheckbox?.isSelected = storedRotationEnabled
        accentPanel?.let { applyInitialSelection(it, storedAccent) }
        updateHeroGlow()
        updatePanelEnabled()
    }

    private fun updateHeroGlow() {
        val panel = accentPanel ?: return
        val settings = AyuIslandsSettings.getInstance()
        val enabled = pendingRotationEnabled
        val isPresetMode =
            pendingRotationMode == AccentRotationMode.PRESET.name
        if (enabled && isPresetMode) {
            val index =
                settings.state.accentRotationPresetIndex
                    .coerceIn(0, AYU_ACCENT_PRESETS.size - 1)
            panel.heroGlowHex = AYU_ACCENT_PRESETS[index].hex
            panel.heroGlowActive = true
        } else {
            panel.heroGlowHex = null
            panel.heroGlowActive = false
        }
    }

    companion object {
        private const val INTERVAL_1H = 1
        private const val INTERVAL_3H = 3
        private const val INTERVAL_6H = 6
        private const val INTERVAL_12H = 12
        private const val INTERVAL_24H = 24

        private val INTERVAL_VALUES =
            listOf(
                INTERVAL_1H,
                INTERVAL_3H,
                INTERVAL_6H,
                INTERVAL_12H,
                INTERVAL_24H,
            )

        private fun colorToHex(color: Color): String = "#%02X%02X%02X".format(color.red, color.green, color.blue)
    }
}
