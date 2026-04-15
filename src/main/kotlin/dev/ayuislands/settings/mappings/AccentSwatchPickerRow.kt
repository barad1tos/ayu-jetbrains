package dev.ayuislands.settings.mappings

import com.intellij.ui.ColorPicker
import com.intellij.ui.components.ActionLink
import com.intellij.util.ui.JBUI
import dev.ayuislands.accent.AYU_ACCENT_PRESETS
import dev.ayuislands.accent.AccentColor
import java.awt.Color
import java.awt.Cursor
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.geom.RoundRectangle2D
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.UIManager

/**
 * Compact horizontal strip of preset swatches + "Custom…" link, used inside
 * the Add/Edit mapping dialogs. A heavier alternative to the full grid+shuffle
 * [AccentColorPanel][dev.ayuislands.settings.AccentColorPanel] when the context
 * is modal and there's no reason to offer shuffle or hero animations.
 *
 * [selectedHex] reflects the current selection (`null` when nothing chosen).
 * Changing it from outside will repaint; user clicks mutate it and fire [onSelected].
 */
class AccentSwatchPickerRow(
    private val onSelected: (String) -> Unit,
) : JPanel(FlowLayout(FlowLayout.LEADING, GAP, 0)) {
    var selectedHex: String? = null
        set(value) {
            field = value
            repaint()
        }

    init {
        isOpaque = false
        for (preset in AYU_ACCENT_PRESETS) {
            add(SwatchButton(preset))
        }
        val customLink =
            ActionLink("Custom…") {
                val chosen =
                    ColorPicker.showDialog(
                        topLevelAncestor ?: this,
                        "Choose Accent Color",
                        selectedHex?.let { RoundedSwatchRenderer.safeDecodeColor(it) },
                        true,
                        emptyList(),
                        false,
                    )
                if (chosen != null) {
                    val hex = "#%02X%02X%02X".format(chosen.red, chosen.green, chosen.blue)
                    selectedHex = hex
                    onSelected(hex)
                }
            }
        customLink.border = JBUI.Borders.emptyLeft(GAP)
        add(customLink)
    }

    private inner class SwatchButton(
        private val preset: AccentColor,
    ) : JComponent() {
        init {
            preferredSize = Dimension(SWATCH_SIZE + SELECTION_OUTLINE * 2, SWATCH_SIZE + SELECTION_OUTLINE * 2)
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            toolTipText = "${preset.name} (${preset.hex})"
            addMouseListener(
                object : MouseAdapter() {
                    override fun mouseClicked(e: MouseEvent) {
                        selectedHex = preset.hex
                        onSelected(preset.hex)
                    }
                },
            )
        }

        override fun paintComponent(g: Graphics) {
            val graphics = g.create() as Graphics2D
            try {
                graphics.setRenderingHint(
                    RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON,
                )
                val swatchX = SELECTION_OUTLINE.toFloat()
                val swatchY = SELECTION_OUTLINE.toFloat()
                val fill = RoundedSwatchRenderer.safeDecodeColor(preset.hex) ?: return
                val shape =
                    RoundRectangle2D.Float(
                        swatchX,
                        swatchY,
                        SWATCH_SIZE.toFloat(),
                        SWATCH_SIZE.toFloat(),
                        SWATCH_ARC,
                        SWATCH_ARC,
                    )
                graphics.color = fill
                graphics.fill(shape)
                val borderColor = UIManager.getColor("Separator.separatorColor") ?: Color(BORDER_RGB)
                graphics.color = borderColor
                graphics.draw(shape)

                if (selectedHex?.equals(preset.hex, ignoreCase = true) == true) {
                    graphics.color = UIManager.getColor("Focus.borderColor") ?: fill
                    graphics.stroke = java.awt.BasicStroke(SELECTION_STROKE)
                    graphics.draw(
                        RoundRectangle2D.Float(
                            SELECTION_BORDER_OFFSET,
                            SELECTION_BORDER_OFFSET,
                            (SWATCH_SIZE + SELECTION_OUTLINE * 2 - 1).toFloat(),
                            (SWATCH_SIZE + SELECTION_OUTLINE * 2 - 1).toFloat(),
                            SWATCH_ARC + SELECTION_ARC_EXTRA,
                            SWATCH_ARC + SELECTION_ARC_EXTRA,
                        ),
                    )
                }
            } finally {
                graphics.dispose()
            }
        }
    }

    companion object {
        private const val SWATCH_SIZE = 18
        private const val SWATCH_ARC = 5f
        private const val SELECTION_OUTLINE = 3
        private const val SELECTION_STROKE = 1.5f
        private const val SELECTION_BORDER_OFFSET = 0.5f
        private const val SELECTION_ARC_EXTRA = 2f
        private const val GAP = 4
        private const val BORDER_RGB = 0x4E5A6E
    }
}
