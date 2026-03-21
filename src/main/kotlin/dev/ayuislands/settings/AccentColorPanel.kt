package dev.ayuislands.settings

import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.util.ui.JBUI
import dev.ayuislands.accent.AccentColor
import java.awt.AlphaComposite
import java.awt.BasicStroke
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Cursor
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.GridLayout
import java.awt.RenderingHints
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.geom.RoundRectangle2D
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.UIManager

/**
 * Accent color selection panel with two visual groups: action links (left) + preset grid (right).
 *
 * Layout:
 * ```
 *   Lavender              [█][█][█][█][█][█]
 *   Custom…    Reset      [█][█][█][█][█][█]
 * ```
 *
 * Left column shows the shade name in accent color (row 1) and action links (row 2).
 * Right side is the 2×6 preset color grid.
 */
class AccentColorPanel(
    presets: List<AccentColor>,
    private val onPresetSelected: (AccentColor) -> Unit,
    private val onCustomTrigger: () -> Unit,
    private val onReset: () -> Unit,
) : JPanel(BorderLayout(COLUMN_GAP, 0)) {
    private val presetList: List<AccentColor> = presets

    var selectedPreset: String? = null
        set(value) {
            field = value
            repaint()
        }

    var customColor: String? = null
        set(value) {
            field = value
            repaint()
        }

    /** Hex color of the swatch that should have a hero glow border (set by rotation). */
    var heroGlowHex: String? = null
        set(value) {
            field = value
            repaint()
        }

    /** Whether hero glow is active (only during preset rotation mode). */
    var heroGlowActive: Boolean = false
        set(value) {
            field = value
            repaint()
        }

    private val presetPanels: List<PresetComponent>
    private val shadeNameLabel: ShadeNameLabel
    private val customLink: CustomLink
    private val resetLabel: ResetLabel
    private val presetGrid: JPanel

    init {
        isOpaque = false

        presetPanels = presets.map { PresetComponent(it) }
        shadeNameLabel = ShadeNameLabel()
        customLink = CustomLink()
        resetLabel = ResetLabel()

        presetGrid = JPanel(GridLayout(GRID_ROWS, GRID_COLUMNS, GRID_GAP, GRID_GAP))
        presetGrid.isOpaque = false
        for (panel in presetPanels) {
            presetGrid.add(panel)
        }

        val linksRow = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0))
        linksRow.isOpaque = false
        linksRow.add(customLink)
        linksRow.add(Box.createHorizontalStrut(LINKS_GAP))
        linksRow.add(resetLabel)

        val leftColumn = JPanel()
        leftColumn.layout = BoxLayout(leftColumn, BoxLayout.Y_AXIS)
        leftColumn.isOpaque = false
        leftColumn.add(shadeNameLabel)
        leftColumn.add(Box.createVerticalStrut(LEFT_COLUMN_GAP))
        leftColumn.add(linksRow)

        add(leftColumn, BorderLayout.WEST)
        add(presetGrid, BorderLayout.CENTER)
    }

    private fun getSelectedAccentHex(): String? = selectedPreset ?: customColor

    private fun getSelectedShadeName(): String {
        val presetHex = selectedPreset
        if (presetHex != null) {
            val preset = presetList.firstOrNull { it.hex.equals(presetHex, ignoreCase = true) }
            return preset?.name ?: presetHex
        }
        val custom = customColor
        if (custom != null) return custom.uppercase()
        return ""
    }

    private fun resolveEditorFontFamily(): String =
        try {
            EditorColorsManager
                .getInstance()
                .globalScheme
                .fontPreferences
                .fontFamily
        } catch (_: IllegalStateException) {
            Font.MONOSPACED
        }

    private inner class PresetComponent(
        private val accent: AccentColor,
    ) : JComponent() {
        init {
            preferredSize = Dimension(0, PANEL_HEIGHT)
            toolTipText = accent.name
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            addMouseListener(
                object : MouseAdapter() {
                    override fun mouseClicked(event: MouseEvent) {
                        if (!isEnabled) return
                        selectedPreset = accent.hex
                        onPresetSelected(accent)
                    }
                },
            )
        }

        override fun setEnabled(enabled: Boolean) {
            super.setEnabled(enabled)
            cursor =
                if (enabled) {
                    Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                } else {
                    Cursor.getDefaultCursor()
                }
            repaint()
        }

        override fun paintComponent(graphics: Graphics) {
            val g2 = graphics.create() as Graphics2D
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)

                val color = Color.decode(accent.hex)
                val isSelected = accent.hex.equals(selectedPreset, ignoreCase = true)
                val borderColor = Color(BORDER_RGB)

                val shape =
                    RoundRectangle2D.Float(
                        BORDER_INSET,
                        BORDER_INSET,
                        width.toFloat() - BORDER_INSET * 2,
                        height.toFloat() - BORDER_INSET * 2,
                        PANEL_ARC,
                        PANEL_ARC,
                    )

                g2.color = color
                g2.fill(shape)

                if (!isEnabled) {
                    paintDisabledOverlay(g2, shape)
                }

                if (isSelected) {
                    g2.composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, SELECTED_OVERLAY_ALPHA)
                    g2.color = borderColor
                    g2.fill(shape)
                    g2.composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 1f)
                }

                g2.color = borderColor
                g2.stroke = BasicStroke(1f)
                g2.draw(shape)
            } finally {
                g2.dispose()
            }
        }
    }

    private inner class ShadeNameLabel : JComponent() {
        init {
            preferredSize = Dimension(LEFT_COLUMN_WIDTH, PANEL_HEIGHT)
        }

        override fun paintComponent(graphics: Graphics) {
            val shadeName = getSelectedShadeName()
            if (shadeName.isEmpty()) return

            val accentHex = getSelectedAccentHex() ?: return

            val g2 = graphics.create() as Graphics2D
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)

                val accentColor = Color.decode(accentHex)
                val editorFamily = resolveEditorFontFamily()
                g2.font = Font(editorFamily, Font.ITALIC, SPECIMEN_FONT_SIZE)
                g2.color = accentColor

                val metrics = g2.fontMetrics
                val textY = (height - metrics.height) / 2 + metrics.ascent
                g2.drawString(shadeName, 0, textY)
            } finally {
                g2.dispose()
            }
        }
    }

    private abstract inner class LinkLabel(
        private val text: String,
        private val onClick: () -> Unit,
    ) : JComponent() {
        private var isHovered = false

        init {
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            addMouseListener(
                object : MouseAdapter() {
                    override fun mouseClicked(event: MouseEvent) {
                        if (!isEnabled) return
                        onClick()
                    }

                    override fun mouseEntered(event: MouseEvent) {
                        isHovered = true
                        repaint()
                    }

                    override fun mouseExited(event: MouseEvent) {
                        isHovered = false
                        repaint()
                    }
                },
            )
        }

        override fun setEnabled(enabled: Boolean) {
            super.setEnabled(enabled)
            cursor =
                if (enabled) {
                    Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                } else {
                    Cursor.getDefaultCursor()
                }
            repaint()
        }

        override fun getPreferredSize(): Dimension {
            val metrics = getFontMetrics(font.deriveFont(Font.PLAIN, LINK_FONT_SIZE))
            return Dimension(metrics.stringWidth(text) + 1, PANEL_HEIGHT)
        }

        override fun paintComponent(graphics: Graphics) {
            val g2 = graphics.create() as Graphics2D
            try {
                g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)

                val linkColor = JBUI.CurrentTheme.Link.Foreground.ENABLED
                g2.color =
                    if (isEnabled) {
                        linkColor
                    } else {
                        UIManager.getColor("Label.disabledForeground") ?: Color.GRAY
                    }
                g2.font = g2.font.deriveFont(Font.PLAIN, LINK_FONT_SIZE)

                val metrics = g2.fontMetrics
                val textY = (height - metrics.height) / 2 + metrics.ascent
                g2.drawString(text, 0, textY)

                if (isHovered && isEnabled) {
                    val lineY = textY + 1
                    g2.drawLine(0, lineY, metrics.stringWidth(text), lineY)
                }
            } finally {
                g2.dispose()
            }
        }
    }

    private inner class CustomLink : LinkLabel("Custom\u2026", onCustomTrigger)

    private inner class ResetLabel : LinkLabel("Reset", onReset)

    private fun paintDisabledOverlay(
        g2: Graphics2D,
        shape: RoundRectangle2D.Float,
    ) {
        g2.composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, DISABLED_OVERLAY_ALPHA)
        g2.color = UIManager.getColor("Panel.background") ?: background
        g2.fill(shape)
        g2.composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 1f)
    }

    companion object {
        private const val PANEL_HEIGHT = 26
        private const val PANEL_ARC = 8f
        private const val GRID_GAP = 2
        private const val LEFT_COLUMN_GAP = 4
        private const val LINKS_GAP = 12
        private const val COLUMN_GAP = 16
        private const val LEFT_COLUMN_WIDTH = 120
        private const val GRID_ROWS = 2
        private const val GRID_COLUMNS = 6
        private const val SPECIMEN_FONT_SIZE = 15
        private const val BORDER_RGB = 0x4E5A6E
        private const val BORDER_INSET = 0.5f
        private const val SELECTED_OVERLAY_ALPHA = 0.55f
        private const val DISABLED_OVERLAY_ALPHA = 0.45f
        private const val LINK_FONT_SIZE = 12f
    }
}
