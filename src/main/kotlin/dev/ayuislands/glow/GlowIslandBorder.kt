package dev.ayuislands.glow

import java.awt.AlphaComposite
import java.awt.Color
import java.awt.Component
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Insets
import java.awt.Rectangle
import java.awt.RenderingHints
import javax.swing.JComponent
import javax.swing.Timer
import javax.swing.UIManager
import javax.swing.border.Border

/**
 * Decorating border that paints a neon glow effect around tool window panels.
 * Wraps the original border and adds glow rendering on top.
 * Supports fade-in/fade-out animation via an internal alpha timer.
 */
class GlowIslandBorder(
    private val originalBorder: Border?,
    var glowColor: Color,
    var glowStyle: GlowStyle,
    var glowIntensity: Int,
    var glowWidth: Int,
) : Border {

    private val renderer = GlowRenderer()
    private var fadeAlpha: Float = 0.0f
    private var fadeTimer: Timer? = null
    private var targetComponent: JComponent? = null

    override fun paintBorder(
        component: Component,
        graphics: Graphics,
        x: Int,
        y: Int,
        width: Int,
        height: Int,
    ) {
        originalBorder?.paintBorder(component, graphics, x, y, width, height)

        if (fadeAlpha <= 0.0f) return

        val g2 = graphics.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.composite = AlphaComposite.SrcOver.derive(fadeAlpha)

            val arcRadius = UIManager.getInt("Island.arc").let { if (it > 0) it else 8 }
            val bounds = Rectangle(x, y, width, height)

            renderer.ensureCache(glowColor, glowStyle, glowIntensity, glowWidth)
            renderer.paintGlow(g2, bounds, glowWidth, arcRadius)
        } finally {
            g2.dispose()
        }
    }

    override fun getBorderInsets(component: Component): Insets {
        return originalBorder?.getBorderInsets(component) ?: Insets(0, 0, 0, 0)
    }

    override fun isBorderOpaque(): Boolean = false

    fun attach(component: JComponent) {
        targetComponent = component
    }

    fun startFadeIn() {
        fadeTimer?.stop()
        fadeTimer = Timer(16) {
            fadeAlpha = (fadeAlpha + 0.08f).coerceAtMost(1.0f)
            targetComponent?.repaint()
            if (fadeAlpha >= 1.0f) fadeTimer?.stop()
        }.also { it.start() }
    }

    fun startFadeOut() {
        fadeTimer?.stop()
        fadeTimer = Timer(16) {
            fadeAlpha = (fadeAlpha - 0.08f).coerceAtLeast(0.0f)
            targetComponent?.repaint()
            if (fadeAlpha <= 0.0f) fadeTimer?.stop()
        }.also { it.start() }
    }

    fun stopAnimation() {
        fadeTimer?.stop()
        fadeTimer = null
    }
}
