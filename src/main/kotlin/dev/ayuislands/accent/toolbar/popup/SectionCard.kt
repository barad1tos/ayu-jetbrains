package dev.ayuislands.accent.toolbar.popup

import com.intellij.ui.ColorUtil
import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import org.jetbrains.annotations.TestOnly
import java.awt.BasicStroke
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.geom.RoundRectangle2D
import javax.swing.BorderFactory
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.SwingConstants

/**
 * Sectioned wrapper for popup content. Replaces a stock Kotlin UI DSL
 * `group("...")` with a hand-built composition:
 *
 *  - NORTH = a caps-header strip ([Density.SECTION_HEADER_H] tall, opaque, slightly tinted
 *    relative to the popup body),
 *  - CENTER = whatever [JComponent] the caller installs via [setContent].
 *
 * The outer shell paints its own rounded body via `RoundRectangle2D` plus a 1-px border
 * in `Popup.innerBorderColor` (with `Popup.borderColor` then `JBColor.GRAY` fallbacks).
 * Backgrounds resolve lazily inside [paintComponent] so a LAF swap re-themes without
 * a constructor rebuild.
 *
 * Pattern A — paint runs on EDT by Swing contract.
 */
internal class SectionCard(
    private val title: String,
) : JPanel(BorderLayout()) {
    private val headerLabel: JLabel =
        JLabel(title.uppercase(), SwingConstants.LEFT).apply {
            isOpaque = true
            border = JBUI.Borders.empty(0, JBUI.scale(Density.CARD_CONTENT_PAD))
            preferredSize =
                Dimension(0, JBUI.scale(Density.SECTION_HEADER_H))
            font = font.deriveFont(Font.PLAIN, JBUI.scale(HEADER_FONT_PX).toFloat())
            background = headerBackground()
            foreground = capsHeaderForeground()
        }

    init {
        isOpaque = false
        border = BorderFactory.createEmptyBorder()
        add(headerLabel, BorderLayout.NORTH)
    }

    /**
     * Install the [JComponent] caller wants in the card's content area. Replaces any
     * previously installed CENTER slot.
     */
    fun setContent(content: JComponent) {
        val layout = layout as BorderLayout
        layout.getLayoutComponent(BorderLayout.CENTER)?.let { remove(it) }
        content.border = JBUI.Borders.empty(JBUI.scale(Density.CARD_CONTENT_PAD))
        add(content, BorderLayout.CENTER)
        revalidate()
        repaint()
    }

    override fun paintComponent(g: Graphics) {
        // Repaint header background each call — a LAF swap mid-popup-life must re-tint.
        headerLabel.background = headerBackground()
        headerLabel.foreground = capsHeaderForeground()
        val g2 = g.create() as Graphics2D
        try {
            paintCard(g2)
        } finally {
            g2.dispose()
        }
        super.paintComponent(g)
    }

    /**
     * Test seam — Pattern I. Lets unit tests run the paint pipeline end-to-end without
     * a Swing event loop. The seam intentionally does NOT mutate header bg/fg so the
     * test stays deterministic.
     */
    @TestOnly
    internal fun paintForTest(g: Graphics2D) {
        paintCard(g)
    }

    private fun paintCard(g2: Graphics2D) {
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        val w = width
        val h = height
        if (w <= 0 || h <= 0) return

        val shape =
            RoundRectangle2D.Float(
                BORDER_INSET,
                BORDER_INSET,
                w - 1f,
                h - 1f,
                Density.CARD_ARC,
                Density.CARD_ARC,
            )

        g2.color = popupBackground()
        g2.fill(shape)
        g2.color = cardBorderColor()
        g2.stroke = BasicStroke(1f)
        g2.draw(shape)
    }

    private companion object {
        const val HEADER_FONT_PX: Int = 10
        const val BORDER_INSET: Float = 0.5f

        fun popupBackground(): Color = JBColor.namedColor("Popup.background", UIUtil.getPanelBackground())

        fun headerBackground(): Color {
            val popupBg = popupBackground()
            return if (JBColor.isBright()) {
                ColorUtil.darker(popupBg, 1)
            } else {
                ColorUtil.brighter(popupBg, 1)
            }
        }

        fun capsHeaderForeground(): Color =
            JBColor.namedColor(
                "Group.disabledSeparatorColor",
                UIUtil.getContextHelpForeground(),
            )

        fun cardBorderColor(): Color =
            JBColor.namedColor(
                "Popup.innerBorderColor",
                JBColor.namedColor("Popup.borderColor", JBColor.GRAY),
            )
    }
}
