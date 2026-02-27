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

    override fun buildPanel(panel: Panel, variant: AyuVariant) {
        this.variant = variant
        val settings = AyuIslandsSettings.getInstance()
        storedAccent = settings.getAccentForVariant(variant)
        pendingAccent = storedAccent

        val swatch = ColorSwatchPanel(AYU_ACCENT_PRESETS) { accent ->
            pendingAccent = accent.hex
        }
        swatch.selectedColor = storedAccent
        swatchPanel = swatch

        panel.group("Accent Color") {
            row { cell(swatch) }
            row {
                button("Reset to Default") {
                    pendingAccent = variant.defaultAccent
                    swatch.selectedColor = variant.defaultAccent
                }
            }
        }
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
