package dev.ayuislands.glow

import java.awt.Color
import java.awt.Graphics2D
import java.awt.Rectangle
import java.awt.RenderingHints
import javax.swing.UIManager

class GlowTabPainter {
    private val renderer = GlowRenderer()

    var glowColor: Color = Color.decode("#FFCC66")
    var glowStyle: GlowStyle = GlowStyle.SOFT
    var tabMode: GlowTabMode = GlowTabMode.UNDERLINE

    // Tab glow is subtler than an island glow
    var baseIntensity: Int = DEFAULT_BASE_INTENSITY

    companion object {
        private const val DEFAULT_BASE_INTENSITY = 40
        private const val INTENSITY_FACTOR = 0.6
        private const val MIN_TAB_INTENSITY = 5
        private const val MAX_TAB_INTENSITY = 100
        private const val MAX_UNDERLINE_WIDTH = 6
        private const val TAB_HEIGHT_DIVISOR_UNDERLINE = 3
        private const val MAX_BORDER_WIDTH = 8
        private const val TAB_HEIGHT_DIVISOR_BORDER = 4
        private const val DEFAULT_ARC_FALLBACK = 4
    }

    fun paintTabGlow(
        graphics: Graphics2D,
        tabBounds: Rectangle,
    ) {
        if (tabMode == GlowTabMode.OFF) return

        val g2 = graphics.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)

            // Tab glow intensity is 60% of island glow intensity
            val tabIntensity = (baseIntensity * INTENSITY_FACTOR).toInt().coerceIn(MIN_TAB_INTENSITY, MAX_TAB_INTENSITY)

            when (tabMode) {
                GlowTabMode.UNDERLINE -> {
                    // Paint only bottom edge glow -- narrower strip (max 6px)
                    val underlineWidth =
                        MAX_UNDERLINE_WIDTH.coerceAtMost(tabBounds.height / TAB_HEIGHT_DIVISOR_UNDERLINE)
                    renderer.ensureCache(glowColor, glowStyle, tabIntensity, underlineWidth)

                    // Paint only the bottom strip of the glow
                    val underlineBounds =
                        Rectangle(
                            tabBounds.x,
                            tabBounds.y + tabBounds.height - underlineWidth,
                            tabBounds.width,
                            underlineWidth,
                        )
                    renderer.paintGlow(g2, underlineBounds, underlineWidth)
                }
                GlowTabMode.FULL_BORDER -> {
                    // Paint all 4 edges -- half-island width, clamped to max 8px
                    val borderWidth = MAX_BORDER_WIDTH.coerceAtMost(tabBounds.height / TAB_HEIGHT_DIVISOR_BORDER)
                    renderer.ensureCache(glowColor, glowStyle, tabIntensity, borderWidth)

                    val arcRadius = UIManager.getInt("Island.arc").let { if (it > 0) it / 2 else DEFAULT_ARC_FALLBACK }
                    renderer.paintGlow(g2, tabBounds, borderWidth, arcRadius)
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
