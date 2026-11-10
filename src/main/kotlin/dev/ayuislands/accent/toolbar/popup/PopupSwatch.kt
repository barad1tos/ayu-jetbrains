package dev.ayuislands.accent.toolbar.popup

import com.intellij.ui.ColorUtil
import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import dev.ayuislands.accent.AccentHex
import org.jetbrains.annotations.TestOnly
import java.awt.AlphaComposite
import java.awt.BasicStroke
import java.awt.Cursor
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.geom.RoundRectangle2D
import javax.swing.JButton

/**
 * Compact 36x24 accent swatch for the popup accent grid. Mirrors the paint
 * recipe from `dev.ayuislands.settings.AccentColorPanel`'s preset component
 * (`BORDER_RGB=0x4E5A6E`, `SELECTED_OVERLAY_ALPHA=0.55f`) so popup ↔ Settings
 * visuals stay in lockstep.
 *
 * State matrix:
 *
 * | State            | Fill                                  | Border               |
 * |------------------|---------------------------------------|----------------------|
 * | Idle             | full accent                           | 1-px [BORDER_RGB]    |
 * | Hover            | full accent                           | 2-px [BORDER_RGB]    |
 * | Selected         | accent + 0.55 alpha [BORDER_RGB]      | 1-px [BORDER_RGB]    |
 * | Selected + Hover | accent + 0.55 alpha [BORDER_RGB]      | 2-px [BORDER_RGB]    |
 * | Pressed          | accent + 0.70 alpha [BORDER_RGB]      | 1-px [BORDER_RGB]    |
 *
 * Native Swing button actions provide mouse, keyboard, focus, and accessibility
 * behavior. Selection is accepted by the parent, never toggled by a click.
 * Pattern A — mouse events run on EDT by Swing contract. Pattern Q — every
 * `Graphics.create()` block dismisses in `finally`.
 *
 * @param hex preset accent as a validated [AccentHex] (e.g. `#FFB454`).
 * @param isSelected starting selected state (matches active accent).
 * @param onClick fires on an enabled native button activation with the swatch's [hex].
 */
internal class PopupSwatch(
    val hex: AccentHex,
    isSelected: Boolean,
    onClick: (AccentHex) -> Unit,
) : JButton() {
    val selected: Boolean get() = model.isSelected

    init {
        isOpaque = false
        isContentAreaFilled = false
        isBorderPainted = false
        isFocusPainted = false
        isRolloverEnabled = true
        setSelected(isSelected)
        preferredSize = Dimension(JBUI.scale(WIDTH_PX), JBUI.scale(HEIGHT_PX))
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        toolTipText = hex.value
        getAccessibleContext().accessibleName = hex.value
        addActionListener { if (isEnabled) onClick(hex) }
    }

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        try {
            paintSwatch(g2)
        } finally {
            g2.dispose()
        }
    }

    /**
     * Test seam — Pattern I. Lets unit tests sample swatch pixels via a `BufferedImage`
     * `Graphics2D` without booting a full Swing event loop.
     */
    @TestOnly
    internal fun paintForTest(g: Graphics2D) {
        paintSwatch(g)
    }

    /** Test seam — set a native pressed appearance without emitting a button action. */
    @TestOnly
    internal fun setPressedForTest(pressed: Boolean) {
        model.isArmed = false
        model.isPressed = pressed
        model.isArmed = pressed
    }

    private fun paintSwatch(g2: Graphics2D) {
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        val w = width
        val h = height
        if (w <= 0 || h <= 0) return

        val arc = Density.SWATCH_ARC.toFloat()
        val shape =
            RoundRectangle2D.Float(
                BORDER_INSET,
                BORDER_INSET,
                w - 1f,
                h - 1f,
                arc,
                arc,
            )

        val accent = ColorUtil.fromHex(hex.value)
        val borderColor = JBColor(BORDER_RGB, BORDER_RGB)

        g2.color = accent
        g2.fill(shape)

        val overlayAlpha: Float? =
            when {
                model.isPressed && model.isArmed -> PRESSED_OVERLAY_ALPHA
                selected -> SELECTED_OVERLAY_ALPHA
                else -> null
            }
        if (overlayAlpha != null) {
            val previous = g2.composite
            g2.composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, overlayAlpha)
            g2.color = borderColor
            g2.fill(shape)
            g2.composite = previous
        }

        g2.color = borderColor
        g2.stroke = BasicStroke(if (model.isRollover || hasFocus()) HOVER_STROKE_PX else IDLE_STROKE_PX)
        g2.draw(shape)
    }

    private companion object {
        const val WIDTH_PX: Int = 36
        const val HEIGHT_PX: Int = 24
        const val BORDER_INSET: Float = 0.5f
        const val IDLE_STROKE_PX: Float = 1f
        const val HOVER_STROKE_PX: Float = 2f

        /** Lock-in: matches `AccentColorPanel.BORDER_RGB` so popup ↔ Settings visual parity holds. */
        const val BORDER_RGB: Int = 0x4E5A6E

        /** Lock-in: matches `AccentColorPanel.SELECTED_OVERLAY_ALPHA`. */
        const val SELECTED_OVERLAY_ALPHA: Float = 0.55f

        /** Pressed overlay is deeper than selected (spec §3.4 state matrix). */
        const val PRESSED_OVERLAY_ALPHA: Float = 0.7f
    }
}
