package dev.ayuislands.accent.toolbar.popup

import com.intellij.ui.ColorUtil
import com.intellij.util.ui.JBUI
import org.jetbrains.annotations.TestOnly
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import javax.swing.JComponent

/**
 * 2-px-tall stripe painted across the top edge of the Wave-7 quick-switcher popup.
 * The single accent-tinted chrome element in the popup container per Locked Answer #2
 * of `48-REDESIGN-SPEC.md` (§2.1).
 *
 * The accent hex is resolved lazily inside [paintComponent] via the supplied lambda —
 * never cached at construction. A mid-LAF-swap repaint therefore picks up the resolved
 * accent immediately. Pattern A — paint already runs on EDT.
 *
 * @param accentSupplier provides the current accent hex (e.g. via
 *   `AccentResolver.resolve(AccentApplicator.resolveFocusedProject(), AyuVariant.detect()!!)`).
 *   Re-invoked on every paint.
 */
internal class AccentStripe(
    private val accentSupplier: () -> String,
) : JComponent() {
    init {
        isOpaque = false
        preferredSize = Dimension(0, JBUI.scale(STRIPE_HEIGHT_PX))
        minimumSize = Dimension(0, JBUI.scale(STRIPE_HEIGHT_PX))
    }

    override fun paintComponent(g: Graphics) {
        super.paintComponent(g)
        val g2 = g.create() as Graphics2D
        try {
            paintStripe(g2)
        } finally {
            g2.dispose()
        }
    }

    /**
     * Test seam — exposed so unit tests can sample pixels without instantiating the
     * platform `JBPopupFactory` chrome. Pattern I; mirrors the seam pattern used by
     * `QuickSwitcherChipComponentTest`'s render assertions.
     */
    @TestOnly
    internal fun paintForTest(g: Graphics2D) {
        paintStripe(g)
    }

    private fun paintStripe(g2: Graphics2D) {
        val hex = accentSupplier()
        val color = ColorUtil.fromHex(hex)
        g2.color = color
        g2.fillRect(0, 0, width, height)
    }

    private companion object {
        /** Pre-scale stripe height; consumer side wraps in `JBUI.scale(...)`. */
        const val STRIPE_HEIGHT_PX: Int = 2
    }
}
