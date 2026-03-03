package dev.ayuislands.glow

import java.awt.AlphaComposite
import java.awt.Color
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Rectangle
import java.awt.RenderingHints
import javax.swing.JPanel
import javax.swing.Timer
import javax.swing.UIManager

/**
 * Transparent overlay that paints the neon glow on top of tool window content.
 * Added as z-order 0 child of the island host container.
 * Mouse events pass through via contains() returning false.
 * Ignores layout managers to stay positioned over the full host area.
 */
class GlowGlassPane(
    var glowColor: Color,
    var glowStyle: GlowStyle,
    var glowIntensity: Int,
    var glowWidth: Int,
) : JPanel(null) {
    private val renderer = GlowRenderer()
    private var fadeAlpha: Float = 0.0f
    private var fadeTimer: Timer? = null

    companion object {
        private const val DEFAULT_ARC_FALLBACK = 8
        private const val FADE_TIMER_INTERVAL_MS = 16
        private const val FADE_STEP = 0.08f
    }

    /** Animation alpha modulated by GlowAnimator (Pulse/Breathe/Reactive). Default 1.0 = no effect. */
    var animationAlpha: Float = 1.0f
        set(value) {
            field = value
            repaint()
        }

    init {
        isOpaque = false
    }

    override fun contains(
        x: Int,
        y: Int,
    ): Boolean = false

    // Prevent layout managers from resizing/repositioning this overlay
    override fun getPreferredSize(): Dimension = parent?.size ?: super.getPreferredSize()

    override fun getMinimumSize(): Dimension = Dimension(0, 0)

    override fun getMaximumSize(): Dimension = Dimension(Int.MAX_VALUE, Int.MAX_VALUE)

    override fun paintComponent(g: Graphics) {
        val effectiveAlpha = fadeAlpha * animationAlpha
        if (effectiveAlpha <= 0.0f) return

        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.composite = AlphaComposite.SrcOver.derive(effectiveAlpha)

            val arcRadius = UIManager.getInt("Island.arc").let { if (it > 0) it else DEFAULT_ARC_FALLBACK }
            val bounds = Rectangle(0, 0, width, height)

            renderer.ensureCache(glowColor, glowStyle, glowIntensity, glowWidth)
            renderer.paintGlow(g2, bounds, glowWidth, arcRadius)
        } finally {
            g2.dispose()
        }
    }

    fun startFadeIn() {
        fadeTimer?.stop()
        fadeTimer =
            Timer(FADE_TIMER_INTERVAL_MS) {
                fadeAlpha = (fadeAlpha + FADE_STEP).coerceAtMost(1.0f)
                repaint()
                if (fadeAlpha >= 1.0f) fadeTimer?.stop()
            }.also { it.start() }
    }

    fun startFadeOut() {
        fadeTimer?.stop()
        fadeTimer =
            Timer(FADE_TIMER_INTERVAL_MS) {
                fadeAlpha = (fadeAlpha - FADE_STEP).coerceAtLeast(0.0f)
                repaint()
                if (fadeAlpha <= 0.0f) fadeTimer?.stop()
            }.also { it.start() }
    }

    fun stopAnimation() {
        fadeTimer?.stop()
        fadeTimer = null
    }

    fun invalidateRendererCache() {
        renderer.invalidateCache()
    }
}
