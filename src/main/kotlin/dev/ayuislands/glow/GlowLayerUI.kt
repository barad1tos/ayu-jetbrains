package dev.ayuislands.glow

import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Rectangle
import java.lang.reflect.Method
import javax.swing.JComponent
import javax.swing.JLayer
import javax.swing.plaf.LayerUI

/** LayerUI that paints a tab glow via [tabPainter] on the selected editor tab. */
class GlowLayerUI : LayerUI<JComponent>() {
    /** Delegates painting to GlowTabPainter for the selected-tab glow. */
    var tabPainter: GlowTabPainter? = null

    // Cached reflection Method objects for tab glow painting (resolved once per instance)
    private var cachedTabsClass: Class<*>? = null
    private var cachedGetSelectedInfo: Method? = null
    private var cachedGetTabLabel: Method? = null

    override fun paint(
        graphics: Graphics,
        component: JComponent,
    ) {
        super.paint(graphics, component)

        val layer = component as? JLayer<*> ?: return
        val painter = tabPainter ?: return
        paintTabGlow(graphics, layer, painter)
    }

    private fun paintTabGlow(
        graphics: Graphics,
        layer: JLayer<*>,
        painter: GlowTabPainter,
    ) {
        val tabs = layer.view ?: return
        try {
            val tabsClass = tabs.javaClass
            if (tabsClass !== cachedTabsClass) {
                cachedTabsClass = tabsClass
                cachedGetSelectedInfo = tabsClass.getMethod("getSelectedInfo")
                cachedGetTabLabel = null
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
}
