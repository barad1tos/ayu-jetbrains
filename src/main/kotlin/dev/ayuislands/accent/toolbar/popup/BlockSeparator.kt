package dev.ayuislands.accent.toolbar.popup

import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import org.jetbrains.annotations.TestOnly
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import javax.swing.JComponent

/**
 * 1-px hairline that separates the FREE block (variant + accent) from the PREMIUM
 * block (toggles + quick actions) inside the redesigned quick-switcher popup, per
 * 48-REDESIGN-SPEC §2.1 / §2.2.
 *
 * The line is insetted [Density.BLOCK_SEPARATOR_PAD] pixels on each side so it does
 * not flush into the section cards' rounded corners. The line color resolves through
 * `Group.separatorColor` (with `UIUtil.getBoundsColor()` fallback) so it re-themes on
 * LAF swap.
 *
 * Pattern A — paint runs on EDT by Swing contract.
 */
internal class BlockSeparator : JComponent() {
    init {
        isOpaque = false
        preferredSize = Dimension(0, JBUI.scale(STRIPE_HEIGHT_PX))
        minimumSize = Dimension(0, JBUI.scale(STRIPE_HEIGHT_PX))
    }

    override fun paintComponent(g: Graphics) {
        super.paintComponent(g)
        val g2 = g.create() as Graphics2D
        try {
            paintLine(g2)
        } finally {
            g2.dispose()
        }
    }

    /**
     * Test seam — Pattern I. Lets unit tests sample line pixels without instantiating
     * the platform popup chrome.
     */
    @TestOnly
    internal fun paintForTest(g: Graphics2D) {
        paintLine(g)
    }

    private fun paintLine(g2: Graphics2D) {
        val pad = JBUI.scale(Density.BLOCK_SEPARATOR_PAD)
        if (width <= pad * 2) return
        g2.color = JBColor.namedColor("Group.separatorColor", UIUtil.getBoundsColor())
        val y = height / 2
        g2.fillRect(pad, y, width - pad * 2, 1)
    }

    private companion object {
        const val STRIPE_HEIGHT_PX: Int = 1
    }
}
