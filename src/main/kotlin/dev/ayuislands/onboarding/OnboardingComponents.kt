@file:Suppress("MatchingDeclarationName")

package dev.ayuislands.onboarding

import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Cursor
import java.awt.Dimension
import java.awt.Font
import java.awt.GradientPaint
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.geom.RoundRectangle2D
import javax.swing.JPanel

/**
 * Shared visual primitives for onboarding wizard panels (free and premium).
 *
 * Card painting stays panel-local because the free wizard's variant cards
 * and the premium wizard's preset cards share almost no paint code.
 */

@Suppress("MagicNumber")
internal object OnboardingCardChrome {
    const val CARD_ARC = 14
    const val SHADOW_LAYERS = 4
    const val SHADOW_OFFSET_Y = 2
    const val SHADOW_BASE_ALPHA = 12
    const val HIGHLIGHT_HEIGHT = 3
    const val CARD_HOVER_TOP_ALPHA = 40
    const val CARD_HOVER_BOTTOM_ALPHA = 10
    const val CARD_BORDER_HOVER_ALPHA = 100
    val CARD_BG_COLOR: Color = Color(0x17, 0x1B, 0x24)
    val CARD_BORDER_COLOR: Color = Color(0x2A, 0x2F, 0x3A)
    val HIGHLIGHT_TOP_COLOR: Color = Color(255, 255, 255, 8)
    val HIGHLIGHT_BOTTOM_COLOR: Color = Color(255, 255, 255, 0)
}

/**
 * Shared card chrome — drop shadow, fill (hovered gradient or base color),
 * glass highlight clip, and border — used by both wizard panels for their
 * variant cards, preset cards, and footer rail cards.
 */
@Suppress("LongParameterList")
internal fun paintCardChrome(
    g2: Graphics2D,
    width: Int,
    height: Int,
    hovered: Boolean,
    tintColor: Color,
    baseFill: Color = OnboardingCardChrome.CARD_BG_COLOR,
) {
    val arc = JBUI.scale(OnboardingCardChrome.CARD_ARC)

    // Drop shadow
    val shadowOffset = JBUI.scale(OnboardingCardChrome.SHADOW_OFFSET_Y)
    for (i in OnboardingCardChrome.SHADOW_LAYERS downTo 1) {
        g2.color = Color(0, 0, 0, OnboardingCardChrome.SHADOW_BASE_ALPHA * i)
        g2.fillRoundRect(
            i,
            i + shadowOffset,
            width - 2 * i,
            height - 2 * i,
            arc,
            arc,
        )
    }

    // Fill
    if (hovered) {
        val topColor =
            Color(tintColor.red, tintColor.green, tintColor.blue, OnboardingCardChrome.CARD_HOVER_TOP_ALPHA)
        val bottomColor =
            Color(tintColor.red, tintColor.green, tintColor.blue, OnboardingCardChrome.CARD_HOVER_BOTTOM_ALPHA)
        g2.paint = GradientPaint(0f, 0f, topColor, 0f, height.toFloat(), bottomColor)
    } else {
        g2.color = baseFill
    }
    g2.fillRoundRect(0, 0, width, height, arc, arc)

    // Glass highlight
    val highlightHeight = JBUI.scale(OnboardingCardChrome.HIGHLIGHT_HEIGHT)
    val clip =
        RoundRectangle2D.Float(
            1f,
            1f,
            (width - 2).toFloat(),
            (height - 2).toFloat(),
            arc.toFloat(),
            arc.toFloat(),
        )
    val oldClip = g2.clip
    g2.clip(clip)
    g2.paint =
        GradientPaint(
            0f,
            1f,
            OnboardingCardChrome.HIGHLIGHT_TOP_COLOR,
            0f,
            highlightHeight.toFloat(),
            OnboardingCardChrome.HIGHLIGHT_BOTTOM_COLOR,
        )
    g2.fillRect(0, 0, width, highlightHeight)
    g2.clip = oldClip

    // Border
    g2.color =
        if (hovered) {
            Color(
                tintColor.red,
                tintColor.green,
                tintColor.blue,
                OnboardingCardChrome.CARD_BORDER_HOVER_ALPHA,
            )
        } else {
            OnboardingCardChrome.CARD_BORDER_COLOR
        }
    g2.drawRoundRect(0, 0, width - 1, height - 1, arc, arc)
}

/**
 * Lock a JLabel to its intrinsic preferred size on both axes.
 *
 * Without this, BoxLayout.Y_AXIS lets JLabel stretch horizontally (pushing
 * text to the left) and BoxLayout.X_AXIS with glue+label+glue can't center
 * it reliably. Limiting the max size makes glue absorb the slack and the
 * label sit at its natural size.
 */
internal fun clampLabelToPreferred(label: JBLabel) {
    val pref = label.preferredSize
    label.maximumSize = Dimension(pref.width, pref.height)
    label.minimumSize = Dimension(pref.width, pref.height)
}

@Suppress("MagicNumber")
internal object OnboardingColors {
    // Accent (Ayu Mirage palette)
    val ACCENT: Color = Color(0xFF, 0xCC, 0x66)
    val ACCENT_HOVER: Color = Color(0xFF, 0xD8, 0x80)
    val ACCENT_TEXT: Color = Color(0x0B, 0x0E, 0x14)

