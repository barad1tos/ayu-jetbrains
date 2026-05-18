package dev.ayuislands.accent.toolbar.popup

import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import dev.ayuislands.accent.AyuVariant
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Cursor
import java.awt.Dimension
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.GridLayout
import java.awt.RenderingHints
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.geom.RoundRectangle2D
import javax.accessibility.AccessibleContext
import javax.accessibility.AccessibleRole
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * 3-cell horizontal pill (Mirage / Dark / Light) used as the variant row
 * inside the quick-switcher popup.
 *
 * Selected cell paints [JBUI.CurrentTheme.ActionButton.pressedBackground] +
 * `pressedBorderColor`; hovered (non-selected) cell paints
 * `JBUI.CurrentTheme.ActionButton.hoverBackground`; idle cell shows label text only
 * via [JBUI.CurrentTheme.Label.disabledForeground]. Cells iterate
 * [AyuVariant.entries] in declaration order so display order is locked at the enum
 * (MIRAGE / DARK / LIGHT).
 *
 * Each cell exposes `accessibleContext.accessibleName = "Variant: Mirage"` etc. for
 * IntelliJ's a11y bridge.
 *
 * Pattern A — mouse events run on EDT by Swing contract.
 *
 * @param initialVariant which cell paints as selected on first paint.
 * @param onSelectionChanged invoked on each cell click with the selected variant.
 */
internal class SegmentedControl(
    initialVariant: AyuVariant,
    private val onSelectionChanged: (AyuVariant) -> Unit,
) : JPanel(GridLayout(1, AyuVariant.entries.size, 0, 0)) {
    var selectedVariant: AyuVariant = initialVariant
        private set

    private val cells: List<VariantCell> =
        AyuVariant.entries.map { variant ->
            VariantCell(variant).also { add(it) }
        }

    init {
        isOpaque = false
        border = JBUI.Borders.empty()
    }

    /**
     * Programmatic selection — used by callers that drive selection from a model
     * change without a mouse click. Repaints every cell so the prior selection clears.
     */
    fun setSelectedVariant(variant: AyuVariant) {
        if (selectedVariant == variant) return
        selectedVariant = variant
        cells.forEach { it.repaint() }
    }

    private inner class VariantCell(
        private val variant: AyuVariant,
    ) : JComponent() {
        private var isHovered: Boolean = false

        init {
            isOpaque = false
            preferredSize = Dimension(JBUI.scale(CELL_WIDTH_PX), JBUI.scale(CELL_HEIGHT_PX))
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            addMouseListener(
                object : MouseAdapter() {
                    override fun mouseEntered(event: MouseEvent) {
                        isHovered = true
                        repaint()
                    }

                    override fun mouseExited(event: MouseEvent) {
                        isHovered = false
                        repaint()
                    }

                    override fun mouseClicked(event: MouseEvent) {
                        if (!isEnabled) return
                        if (selectedVariant != variant) {
                            selectedVariant = variant
                            cells.forEach { it.repaint() }
                        }
                        onSelectionChanged(variant)
                    }
                },
            )
        }

        override fun getAccessibleContext(): AccessibleContext {
            val existing = super.getAccessibleContext()
            if (existing != null) {
                if (existing.accessibleName == null) {
                    existing.accessibleName = "Variant: ${variant.displayLabel()}"
                }
                return existing
            }
            val fresh =
                object : AccessibleJComponent() {
                    override fun getAccessibleRole(): AccessibleRole = AccessibleRole.PUSH_BUTTON
                }
            fresh.accessibleName = "Variant: ${variant.displayLabel()}"
            accessibleContext = fresh
            return fresh
        }

        override fun paintComponent(g: Graphics) {
            super.paintComponent(g)
            val g2 = g.create() as Graphics2D
            try {
                paintCell(g2)
            } finally {
                g2.dispose()
            }
        }

        private fun paintCell(g2: Graphics2D) {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)

            val isSelected = variant == selectedVariant
            val shape =
                RoundRectangle2D.Float(
                    BORDER_INSET,
                    BORDER_INSET,
                    width - 1f,
                    height - 1f,
                    CELL_ARC,
                    CELL_ARC,
                )

            when {
                isSelected -> {
                    g2.color = JBUI.CurrentTheme.ActionButton.pressedBackground()
                    g2.fill(shape)
                    g2.color = selectedBorderColor()
                    g2.stroke = BasicStroke(1f)
                    g2.draw(shape)
                }

                isHovered -> {
                    g2.color = JBUI.CurrentTheme.ActionButton.hoverBackground()
                    g2.fill(shape)
                }

                else -> Unit
            }

            paintLabel(g2, isSelected)
        }

        private fun paintLabel(
            g2: Graphics2D,
            isSelected: Boolean,
        ) {
            g2.font = font.deriveFont(Font.PLAIN, JBUI.scale(LABEL_FONT_PX).toFloat())
            g2.color =
                if (isSelected) {
                    JBColor.namedColor("Label.foreground", JBColor.foreground())
                } else {
                    labelDisabledForeground()
                }
            val text = variant.displayLabel()
            val metrics = g2.fontMetrics
            val textX = (width - metrics.stringWidth(text)) / 2
            val textY = (height - metrics.height) / 2 + metrics.ascent
            g2.drawString(text, textX, textY)
        }
    }

    private companion object {
        const val CELL_WIDTH_PX: Int = 64
        const val CELL_HEIGHT_PX: Int = 28
        const val LABEL_FONT_PX: Int = 12
        const val CELL_ARC: Float = 4f

        /** Inset for the rounded rect so the 1-px border stroke sits on the pixel grid. */
        const val BORDER_INSET: Float = 0.5f

        fun AyuVariant.displayLabel(): String = name.lowercase().replaceFirstChar { it.uppercase() }

        fun labelDisabledForeground(): Color = JBUI.CurrentTheme.Label.disabledForeground()

        fun selectedBorderColor(): Color =
            JBColor.namedColor(
                "Popup.innerBorderColor",
                JBColor.namedColor("Popup.borderColor", JBColor.GRAY),
            )
    }
}
