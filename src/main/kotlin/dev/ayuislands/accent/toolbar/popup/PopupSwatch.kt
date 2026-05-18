package dev.ayuislands.accent.toolbar.popup

import com.intellij.ui.ColorUtil
import com.intellij.util.ui.JBUI
import org.jetbrains.annotations.TestOnly
import java.awt.AlphaComposite
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Cursor
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.geom.RoundRectangle2D
import javax.swing.JComponent

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
 * Pattern A — mouse events run on EDT by Swing contract. Pattern Q — every
 * `Graphics.create()` block dismisses in `finally`.
 *
 * @param hex preset accent hex (e.g. `#FFB454`).
 * @param isSelected starting selected state (matches active accent).
 * @param onClick fires on mouse-release inside bounds with the swatch's [hex].
 */
internal class PopupSwatch(
    val hex: String,
    isSelected: Boolean,
    private val onClick: (String) -> Unit,
) : JComponent() {
    var selected: Boolean = isSelected
        private set
    private var isHovered: Boolean = false
    private var isPressed: Boolean = false

    init {
        isOpaque = false
        preferredSize = Dimension(JBUI.scale(WIDTH_PX), JBUI.scale(HEIGHT_PX))
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        toolTipText = hex
        addMouseListener(
            object : MouseAdapter() {
                override fun mouseEntered(event: MouseEvent) {
                    isHovered = true
                    repaint()
                }

                override fun mouseExited(event: MouseEvent) {
                    isHovered = false
                    isPressed = false
                    repaint()
                }

                override fun mousePressed(event: MouseEvent) {
                    if (!isEnabled) return
                    isPressed = true
                    repaint()
                }

                override fun mouseReleased(event: MouseEvent) {
                    val wasPressed = isPressed
                    isPressed = false
                    repaint()
                    if (wasPressed && contains(event.x, event.y)) onClick(hex)
                }
            },
        )
    }

    /**
     * Programmatic selection toggle — called by the parent grid when the active
     * accent changes (one swatch becomes selected; the prior one clears).
     */
    fun setSelected(value: Boolean) {
        if (selected == value) return
        selected = value
        repaint()
    }

    override fun paintComponent(g: Graphics) {
        super.paintComponent(g)
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

    /** Test seam — flip the pressed flag without dispatching a real `MousePressed`. */
    @TestOnly
    internal fun setPressedForTest(pressed: Boolean) {
        isPressed = pressed
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

        val accent = ColorUtil.fromHex(hex)
        val borderColor = Color(BORDER_RGB)

        g2.color = accent
        g2.fill(shape)

        val overlayAlpha: Float? =
            when {
                isPressed -> PRESSED_OVERLAY_ALPHA
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
        g2.stroke = BasicStroke(if (isHovered) HOVER_STROKE_PX else IDLE_STROKE_PX)
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
