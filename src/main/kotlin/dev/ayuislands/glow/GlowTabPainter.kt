package dev.ayuislands.glow

import com.intellij.openapi.diagnostic.logger
import java.awt.Graphics2D
import java.awt.Rectangle
import java.awt.RenderingHints
import java.awt.geom.RoundRectangle2D
import java.awt.Color
import javax.swing.UIManager

class GlowTabPainter {

    private val log = logger<GlowTabPainter>()
    private val renderer = GlowRenderer()

    var glowColor: Color = Color.decode("#FFCC66")
    var glowStyle: GlowStyle = GlowStyle.SOFT
    var tabMode: GlowTabMode = GlowTabMode.UNDERLINE

    // Tab glow is subtler than island glow
    var baseIntensity: Int = 40

    fun paintTabGlow(graphics: Graphics2D, tabBounds: Rectangle) {
        if (tabMode == GlowTabMode.OFF) return

        val g2 = graphics.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)

            // Tab glow intensity is 60% of island glow intensity
            val tabIntensity = (baseIntensity * 0.6).toInt().coerceIn(5, 100)

            when (tabMode) {
                GlowTabMode.UNDERLINE -> {
                    // Paint only bottom edge glow -- narrower strip (max 6px)
                    val underlineWidth = 6.coerceAtMost(tabBounds.height / 3)
                    renderer.ensureCache(glowColor, glowStyle, tabIntensity, underlineWidth)

                    // Paint only the bottom strip of the glow
                    val underlineBounds = Rectangle(
                        tabBounds.x, tabBounds.y + tabBounds.height - underlineWidth,
                        tabBounds.width, underlineWidth,
                    )
                    renderer.paintGlow(g2, underlineBounds, underlineWidth)
                }
                GlowTabMode.FULL_BORDER -> {
                    // Paint all 4 edges -- half island width, clamped to max 8px
                    val borderWidth = 8.coerceAtMost(tabBounds.height / 4)
                    renderer.ensureCache(glowColor, glowStyle, tabIntensity, borderWidth)

                    // Clip to tab bounds
                    val arcRadius = UIManager.getInt("Island.arc").let { if (it > 0) it / 2 else 4 }
                    val roundRect = RoundRectangle2D.Double(
                        tabBounds.x.toDouble(), tabBounds.y.toDouble(),
                        tabBounds.width.toDouble(), tabBounds.height.toDouble(),
                        arcRadius.toDouble(), arcRadius.toDouble(),
                    )
                    g2.clip(roundRect)
                    renderer.paintGlow(g2, tabBounds, borderWidth)
                }
                GlowTabMode.OFF -> { /* no-op */ }
            }
        } finally {
            g2.dispose()
        }
    }

    fun invalidateCache() {
        renderer.invalidateCache()
    }
}
