package dev.ayuislands.accent.toolbar.popup

import com.intellij.ui.ColorUtil
import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import org.jetbrains.annotations.TestOnly
import java.awt.AlphaComposite
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Cursor
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.geom.Ellipse2D
import java.awt.geom.RoundRectangle2D
import javax.swing.JComponent

/**
 * macOS-style pill toggle: 28x14 px (pre-scale) with a 10-px indicator circle that
 * slides between left (OFF) and right (ON). ON state fills with the current resolved
 * accent (read lazily via [accentSupplier] at paint time); OFF state fills with a
 * subtle pressed background and 1-px inner border.
 *
 * Bound to a hidden [javax.swing.JCheckBox] in [ToggleTile] so the Kotlin UI DSL
 * `bindSelected({ state.X }, { state.X = it })` plumbing continues to drive
 * persistence — this widget only mirrors and writes the model.
 *
 * Pattern A — every state mutation triggers `repaint()` on the calling thread
 * (already EDT for mouse events).
 *
 * @param initialSelected starting state of the switch.
 * @param accentSupplier provides the current accent hex; re-invoked on every paint.
 * @param listener fires on every flip with the new selection value.
 */
internal class ToggleSwitch(
    initialSelected: Boolean,
    private val accentSupplier: () -> String,
    private val listener: (Boolean) -> Unit,
) : JComponent() {
    var isSelected: Boolean = initialSelected
        private set

    init {
        isOpaque = false
        preferredSize = Dimension(JBUI.scale(SWITCH_WIDTH_PX), JBUI.scale(SWITCH_HEIGHT_PX))
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        addMouseListener(
            object : MouseAdapter() {
                override fun mouseClicked(event: MouseEvent) {
                    if (!isEnabled) return
                    flip()
                }
            },
        )
    }

    /**
     * Programmatic flip — used by [ToggleTile] to forward a click on the surrounding
     * tile body. Updates the model and notifies [listener] exactly once.
     */
    internal fun flip() {
        isSelected = !isSelected
        listener(isSelected)
        repaint()
    }

    override fun paintComponent(g: Graphics) {
        super.paintComponent(g)
        val g2 = g.create() as Graphics2D
        try {
            paintSwitch(g2)
        } finally {
            g2.dispose()
        }
    }

    /**
     * Test seam — Pattern I. Lets unit tests sample pixels without instantiating the
     * platform popup chrome.
     */
    @TestOnly
    internal fun paintForTest(g: Graphics2D) {
        paintSwitch(g)
    }

    private fun paintSwitch(g2: Graphics2D) {
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)

        val w = width
        val h = height
        if (w <= 0 || h <= 0) return

        val pill =
            RoundRectangle2D.Float(
                0f,
                0f,
                w.toFloat(),
                h.toFloat(),
                h.toFloat(),
                h.toFloat(),
            )

        if (isSelected) {
            g2.color = ColorUtil.fromHex(accentSupplier())
            g2.fill(pill)
        } else {
            g2.color = JBUI.CurrentTheme.ActionButton.pressedBackground()
            g2.fill(pill)
            g2.color =
                JBColor.namedColor(
                    "Popup.innerBorderColor",
                    JBColor.namedColor("Popup.borderColor", JBColor.GRAY),
                )
            g2.stroke = BasicStroke(1f)
            g2.draw(pill)
        }

        paintIndicator(g2, w, h)
    }

    private fun paintIndicator(
        g2: Graphics2D,
        w: Int,
        h: Int,
    ) {
        val indicatorDiameter = (h - INDICATOR_INSET_PX * 2).coerceAtLeast(MIN_INDICATOR_DIAMETER)
        val cy = (h - indicatorDiameter) / 2f
        val cx =
            if (isSelected) {
                (w - indicatorDiameter - INDICATOR_INSET_PX).toFloat()
            } else {
                INDICATOR_INSET_PX.toFloat()
            }
        val circle = Ellipse2D.Float(cx, cy, indicatorDiameter.toFloat(), indicatorDiameter.toFloat())

        val previous = g2.composite
        if (isSelected) {
            g2.composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, ON_INDICATOR_ALPHA)
            g2.color = Color.WHITE
        } else {
            g2.composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, OFF_INDICATOR_ALPHA)
            g2.color = JBColor.foreground()
        }
        g2.fill(circle)
        g2.composite = previous
    }

    private companion object {
        const val SWITCH_WIDTH_PX: Int = 28
        const val SWITCH_HEIGHT_PX: Int = 14
        const val INDICATOR_INSET_PX: Int = 2
        const val MIN_INDICATOR_DIAMETER: Int = 8
        const val ON_INDICATOR_ALPHA: Float = 0.9f
        const val OFF_INDICATOR_ALPHA: Float = 0.5f
    }
}
