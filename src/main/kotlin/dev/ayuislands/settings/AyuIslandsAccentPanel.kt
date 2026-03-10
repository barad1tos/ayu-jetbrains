package dev.ayuislands.settings

import com.intellij.openapi.util.SystemInfo
import com.intellij.ui.ColorPicker
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.Panel
import dev.ayuislands.accent.AYU_ACCENT_PRESETS
import dev.ayuislands.accent.AccentApplicator
import dev.ayuislands.accent.AyuVariant
import dev.ayuislands.accent.SystemAccentProvider
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

    override fun buildPanel(
        panel: Panel,
        variant: AyuVariant,
    ) {
        this.variant = variant
        val settings = AyuIslandsSettings.getInstance()
        storedAccent = settings.getAccentForVariant(variant)
        pendingAccent = storedAccent
        storedFollowSystem = settings.state.followSystemAccent
        pendingFollowSystem = storedFollowSystem

        val colorPanel =
            AccentColorPanel(
                presets = AYU_ACCENT_PRESETS,
                onPresetSelected = { accent ->
                    pendingAccent = accent.hex
                    accentPanel?.selectedPreset = accent.hex
                    onAccentChanged?.invoke(accent.hex)
                },
                onCustomTrigger = { handleCustomTrigger() },
                onReset = { handleReset() },
            )
        accentPanel = colorPanel

        applyInitialSelection(colorPanel, storedAccent)

        panel.group("Accent Color") {
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
                        if (pendingFollowSystem) {
                            SystemAccentProvider.resolve()?.let { hex ->
                                applyInitialSelection(colorPanel, hex)
                                onAccentChanged?.invoke(hex)
                            }
                        } else {
                            val manualAccent = getManualAccent(variant, settings)
                            pendingAccent = manualAccent
                            applyInitialSelection(colorPanel, manualAccent)
                            onAccentChanged?.invoke(manualAccent)
                        }
                    }
                    followSystemCheckbox = checkbox
                }
            }
            row { cell(colorPanel).resizableColumn().align(Align.FILL) }
        }

        updatePanelEnabled()
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
        }
    }

    private fun handleReset() {
        val panel = accentPanel ?: return
        pendingAccent = ""
        pendingCustomColor = null
        panel.selectedPreset = null
        panel.customColor = null
        onAccentChanged?.invoke("")
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
    }

    override fun reset() {
        pendingAccent = storedAccent
        pendingFollowSystem = storedFollowSystem
        followSystemCheckbox?.isSelected = storedFollowSystem
        accentPanel?.let { applyInitialSelection(it, storedAccent) }
        updatePanelEnabled()
    }

    companion object {
        private fun colorToHex(color: Color): String = "#%02X%02X%02X".format(color.red, color.green, color.blue)
    }
}
