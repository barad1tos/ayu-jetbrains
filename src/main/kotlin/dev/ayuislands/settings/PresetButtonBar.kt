package dev.ayuislands.settings

import com.intellij.util.ui.JBUI
import dev.ayuislands.glow.GlowAnimation
import dev.ayuislands.glow.GlowPreset
import java.awt.AWTEvent
import java.awt.Color
import java.awt.Cursor
import java.awt.FlowLayout
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.GridLayout
import java.awt.RenderingHints
import java.awt.Toolkit
import java.awt.event.AWTEventListener
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.geom.RoundRectangle2D
import javax.swing.ButtonGroup
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JToggleButton
import javax.swing.JWindow
import javax.swing.SwingUtilities
import javax.swing.Timer
import javax.swing.UIManager

/**
 * Row of toggle buttons for glow presets.
 *
 * Named presets (Whisper, Ambient, Neon, Cyberpunk) show an animated card
 * expanding from the button center with animation variants. Custom has no card.
 *
 * @param onPresetSelected called when a preset is clicked directly (uses default animation)
 * @param onPresetWithAnimation called when an animation is chosen from the card
 */
class PresetButtonBar(
    private val onPresetSelected: (GlowPreset) -> Unit,
    private val onPresetWithAnimation: (GlowPreset, GlowAnimation) -> Unit,
) : JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)) {
    private val buttons = mutableMapOf<GlowPreset, JToggleButton>()
    private val buttonGroup = ButtonGroup()
    private var hoverTimer: Timer? = null
    private var activeCard: JWindow? = null
    private var expandTimer: Timer? = null
    private var clickOutsideListener: AWTEventListener? = null

    var selectedPreset: GlowPreset = GlowPreset.WHISPER
        set(value) {
            field = value
            buttons[value]?.isSelected = true
        }

    init {
        for (preset in GlowPreset.entries) {
            val button = JToggleButton(preset.displayName)
            buttons[preset] = button
            buttonGroup.add(button)
            add(button)

            button.addActionListener {
                if (button.isSelected) {
                    onPresetSelected(preset)
                }
            }

            if (preset != GlowPreset.CUSTOM) {
                button.addMouseListener(
                    object : MouseAdapter() {
                        override fun mouseEntered(e: MouseEvent) {
                            startHoverTimer(preset, button)
                        }

                        override fun mouseExited(e: MouseEvent) {
                            cancelHoverTimer()
                        }
                    },
                )
            }
        }

        buttons[selectedPreset]?.isSelected = true
    }

    fun setAllEnabled(enabled: Boolean) {
        for (button in buttons.values) {
            button.isEnabled = enabled
        }
    }

    private fun startHoverTimer(
        preset: GlowPreset,
        anchor: JToggleButton,
    ) {
        cancelHoverTimer()
        hoverTimer =
            Timer(HOVER_DELAY_MS) {
                showAnimatedCard(preset, anchor)
            }.apply {
                isRepeats = false
                start()
            }
    }

    private fun cancelHoverTimer() {
        hoverTimer?.stop()
        hoverTimer = null
    }

    private fun showAnimatedCard(
        preset: GlowPreset,
        anchor: JToggleButton,
    ) {
        dismissCard()

        val itemsPanel = buildCardItems(preset)
        val popupBg =
            UIManager.getColor("Popup.background")
                ?: UIManager.getColor("Panel.background")
        val borderColor = JBUI.CurrentTheme.Popup.borderColor(true)
        itemsPanel.background = popupBg

        val targetWidth = itemsPanel.preferredSize.width + 2
        val targetHeight = itemsPanel.preferredSize.height + 2

        // Clip-based reveal: window stays at final size, content clips from center outward.
        // This avoids native window resizing which causes tearing on macOS.
        var revealProgress = 0f

        val wrapper =
            object : JPanel(null) {
                override fun doLayout() {
                    if (componentCount > 0) {
                        val child = getComponent(0)
                        child.setBounds(1, 1, width - 2, height - 2)
                    }
                }

                override fun paint(g: Graphics) {
                    val g2 = g.create() as Graphics2D
                    try {
                        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                        val revealHeight = (height * revealProgress).toInt().coerceAtLeast(0)
                        val clipY = (height - revealHeight) / 2
                        g2.clip(
                            RoundRectangle2D.Float(
                                0f,
                                clipY.toFloat(),
                                width.toFloat(),
                                revealHeight.toFloat(),
                                CARD_ARC.toFloat(),
                                CARD_ARC.toFloat(),
                            ),
                        )
                        g2.color = popupBg
                        g2.fillRect(0, 0, width, height)
                        super.paint(g2)
                        g2.color = borderColor
                        g2.draw(
                            RoundRectangle2D.Float(
                                0f,
                                clipY.toFloat(),
                                width.toFloat() - 1,
                                revealHeight.toFloat() - 1,
                                CARD_ARC.toFloat(),
                                CARD_ARC.toFloat(),
                            ),
                        )
                    } finally {
                        g2.dispose()
                    }
                }
            }
        wrapper.isOpaque = false
        wrapper.add(itemsPanel)

        val ownerWindow = SwingUtilities.getWindowAncestor(this) ?: return
        val card = JWindow(ownerWindow)
        card.background = Color(0, 0, 0, 0)
        card.contentPane = wrapper

        val anchorScreen = anchor.locationOnScreen
        val centerY = anchorScreen.y + anchor.height / 2
        val x = anchorScreen.x

        card.setBounds(x, centerY - targetHeight / 2, targetWidth, targetHeight)
        card.isVisible = true
        activeCard = card

        var frame = 0
        expandTimer?.stop()
        expandTimer =
            Timer(FRAME_INTERVAL_MS) { evt ->
                frame++
                val progress = (frame.toFloat() / EXPAND_FRAMES).coerceAtMost(1f)
                revealProgress = easeOutCubic(progress)
                wrapper.repaint()
                if (progress >= 1f) {
                    (evt.source as Timer).stop()
                }
            }.apply {
                isRepeats = true
                start()
            }

        val listener =
            AWTEventListener { event ->
                if (event is MouseEvent && event.id == MouseEvent.MOUSE_PRESSED) {
                    if (!card.bounds.contains(event.locationOnScreen)) {
                        SwingUtilities.invokeLater { dismissCard() }
                    }
                }
            }
        Toolkit.getDefaultToolkit().addAWTEventListener(listener, AWTEvent.MOUSE_EVENT_MASK)
        clickOutsideListener = listener
    }

    private fun dismissCard() {
        expandTimer?.stop()
        expandTimer = null
        clickOutsideListener?.let {
            Toolkit.getDefaultToolkit().removeAWTEventListener(it)
        }
        clickOutsideListener = null
        activeCard?.dispose()
        activeCard = null
    }

    private fun buildCardItems(preset: GlowPreset): JPanel {
        val panel = JPanel(GridLayout(GlowAnimation.entries.size, 1))
        panel.isOpaque = false

        for (animation in GlowAnimation.entries) {
            val item = JLabel(buildItemText(animation, preset))
            item.border = JBUI.Borders.empty(ITEM_V_PAD, ITEM_H_PAD)
            item.cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            item.isOpaque = false
            if (animation == preset.animation) {
                item.font = item.font.deriveFont(Font.BOLD)
            }
            item.addMouseListener(
                object : MouseAdapter() {
                    override fun mouseClicked(e: MouseEvent) {
                        onPresetWithAnimation(preset, animation)
                        dismissCard()
                    }

                    override fun mouseEntered(e: MouseEvent) {
                        item.isOpaque = true
                        item.background =
                            JBUI.CurrentTheme.List.Selection
                                .background(true)
                        item.repaint()
                    }

                    override fun mouseExited(e: MouseEvent) {
                        item.isOpaque = false
                        item.repaint()
                    }
                },
            )
            panel.add(item)
        }
        return panel
    }

    private fun buildItemText(
        animation: GlowAnimation,
        preset: GlowPreset,
    ): String {
        val check = if (animation == preset.animation) "\u2713 " else "  "
        return "$check${animation.displayName}"
    }

    companion object {
        private const val HOVER_DELAY_MS = 300
        private const val ITEM_V_PAD = 4
        private const val ITEM_H_PAD = 8
        private const val FRAME_INTERVAL_MS = 12
        private const val EXPAND_FRAMES = 12
        private const val CARD_ARC = 6

        private fun easeOutCubic(t: Float): Float {
            val t1 = t - 1f
            return t1 * t1 * t1 + 1f
        }
    }
}
