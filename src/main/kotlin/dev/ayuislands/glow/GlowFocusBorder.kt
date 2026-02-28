package dev.ayuislands.glow

import com.intellij.openapi.diagnostic.logger
import java.awt.Color
import java.awt.Component
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Insets
import java.awt.Rectangle
import java.awt.RenderingHints
import java.awt.event.FocusEvent
import java.awt.event.FocusListener
import java.awt.geom.RoundRectangle2D
import javax.swing.JComponent
import javax.swing.UIManager
import javax.swing.border.Border

class GlowFocusBorder(
    private val originalBorder: Border?,
    private val glowColor: Color,
    private val glowStyle: GlowStyle,
    private val baseIntensity: Int,
) : Border {

    private val log = logger<GlowFocusBorder>()
    private val renderer = GlowRenderer()

    // Focus ring width is fixed at 3px (subtle)
    private val focusRingWidth = 3

    // Focus-ring intensity is 40% of island glow intensity
    private val focusIntensity: Int
        get() = (baseIntensity * 0.4).toInt().coerceIn(3, 60)

    override fun paintBorder(
        component: Component,
        graphics: Graphics,
        x: Int,
        y: Int,
        width: Int,
        height: Int,
    ) {
        // Paint original border first
        originalBorder?.paintBorder(component, graphics, x, y, width, height)

        // Paint glow ring only when focused
        if (!component.hasFocus()) return

        val g2 = graphics.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)

            val arcRadius = UIManager.getInt("Component.arc").let { if (it > 0) it else 6 }
            val bounds = Rectangle(x, y, width, height)

            // Clip to rounded rect
            val roundRect = RoundRectangle2D.Double(
                x.toDouble(), y.toDouble(),
                width.toDouble(), height.toDouble(),
                arcRadius.toDouble(), arcRadius.toDouble(),
            )
            g2.clip(roundRect)

            renderer.ensureCache(glowColor, glowStyle, focusIntensity, focusRingWidth)
            renderer.paintGlow(g2, bounds, focusRingWidth)
        } finally {
            g2.dispose()
        }
    }

    override fun getBorderInsets(component: Component): Insets {
        // Delegate to original border insets -- don't add extra space
        return originalBorder?.getBorderInsets(component) ?: Insets(1, 1, 1, 1)
    }

    override fun isBorderOpaque(): Boolean = false

    companion object {
        /**
         * Creates a FocusListener that swaps the component border to a GlowFocusBorder
         * on focus gain and restores the original on focus loss.
         */
        fun createFocusListener(
            glowColor: Color,
            glowStyle: GlowStyle,
            baseIntensity: Int,
        ): FocusListener {
            return object : FocusListener {
                private var originalBorder: Border? = null

                override fun focusGained(event: FocusEvent) {
                    val component = event.component as? JComponent ?: return
                    originalBorder = component.border
                    component.border = GlowFocusBorder(
                        originalBorder, glowColor, glowStyle, baseIntensity,
                    )
                    component.repaint()
                }

                override fun focusLost(event: FocusEvent) {
                    val component = event.component as? JComponent ?: return
                    component.border = originalBorder
                    originalBorder = null
                    component.repaint()
                }
            }
        }
    }
}
