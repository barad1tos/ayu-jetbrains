package dev.ayuislands.whatsnew

import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Cursor
import java.awt.Dimension
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JPanel

/**
 * Plain editor-tab footer button for the What's New panel.
 *
 * Distinct from `OnboardingComponents.createStyledButton`, which is sized for
 * the wizard's hero overlay and depends on a `ContentScaler`. Here we want a
 * smaller, IDE-native-feeling button suitable for an editor tab footer.
 */
internal class ShowWhatsNewButton(
    text: String,
    private val tint: Color,
    private val isAccent: Boolean,
    private val onClick: () -> Unit,
) : JPanel(BorderLayout()) {
    private var hovered = false
    private var pressed = false

    init {
        isOpaque = false
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        border = JBUI.Borders.empty(PADDING_Y, PADDING_X)
        val size = Dimension(JBUI.scale(WIDTH), JBUI.scale(HEIGHT))
        preferredSize = size
        minimumSize = size

        val label = JBLabel(text)
        label.horizontalAlignment = JBLabel.CENTER
        label.font =
            label.font.deriveFont(
                if (isAccent) Font.BOLD else Font.PLAIN,
                JBUI.scale(FONT_SIZE).toFloat(),
            )
        label.foreground = if (isAccent) ACCENT_INK else JBColor.foreground()
        add(label, BorderLayout.CENTER)

        addMouseListener(
            object : MouseAdapter() {
                override fun mouseEntered(event: MouseEvent) {
                    hovered = true
                    repaint()
                }

                override fun mouseExited(event: MouseEvent) {
                    hovered = false
                    pressed = false
                    repaint()
                }

                override fun mousePressed(event: MouseEvent) {
                    pressed = true
                    repaint()
                }

                override fun mouseReleased(event: MouseEvent) {
                    val wasPressed = pressed
                    pressed = false
                    repaint()
                    if (wasPressed && hovered) onClick()
                }
            },
        )
    }

    override fun paintComponent(graphics: Graphics) {
        val g2 = graphics as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        val arc = JBUI.scale(ARC)
        val fillColor =
            when {
                pressed -> shade(tint, PRESSED_ALPHA)
                hovered -> shade(tint, HOVER_ALPHA)
                isAccent -> tint
                else -> SECONDARY_FILL
            }
        g2.color = fillColor
        g2.fillRoundRect(0, 0, width, height, arc, arc)
        if (!isAccent) {
            g2.color = SECONDARY_BORDER
            g2.drawRoundRect(0, 0, width - 1, height - 1, arc, arc)
        }
    }

    private fun shade(
        color: Color,
        alpha: Int,
    ): Color = Color(color.red, color.green, color.blue, alpha)

    companion object {
        private const val WIDTH = 160
        private const val HEIGHT = 32
        private const val PADDING_Y = 6
        private const val PADDING_X = 14
        private const val FONT_SIZE = 13
        private const val ARC = 6
        private const val HOVER_ALPHA = 220
        private const val PRESSED_ALPHA = 180

        // Near-black ink used on top of the accent fill so the label reads
        // cleanly on golds / oranges / pastels alike. Same color in both themes
        // because the button uses the accent fill directly — it's light-on-dark
        // on its own, regardless of the surrounding IDE theme.
        private const val ACCENT_INK_RGB = 0x1F1F28
        private val ACCENT_INK: JBColor = JBColor(Color(ACCENT_INK_RGB), Color(ACCENT_INK_RGB))

        // Secondary (close) button fill + border — translucent tint that adapts
        // to light/dark mode ink. Named constants rather than inline numbers so
        // detekt's MagicNumber check passes without an @Suppress.
        private const val RGB_MIN = 0
        private const val RGB_MAX = 255
        private const val SECONDARY_FILL_ALPHA = 16
        private const val SECONDARY_BORDER_ALPHA = 48

        private val SECONDARY_FILL: JBColor =
            JBColor(
                Color(RGB_MIN, RGB_MIN, RGB_MIN, SECONDARY_FILL_ALPHA),
                Color(RGB_MAX, RGB_MAX, RGB_MAX, SECONDARY_FILL_ALPHA),
            )
        private val SECONDARY_BORDER: JBColor =
            JBColor(
                Color(RGB_MIN, RGB_MIN, RGB_MIN, SECONDARY_BORDER_ALPHA),
                Color(RGB_MAX, RGB_MAX, RGB_MAX, SECONDARY_BORDER_ALPHA),
            )
    }
}