    // Accent button extras
    val ACCENT_BORDER: Color = Color(0xC0, 0x96, 0x30)
    val ACCENT_HIGHLIGHT: Color = Color(0xFF, 0xE0, 0x99, 60)
    val ACCENT_PRESSED: Color = Color(0xD9, 0xAD, 0x50)

    // Secondary button
    val SECONDARY_BG: Color = Color(0x18, 0x1C, 0x25)
    val SECONDARY_HOVER: Color = Color(0x1E, 0x23, 0x2E)
    val SECONDARY_PRESSED: Color = Color(0x14, 0x18, 0x20)
    val SECONDARY_BORDER: Color = Color(0x3A, 0x40, 0x4C)
    val SECONDARY_HIGHLIGHT: Color = Color(0xFF, 0xFF, 0xFF, 10)
    val SECONDARY_TEXT: Color = Color(0xB0, 0xB8, 0xC4)
}

private const val BTN_ARC = 10
private const val BTN_PADDING_V = 8
private const val BTN_PADDING_H = 20
private const val BTN_WIDTH = 160
private const val BTN_HEIGHT = 36
private const val BTN_FONT_SIZE = 13
private const val HIGHLIGHT_INSET = 1

/**
 * Builds a styled wizard button with accent or secondary visual variant.
 * Used by both free and premium onboarding panels.
 */
internal fun createStyledButton(
    text: String,
    isAccent: Boolean,
    onClick: () -> Unit,
): JPanel {
    val button =
        object : JPanel() {
            private var hovered = false
            private var pressed = false

            init {
                layout = BorderLayout()
                isOpaque = false
                cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                border = JBUI.Borders.empty(BTN_PADDING_V, BTN_PADDING_H)
                val btnSize = Dimension(JBUI.scale(BTN_WIDTH), JBUI.scale(BTN_HEIGHT))
                preferredSize = btnSize
                maximumSize = btnSize
                minimumSize = btnSize
                val label = JBLabel(text)
                label.horizontalAlignment = JBLabel.CENTER
                label.foreground =
                    if (isAccent) OnboardingColors.ACCENT_TEXT else OnboardingColors.SECONDARY_TEXT
                label.font =
                    label.font.deriveFont(
                        if (isAccent) Font.BOLD else Font.PLAIN,
                        JBUI.scale(BTN_FONT_SIZE).toFloat(),
                    )
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
                            pressed = false
                            repaint()
                        }

                        override fun mouseClicked(event: MouseEvent) {
                            onClick()
                        }
                    },
                )
            }

            override fun paintComponent(graphics: Graphics) {
                val g2 = graphics as Graphics2D
                g2.setRenderingHint(
                    RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON,
                )
                val arc = JBUI.scale(BTN_ARC)

                if (isAccent) {
                    paintAccentButton(g2, arc, hovered, pressed)
                } else {
                    paintSecondaryButton(g2, arc, hovered, pressed)
                }
            }
        }
    return button
}

private fun JPanel.paintAccentButton(
    g2: Graphics2D,
    arc: Int,
    hovered: Boolean,
    pressed: Boolean,
) {
    val fill =
        when {
            pressed -> OnboardingColors.ACCENT_PRESSED
            hovered -> OnboardingColors.ACCENT_HOVER
            else -> OnboardingColors.ACCENT
        }
    g2.color = fill
    g2.fillRoundRect(0, 0, width, height, arc, arc)
    g2.color = OnboardingColors.ACCENT_BORDER
    g2.drawRoundRect(0, 0, width - 1, height - 1, arc, arc)

    val oldClip = g2.clip
    g2.clipRect(1, 1, width - 2, arc)
    g2.color = OnboardingColors.ACCENT_HIGHLIGHT
    g2.drawRoundRect(
        HIGHLIGHT_INSET,
        HIGHLIGHT_INSET,
        width - HIGHLIGHT_INSET * 2 - 1,
        height - HIGHLIGHT_INSET * 2 - 1,
        arc - HIGHLIGHT_INSET,
        arc - HIGHLIGHT_INSET,
    )
    g2.clip = oldClip
}

private fun JPanel.paintSecondaryButton(
    g2: Graphics2D,
    arc: Int,
    hovered: Boolean,
    pressed: Boolean,
) {
    val fill =
        when {
            pressed -> OnboardingColors.SECONDARY_PRESSED
            hovered -> OnboardingColors.SECONDARY_HOVER
            else -> OnboardingColors.SECONDARY_BG
        }
    g2.color = fill
    g2.fillRoundRect(0, 0, width, height, arc, arc)
    g2.color = OnboardingColors.SECONDARY_BORDER
    g2.drawRoundRect(0, 0, width - 1, height - 1, arc, arc)

    val oldClip = g2.clip
    g2.clipRect(1, 1, width - 2, arc)
    g2.color = OnboardingColors.SECONDARY_HIGHLIGHT
    g2.drawRoundRect(
        HIGHLIGHT_INSET,
        HIGHLIGHT_INSET,
        width - HIGHLIGHT_INSET * 2 - 1,
        height - HIGHLIGHT_INSET * 2 - 1,
        arc - HIGHLIGHT_INSET,
        arc - HIGHLIGHT_INSET,
    )
    g2.clip = oldClip
}
