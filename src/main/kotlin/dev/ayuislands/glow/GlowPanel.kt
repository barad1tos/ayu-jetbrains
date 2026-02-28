package dev.ayuislands.glow

import java.awt.Color
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Rectangle
import java.awt.RenderingHints
import javax.swing.JPanel

class GlowPanel : JPanel() {

    private val renderer = GlowRenderer()

    var glowColor: Color = Color.decode("#FFCC66")
        set(value) {
            if (field != value) {
                field = value
                renderer.invalidateCache()
                repaint()
            }
        }

    var glowEnabled: Boolean = true
        set(value) {
            if (field != value) {
                field = value
                repaint()
            }
        }

    var glowWidth: Int = GlowRenderer.DEFAULT_GLOW_WIDTH
        set(value) {
            if (field != value) {
                field = value
                renderer.invalidateCache()
                repaint()
            }
        }

    init {
        isOpaque = false
        preferredSize = Dimension(200, 100)
    }

    override fun paintComponent(graphics: Graphics) {
        super.paintComponent(graphics)
        if (!glowEnabled) return

        val g2 = graphics.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)

            renderer.ensureCache(glowColor, glowWidth)
            renderer.paintGlow(g2, Rectangle(0, 0, width, height), glowWidth)
        } finally {
            g2.dispose()
        }
    }
}
