package dev.ayuislands.settings.mappings

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.ColorPicker
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.ui.JBUI
import dev.ayuislands.accent.AYU_ACCENT_PRESETS
import dev.ayuislands.accent.AyuVariant
import dev.ayuislands.rotation.ContrastAwareColorGenerator
import dev.ayuislands.settings.AccentColorPanel
import javax.swing.JComponent

/**
 * Modal color picker for the Overrides table "Edit color" action. Embeds the same
 * [AccentColorPanel] used in the main Accent tab — shade-name label + Custom link +
 * Shuffle link on the left, 4×3 preset grid + dynamic 13th swatch on the right — so
 * users see the familiar layout and can orient immediately.
 *
 * Reuses [AccentColorPanel] verbatim. Shuffle is wired through [ContrastAwareColorGenerator]
 * but scoped to this dialog — it does not pollute [AyuIslandsSettings.lastShuffleColor] the
 * way the main panel does, because this modal edits a single override, not the global state.
 */
class EditAccentColorDialog(
    parent: Project?,
    private val initialHex: String,
    private val mappingLabel: String,
) : DialogWrapper(parent, true) {
    var resultHex: String = initialHex
        private set

    private lateinit var colorPanel: AccentColorPanel

    init {
        title = "Edit Accent Color"
        colorPanel =
            AccentColorPanel(
                presets = AYU_ACCENT_PRESETS,
                onPresetSelected = { accent ->
                    resultHex = accent.hex
                    colorPanel.selectedPreset = accent.hex
                    colorPanel.customColor = null
                },
                onCustomTrigger = { handleCustomTrigger() },
                onReset = { /* Cancel button handles revert */ },
                onShuffleTrigger = { handleShuffleTrigger() },
                onThirteenthSwatchClicked = { hex ->
                    resultHex = hex
                    colorPanel.selectedPreset = null
                    colorPanel.customColor = hex
                },
            )
        // Without an explicit minimum the inner AccentColorPanel (BorderLayout + tiny
        // PresetComponent naturals) collapses the grid into near-invisible dots when the
        // modal renders. Pin a sensible floor that matches the main Settings panel's visual
        // weight — the dialog can still grow if the user drags it, but never shrinks below.
        colorPanel.preferredSize = JBUI.size(PANEL_MIN_WIDTH, PANEL_MIN_HEIGHT)
        colorPanel.minimumSize = JBUI.size(PANEL_MIN_WIDTH, PANEL_MIN_HEIGHT)
        applyInitialSelection()
        init()
    }

    private fun applyInitialSelection() {
        val preset = AYU_ACCENT_PRESETS.firstOrNull { it.hex.equals(initialHex, ignoreCase = true) }
        if (preset != null) {
            colorPanel.selectedPreset = preset.hex
        } else {
            colorPanel.customColor = initialHex
            colorPanel.showThirteenthSwatchImmediate(initialHex)
        }
    }

    private fun handleCustomTrigger() {
        val chosen =
            ColorPicker.showDialog(
                colorPanel.topLevelAncestor ?: colorPanel,
                "Choose Accent Color",
                RoundedSwatchRenderer.safeDecodeColor(resultHex),
                true,
                emptyList(),
                false,
            ) ?: return
        val hex = "#%02X%02X%02X".format(chosen.red, chosen.green, chosen.blue)
        resultHex = hex
        colorPanel.selectedPreset = null
        colorPanel.customColor = hex
        colorPanel.showThirteenthSwatchImmediate(hex)
    }

    private fun handleShuffleTrigger() {
        val variant = AyuVariant.detect() ?: return
        val randomHex = ContrastAwareColorGenerator.generate(variant)
        resultHex = randomHex
        colorPanel.selectedPreset = null
        colorPanel.customColor = randomHex
        colorPanel.showThirteenthSwatch(randomHex)
    }

    override fun createCenterPanel(): JComponent =
        panel {
            row {
                comment("Editing accent for: $mappingLabel")
            }
            row {
                cell(colorPanel)
                    .resizableColumn()
                    .align(Align.FILL)
            }
        }

    override fun doValidate(): ValidationInfo? =
        if (resultHex.isBlank()) {
            ValidationInfo("Choose an accent color.")
        } else {
            null
        }

    companion object {
        private const val PANEL_MIN_WIDTH = 460
        private const val PANEL_MIN_HEIGHT = 130
    }
}
