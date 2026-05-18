package dev.ayuislands.accent.toolbar.popup

import com.intellij.ui.ColorUtil
import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import org.jetbrains.annotations.TestOnly
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Cursor
import java.awt.Dimension
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.geom.RoundRectangle2D
import javax.accessibility.AccessibleContext
import javax.accessibility.AccessibleRole
import javax.swing.JComponent

/**
 * "Islands UI" toggle pill per 48-REDESIGN-SPEC §3.3. Sits to the right of the
 * [SegmentedControl] inside the variant section card. Custom-painted because a
 * `JCheckBox` reads as a heavy debug control — the pill matches the segmented
 * control's visual language exactly.
 *
 * Glyph + label rendered inside a single pill. Selected state fills with
 * [JBUI.CurrentTheme.ActionButton.pressedBackground], 1-px border resolves through
 * `Popup.innerBorderColor`. Glyph foreground in the selected state echoes the
 * current resolved accent (read lazily via [accentSupplier] at paint time) — a
 * subtle visual connection back to the chip.
 *
 * Pattern A — mouse events run on EDT by Swing contract.
 *
 * @param initialSelected starting state of the pill (matches active LAF).
 * @param accentSupplier provides the current accent hex (re-invoked on every paint).
 * @param onToggle fires on each click with the new boolean state.
 */
internal class IslandsUiPill(
    initialSelected: Boolean,
    private val accentSupplier: () -> String,
    private val onToggle: (Boolean) -> Unit,
) : JComponent() {
    var isSelected: Boolean = initialSelected
        private set
    private var isHovered: Boolean = false

    init {
        isOpaque = false
        preferredSize = Dimension(JBUI.scale(PILL_WIDTH_PX), JBUI.scale(PILL_HEIGHT_PX))
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        toolTipText = "Islands UI"
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
                    isSelected = !isSelected
                    onToggle(isSelected)
                    repaint()
                }
            },
        )
    }

    /** Programmatic flip — kept symmetric with [ToggleSwitch.flip] for callers that bind a model. */
    fun setSelectedExternally(value: Boolean) {
        if (isSelected == value) return
        isSelected = value
        repaint()
    }

    override fun getAccessibleContext(): AccessibleContext {
        val existing = super.getAccessibleContext()
        if (existing != null) {
            if (existing.accessibleName == null) existing.accessibleName = "Islands UI"
            return existing
        }
        val fresh =
            object : AccessibleJComponent() {
                override fun getAccessibleRole(): AccessibleRole = AccessibleRole.TOGGLE_BUTTON
            }
        fresh.accessibleName = "Islands UI"
        accessibleContext = fresh
        return fresh
    }

    override fun paintComponent(g: Graphics) {
        super.paintComponent(g)
        val g2 = g.create() as Graphics2D
        try {
            paintPill(g2)
        } finally {
            g2.dispose()
        }
    }

    /**
     * Test seam — Pattern I. Lets unit tests sample fill / glyph pixels without a
     * full Swing event loop.
     */
    @TestOnly
    internal fun paintForTest(g: Graphics2D) {
        paintPill(g)
    }

    private fun paintPill(g2: Graphics2D) {
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)

        val w = width
        val h = height
        if (w <= 0 || h <= 0) return

        val shape =
            RoundRectangle2D.Float(
                BORDER_INSET,
                BORDER_INSET,
                w - 1f,
                h - 1f,
                ARC,
                ARC,
            )

        when {
            isSelected -> {
                g2.color = JBUI.CurrentTheme.ActionButton.pressedBackground()
                g2.fill(shape)
                g2.color = pressedBorderColor()
                g2.stroke = BasicStroke(1f)
                g2.draw(shape)
            }

            isHovered -> {
                g2.color = JBUI.CurrentTheme.ActionButton.hoverBackground()
                g2.fill(shape)
                g2.color = popupInnerBorder()
                g2.stroke = BasicStroke(1f)
                g2.draw(shape)
            }

            else -> {
                g2.color = popupInnerBorder()
                g2.stroke = BasicStroke(1f)
                g2.draw(shape)
            }
        }

        paintGlyphAndLabel(g2)
    }

    private fun paintGlyphAndLabel(g2: Graphics2D) {
        val glyphColor: Color =
            if (isSelected) ColorUtil.fromHex(accentSupplier()) else JBUI.CurrentTheme.Label.disabledForeground()

        g2.font = font.deriveFont(Font.PLAIN, JBUI.scale(GLYPH_FONT_PX).toFloat())
        val glyphMetrics = g2.fontMetrics
        val glyphX = JBUI.scale(GLYPH_LEFT_PAD)
        val glyphY = (height - glyphMetrics.height) / 2 + glyphMetrics.ascent
        g2.color = glyphColor
        g2.drawString(GLYPH, glyphX, glyphY)

        g2.font = font.deriveFont(Font.PLAIN, JBUI.scale(LABEL_FONT_PX).toFloat())
        val labelMetrics = g2.fontMetrics
        val labelX = glyphX + glyphMetrics.stringWidth(GLYPH) + JBUI.scale(LABEL_GAP)
        val labelY = (height - labelMetrics.height) / 2 + labelMetrics.ascent
        g2.color =
            if (isSelected) {
                JBColor.namedColor("Label.foreground", JBColor.foreground())
            } else {
                JBUI.CurrentTheme.Label.disabledForeground()
            }
        g2.drawString(LABEL, labelX, labelY)
    }

    private companion object {
        const val PILL_WIDTH_PX: Int = 94
        const val PILL_HEIGHT_PX: Int = 28
        const val BORDER_INSET: Float = 0.5f
        const val ARC: Float = 4f
        const val GLYPH_FONT_PX: Int = 12
        const val LABEL_FONT_PX: Int = 12
        const val GLYPH_LEFT_PAD: Int = 8
        const val LABEL_GAP: Int = 6

        /**
         * Unicode `◐` (half-moon) — spec §3.3 suggested glyph. Reads as a "duality"
         * affordance which lines up with the Islands UI / classic UI duality.
         */
        const val GLYPH: String = "◐"
        const val LABEL: String = "Islands UI"

        fun popupInnerBorder(): Color =
            JBColor.namedColor(
                "Popup.innerBorderColor",
                JBColor.namedColor("Popup.borderColor", JBColor.GRAY),
            )

        /**
         * Spawn-A finding: `JBUI.CurrentTheme.ActionButton.pressedBorderColor()` does
         * NOT exist on 2025.1. Substitute `Popup.innerBorderColor` for the same visual
         * weight.
         */
        fun pressedBorderColor(): Color = popupInnerBorder()
    }
}
