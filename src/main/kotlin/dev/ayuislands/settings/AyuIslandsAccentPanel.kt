package dev.ayuislands.settings

import com.intellij.openapi.util.SystemInfo
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.dsl.builder.Panel
import dev.ayuislands.accent.AYU_ACCENT_PRESETS
import dev.ayuislands.accent.AccentApplicator
import dev.ayuislands.accent.AyuVariant
import dev.ayuislands.accent.SystemAccentProvider

/** Accent color section for the Ayu Islands settings panel. */
class AyuIslandsAccentPanel : AyuIslandsSettingsPanel {
    private var variant: AyuVariant? = null
    private var pendingAccent: String = ""
    private var storedAccent: String = ""
    private var swatchPanel: ColorSwatchPanel? = null
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

        val swatch =
            ColorSwatchPanel(AYU_ACCENT_PRESETS) { accent ->
                pendingAccent = accent.hex
                onAccentChanged?.invoke(accent.hex)
            }
        swatch.selectedColor = storedAccent
        swatchPanel = swatch

        panel.group("Accent Color") {
            row {
                comment("Choose your accent color and which UI elements use it.")
                link("Reset") {
                    pendingAccent = variant.defaultAccent
                    swatch.selectedColor = variant.defaultAccent
                    onAccentChanged?.invoke(variant.defaultAccent)
                }
            }
            if (SystemInfo.isMac) {
                row {
                    val checkbox =
                        checkBox("Follow system accent color")
                            .component
                    checkbox.isSelected = pendingFollowSystem
                    checkbox.addActionListener {
                        pendingFollowSystem = checkbox.isSelected
                        updateSwatchEnabled()
                        if (pendingFollowSystem) {
                            SystemAccentProvider.resolve()?.let { hex ->
                                swatch.selectedColor = hex
                                onAccentChanged?.invoke(hex)
                            }
                        } else {
                            val manualAccent = getManualAccent(variant, settings)
                            swatch.selectedColor = manualAccent
                            pendingAccent = manualAccent
                            onAccentChanged?.invoke(manualAccent)
                        }
                    }
                    followSystemCheckbox = checkbox
                }
            }
            row { cell(swatch) }
        }

        updateSwatchEnabled()
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

    private fun updateSwatchEnabled() {
        val following = pendingFollowSystem
        swatchPanel?.let { panel ->
            panel.components.forEach { it.isEnabled = !following }
        }
    }

    fun resetToDefault(variant: AyuVariant) {
        pendingAccent = variant.defaultAccent
        swatchPanel?.selectedColor = variant.defaultAccent
        onAccentChanged?.invoke(variant.defaultAccent)
    }

    override fun isModified(): Boolean = pendingAccent != storedAccent || pendingFollowSystem != storedFollowSystem

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
                SystemAccentProvider.resolve() ?: pendingAccent
            } else {
                settings.setAccentForVariant(currentVariant, pendingAccent)
                pendingAccent
            }

        AccentApplicator.apply(effectiveAccent)
        storedAccent = effectiveAccent
    }

    override fun reset() {
        pendingAccent = storedAccent
        pendingFollowSystem = storedFollowSystem
        swatchPanel?.selectedColor = storedAccent
        followSystemCheckbox?.isSelected = storedFollowSystem
        updateSwatchEnabled()
    }
}
