package dev.ayuislands.glow

import java.awt.AlphaComposite
import java.awt.Color
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Rectangle
import java.awt.RenderingHints
import java.lang.reflect.Method
import javax.swing.JComponent
import javax.swing.JLayer
import javax.swing.Timer
import javax.swing.UIManager
import javax.swing.plaf.LayerUI

/**
 * LayerUI that paints glow effects in two modes:
 * - Border glow (default): renders the glow around the entire JLayer bounds with fade animation
 * - Tab glow: when [tabPainter] is set, delegates to GlowTabPainter for the selected-tab glow
 */
class GlowLayerUI : LayerUI<JComponent>() {
    var glowColor: Color = Color.decode("#FFCC66")
    var glowStyle: GlowStyle = GlowStyle.SOFT
    var glowIntensity: Int = DEFAULT_INTENSITY
    var glowWidth: Int = GlowRenderer.DEFAULT_GLOW_WIDTH
    var isActive: Boolean = false

    /** When set, paint() delegates to tab glow mode instead of a border glow. */
    var tabPainter: GlowTabPainter? = null

    private var fadeAlpha: Float = 0.0f
    private var fadeTimer: Timer? = null
    private val renderer = GlowRenderer()

    private var parentLayer: JLayer<*>? = null

    // Cached reflection Method objects for tab glow painting (resolved once per instance)
    private var cachedTabsClass: Class<*>? = null
    private var cachedGetSelectedInfo: Method? = null
    private var cachedGetTabLabel: Method? = null

    companion object {
        private const val DEFAULT_INTENSITY = 40
        private const val DEFAULT_ARC_FALLBACK = 8
        private const val FADE_TIMER_INTERVAL_MS = 16
        private const val FADE_STEP = 0.08f
    }

    override fun paint(
        graphics: Graphics,
        component: JComponent,
    ) {
        super.paint(graphics, component)

        val layer = component as? JLayer<*> ?: return
        val painter = tabPainter
        if (painter != null) {
            paintTabGlow(graphics, layer, painter)
        } else {
            paintBorderGlow(graphics, layer)
        }
    }

    private fun paintTabGlow(
        graphics: Graphics,
        layer: JLayer<*>,
        painter: GlowTabPainter,
    ) {
        val tabs = layer.view ?: return
        try {
            // Cache Method objects on the first paint (or if tabs class changes across IDE versions)
            val tabsClass = tabs.javaClass
            if (tabsClass !== cachedTabsClass) {
                cachedTabsClass = tabsClass
                cachedGetSelectedInfo = tabsClass.getMethod("getSelectedInfo")
                cachedGetTabLabel = null // Will resolve from tabInfo class below
            }

            val getSelectedInfo = cachedGetSelectedInfo ?: return
            val tabInfo = getSelectedInfo.invoke(tabs) ?: return

            if (cachedGetTabLabel == null) {
                cachedGetTabLabel = tabInfo.javaClass.getMethod("getTabLabel")
            }
            val getTabLabel = cachedGetTabLabel ?: return
            val label = getTabLabel.invoke(tabInfo) as? JComponent ?: return

            val tabBounds = label.bounds
            val g2 = graphics.create() as Graphics2D
            try {
                g2.translate(tabBounds.x, tabBounds.y)
                painter.paintTabGlow(g2, Rectangle(0, 0, tabBounds.width, tabBounds.height))
            } finally {
                g2.dispose()
            }
        } catch (_: Exception) {
            // Graceful degradation if JBEditorTabs API changes across IDE versions
        }
    }

    private fun paintBorderGlow(
        graphics: Graphics,
        layer: JLayer<*>,
    ) {
        if (fadeAlpha <= 0.0f) return

        val g2 = graphics.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.composite = AlphaComposite.SrcOver.derive(fadeAlpha)

            val arcRadius = UIManager.getInt("Island.arc").let { if (it > 0) it else DEFAULT_ARC_FALLBACK }
            val bounds = Rectangle(0, 0, layer.width, layer.height)

            renderer.ensureCache(glowColor, glowStyle, glowIntensity, glowWidth)
            renderer.paintGlow(g2, bounds, glowWidth, arcRadius)
        } finally {
            g2.dispose()
        }
    }

    fun startFadeIn() {
        fadeTimer?.stop()
        // ~60fps at 16ms interval, 0.08f step = ~12 frames = ~200ms total
        fadeTimer =
            Timer(FADE_TIMER_INTERVAL_MS) {
                fadeAlpha = (fadeAlpha + FADE_STEP).coerceAtMost(1.0f)
                repaintLayer()
                if (fadeAlpha >= 1.0f) fadeTimer?.stop()
            }.also { it.start() }
    }

    fun startFadeOut() {
        fadeTimer?.stop()
        fadeTimer =
            Timer(FADE_TIMER_INTERVAL_MS) {
                fadeAlpha = (fadeAlpha - FADE_STEP).coerceAtLeast(0.0f)
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
