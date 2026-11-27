package dev.ayuislands.studio.ui

import com.intellij.util.ui.ColorIcon
import com.intellij.util.ui.JBUI
import dev.ayuislands.accent.AYU_ACCENT_PRESETS
import dev.ayuislands.accent.AccentHex
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.GridLayout
import javax.swing.ButtonGroup
import javax.swing.JButton
import javax.swing.JPanel
import javax.swing.JToggleButton

/** Native buttons keep palette selection visible to keyboard and assistive technology. */
internal class StudioAccentPalette(
    onSelect: (AccentHex) -> Unit,
    onCustom: () -> Unit,
    onShuffle: (() -> Unit)? = null,
) : JPanel() {
    private val selection = ButtonGroup()
    private val swatches =
        AYU_ACCENT_PRESETS.associate { preset ->
            val hex = AccentHex.unsafeOf(preset.hex)
            hex to
                JToggleButton(ColorIcon(JBUI.scale(SWATCH_ICON_SIZE), hex.toColor())).apply {
                    val description = "${preset.name} (${preset.hex})"
                    toolTipText = description
                    accessibleContext.accessibleName = description
                    margin = JBUI.insets(2)
                    minimumSize = Dimension(0, 0)
                    addActionListener { onSelect(hex) }
                    selection.add(this)
                }
        }
    private val custom = JButton("Custom…").apply { addActionListener { onCustom() } }
    private val shuffle = onShuffle?.let { action -> JButton("Shuffle").apply { addActionListener { action() } } }

    init {
        val spacing = StudioUiTokens.spacing()
        isOpaque = false
        layout = BorderLayout(0, spacing.fieldGap)
        add(
            JPanel(GridLayout(0, COLUMNS, spacing.paletteGap, spacing.paletteGap)).apply {
                isOpaque = false
                swatches.values.forEach { add(it) }
            },
            BorderLayout.CENTER,
        )
        add(
            JPanel(FlowLayout(FlowLayout.LEADING, spacing.fieldGap, 0)).apply {
                isOpaque = false
                add(custom)
                shuffle?.let { add(it) }
            },
            BorderLayout.SOUTH,
        )
        render(null, editable = false)
    }

    fun render(
        selected: AccentHex?,
        editable: Boolean,
    ) {
        val button = swatches.entries.firstOrNull { it.key.value.equals(selected?.value, ignoreCase = true) }?.value
        if (button == null) selection.clearSelection() else button.isSelected = true
        swatches.values.forEach { it.isEnabled = editable }
        custom.isEnabled = editable
        shuffle?.isEnabled = editable
        custom.icon = selected?.let { ColorIcon(JBUI.scale(SWATCH_ICON_SIZE), it.toColor()) }
    }

    private companion object {
        const val COLUMNS = 6
        const val SWATCH_ICON_SIZE = 18
    }
}
