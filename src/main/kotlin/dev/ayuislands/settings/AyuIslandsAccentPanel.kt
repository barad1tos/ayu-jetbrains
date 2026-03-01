package dev.ayuislands.settings

import com.intellij.ui.dsl.builder.Panel
import dev.ayuislands.accent.AccentApplicator
import dev.ayuislands.accent.AYU_ACCENT_PRESETS
import dev.ayuislands.accent.AyuVariant

/** Accent color section for the Ayu Islands settings panel. */
class AyuIslandsAccentPanel : AyuIslandsSettingsPanel() {

    private var variant: AyuVariant? = null
    private var pendingAccent: String = ""
    private var storedAccent: String = ""
    private var swatchPanel: ColorSwatchPanel? = null
    var onAccentChanged: ((String) -> Unit)? = null

    override fun buildPanel(panel: Panel, variant: AyuVariant) {
        this.variant = variant
        val settings = AyuIslandsSettings.getInstance()
        storedAccent = settings.getAccentForVariant(variant)
        pendingAccent = storedAccent

        val swatch = ColorSwatchPanel(AYU_ACCENT_PRESETS) { accent ->
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
            row { cell(swatch) }
        }
    }

    fun resetToDefault(variant: AyuVariant) {
        pendingAccent = variant.defaultAccent
        swatchPanel?.selectedColor = variant.defaultAccent
        onAccentChanged?.invoke(variant.defaultAccent)
    }

    override fun isModified(): Boolean = pendingAccent != storedAccent

    override fun apply() {
        val currentVariant = variant ?: return
        if (!isModified()) return
        val settings = AyuIslandsSettings.getInstance()
        settings.setAccentForVariant(currentVariant, pendingAccent)
        AccentApplicator.apply(pendingAccent)
        storedAccent = pendingAccent
    }

    override fun reset() {
        pendingAccent = storedAccent
        swatchPanel?.selectedColor = storedAccent
    }
}
