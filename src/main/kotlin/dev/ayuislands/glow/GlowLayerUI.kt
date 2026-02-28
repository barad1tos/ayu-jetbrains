package dev.ayuislands.glow

import java.awt.AlphaComposite
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Rectangle
import java.awt.RenderingHints
import java.awt.geom.RoundRectangle2D
import java.awt.Color
import javax.swing.JComponent
import javax.swing.JLayer
import javax.swing.Timer
import javax.swing.UIManager
import javax.swing.plaf.LayerUI

class GlowLayerUI : LayerUI<JComponent>() {

    var glowColor: Color = Color.decode("#FFCC66")
    var glowStyle: GlowStyle = GlowStyle.SOFT
    var glowIntensity: Int = 40
    var glowWidth: Int = GlowRenderer.DEFAULT_GLOW_WIDTH
    var isActive: Boolean = false

    private var fadeAlpha: Float = 0.0f
    private var fadeTimer: Timer? = null
    private val renderer = GlowRenderer()

    private var parentLayer: JLayer<*>? = null

    override fun paint(graphics: Graphics, component: JComponent) {
        super.paint(graphics, component)

        if (fadeAlpha <= 0.0f) return

        val layer = component as? JLayer<*> ?: return
        val g2 = graphics.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.composite = AlphaComposite.SrcOver.derive(fadeAlpha)

            // Read arc radius from UI theme (Island.arc), fallback to 8
            val arcRadius = UIManager.getInt("Island.arc").let { if (it > 0) it else 8 }

            // Clip to rounded rect matching island arc to prevent overlap between adjacent panels
            val bounds = Rectangle(0, 0, layer.width, layer.height)
            val roundRect = RoundRectangle2D.Double(
                bounds.x.toDouble(), bounds.y.toDouble(),
                bounds.width.toDouble(), bounds.height.toDouble(),
                arcRadius.toDouble(), arcRadius.toDouble(),
            )
            val originalClip = g2.clip
            g2.clip(roundRect)

            renderer.ensureCache(glowColor, glowStyle, glowIntensity, glowWidth)
            renderer.paintGlow(g2, bounds, glowWidth)

            g2.clip = originalClip
        } finally {
            g2.dispose()
        }
    }

    fun startFadeIn() {
        fadeTimer?.stop()
        // ~60fps at 16ms interval, 0.08f step = ~12 frames = ~200ms total
        fadeTimer = Timer(16) {
            fadeAlpha = (fadeAlpha + 0.08f).coerceAtMost(1.0f)
            repaintLayer()
            if (fadeAlpha >= 1.0f) fadeTimer?.stop()
        }.also { it.start() }
    }

    fun startFadeOut() {
        fadeTimer?.stop()
        fadeTimer = Timer(16) {
            fadeAlpha = (fadeAlpha - 0.08f).coerceAtLeast(0.0f)
            repaintLayer()
            if (fadeAlpha <= 0.0f) fadeTimer?.stop()
        }.also { it.start() }
    }

    fun stopAnimation() {
        fadeTimer?.stop()
        fadeTimer = null
    }

    override fun installUI(component: JComponent) {
        super.installUI(component)
        if (component is JLayer<*>) parentLayer = component
    }

    override fun uninstallUI(component: JComponent) {
        parentLayer = null
        super.uninstallUI(component)
    }

    private fun repaintLayer() {
        parentLayer?.repaint()
    }
}
