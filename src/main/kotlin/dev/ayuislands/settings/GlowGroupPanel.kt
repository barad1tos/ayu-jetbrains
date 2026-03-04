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
import java.awt.geom.Area
import java.awt.geom.RoundRectangle2D
import javax.swing.JPanel

/**
 * JPanel that paints a glow border around its content using [GlowRenderer].
 *
 * The glow is clipped to a donut-shaped border zone (outer minus inner rect),
 * then an opaque fill covers the inner content area so children render cleanly.
 */
class GlowGroupPanel : JPanel(BorderLayout()) {
    var glowStyle: GlowStyle = GlowStyle.SOFT
    var glowIntensity: Int = DEFAULT_INTENSITY
    var glowWidth: Int = DEFAULT_WIDTH
    var glowColor: Color = Color.decode(DEFAULT_COLOR_HEX)
    var glowVisible: Boolean = false

    private val renderer = GlowRenderer()

    init {
        border = JBUI.Borders.empty(FIXED_PADDING)
    }

    override fun paintComponent(g: Graphics) {
        super.paintComponent(g)
        if (!glowVisible || glowIntensity <= 0) return

        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.composite = AlphaComposite.SrcOver.derive(OVERLAY_ALPHA)

            val outer =
                Area(
                    RoundRectangle2D.Float(
                        0f,
                        0f,
                        width.toFloat(),
                        height.toFloat(),
                        ARC_F,
                        ARC_F,
                    ),
                )
            val ins = insets
            val inner =
                Area(
                    RoundRectangle2D.Float(
                        ins.left.toFloat(),
                        ins.top.toFloat(),
                        (width - ins.left - ins.right).toFloat(),
                        (height - ins.top - ins.bottom).toFloat(),
                        INNER_ARC_F,
                        INNER_ARC_F,
                    ),
                )
            outer.subtract(inner)
            g2.clip(outer)

            renderer.ensureCache(glowColor, glowStyle, glowIntensity, glowWidth)
            renderer.paintGlow(g2, Rectangle(0, 0, width, height), glowWidth, ARC_RADIUS)
        } finally {
            g2.dispose()
        }

        // Opaque fill over inner content area so children render cleanly
        val g3 = g.create() as Graphics2D
        try {
            val ins = insets
            g3.color = background
            g3.fillRoundRect(
                ins.left,
                ins.top,
                width - ins.left - ins.right,
                height - ins.top - ins.bottom,
                INNER_ARC_RADIUS,
                INNER_ARC_RADIUS,
            )
        } finally {
            g3.dispose()
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
        private const val ARC_F = 24f
        private const val INNER_ARC_F = 16f
        private const val INNER_ARC_RADIUS = 8
        private const val OVERLAY_ALPHA = 0.8f
        private const val DEFAULT_INTENSITY = 35
        private const val DEFAULT_WIDTH = 8
        private const val DEFAULT_COLOR_HEX = "#FFCC66"
        private const val FIXED_PADDING = 10
    }
}
