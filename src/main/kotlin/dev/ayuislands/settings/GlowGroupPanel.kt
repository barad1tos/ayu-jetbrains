package dev.ayuislands.settings

import com.intellij.util.ui.JBUI
import dev.ayuislands.glow.GlowRenderer
import dev.ayuislands.glow.GlowStyle
import java.awt.AlphaComposite
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Rectangle
import java.awt.RenderingHints
import javax.swing.JPanel

/**
 * JPanel that paints a static glow overlay around its content using [GlowRenderer].
 * Uses an empty border for glow padding — the title is handled by the inner DSL group.
 */
class GlowGroupPanel : JPanel(BorderLayout()) {
    var glowStyle: GlowStyle = GlowStyle.SOFT
    var glowIntensity: Int = DEFAULT_INTENSITY
    var glowWidth: Int = DEFAULT_WIDTH
    var glowColor: Color = Color.decode(DEFAULT_COLOR_HEX)
    var glowVisible: Boolean = false

    private val renderer = GlowRenderer()

    init {
        border = JBUI.Borders.empty(GLOW_PADDING)
    }

    override fun paintComponent(g: Graphics) {
        super.paintComponent(g)
        if (!glowVisible || glowIntensity <= 0) return

        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.composite = AlphaComposite.SrcOver.derive(OVERLAY_ALPHA)
            renderer.ensureCache(glowColor, glowStyle, glowIntensity, glowWidth)
            renderer.paintGlow(g2, Rectangle(0, 0, width, height), glowWidth, ARC_RADIUS)
        } finally {
            g2.dispose()
        }
    }

    fun updateFromPreset(
        style: GlowStyle,
        intensity: Int,
        width: Int,
        color: Color,
        visible: Boolean,
    ) {
        glowStyle = style
        glowIntensity = intensity
        glowWidth = width
        glowColor = color
        glowVisible = visible
        repaint()
    }

    companion object {
        private const val ARC_RADIUS = 12
        private const val OVERLAY_ALPHA = 0.8f
        private const val GLOW_PADDING = 14
        private const val DEFAULT_INTENSITY = 35
        private const val DEFAULT_WIDTH = 8
        private const val DEFAULT_COLOR_HEX = "#FFCC66"
    }
}
