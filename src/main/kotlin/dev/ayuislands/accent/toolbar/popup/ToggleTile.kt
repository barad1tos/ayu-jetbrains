package dev.ayuislands.accent.toolbar.popup

import com.intellij.util.ui.JBUI
import org.jetbrains.annotations.TestOnly
import java.awt.BorderLayout
import java.awt.Cursor
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.Icon
import javax.swing.JLabel
import javax.swing.JPanel

/**
 * Composite cell for the 2x2 toggles grid per 48-REDESIGN-SPEC §3.5: leading icon,
 * centre label, trailing [ToggleSwitch]. The entire tile is the click target — clicking
 * the icon or label flips the bound switch (long-standing usability win — users hit a
 * 32-px tile, not a 14-px glyph).
 *
 * Hover state paints the tile background with [JBUI.CurrentTheme.ActionButton.hoverBackground];
 * idle state leaves the tile transparent so the parent's background shows through. The
 * inner [toggleSwitch] is exposed as a `val` so the popup composition can bind it to a
 * hidden [javax.swing.JCheckBox] via the Kotlin UI DSL `bindSelected` plumbing — that
 * preserves D-13 persistence semantics.
 *
 * Pattern A — mouse events already run on EDT.
 *
 * @param icon leading icon (typically `AllIcons.*` at 16x16).
 * @param label centre text.
 * @param toggleSwitch trailing pill switch; same instance is exposed via [toggleSwitch] field.
 */
internal class ToggleTile(
    icon: Icon,
    label: String,
    val toggleSwitch: ToggleSwitch,
) : JPanel(BorderLayout(JBUI.scale(Density.ACTION_GAP), 0)) {
    private var isHovered: Boolean = false

    init {
        isOpaque = false
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        border = JBUI.Borders.empty(0, JBUI.scale(Density.ACTION_GAP))

        add(JLabel(icon), BorderLayout.WEST)
        add(JLabel(label), BorderLayout.CENTER)
        add(toggleSwitch, BorderLayout.EAST)

        addMouseListener(
            object : MouseAdapter() {
                override fun mouseEntered(event: MouseEvent) {
                    isHovered = true
                    repaint()
                }

                override fun mouseExited(event: MouseEvent) {
                    isHovered = false
                    repaint()
                }

                override fun mouseClicked(event: MouseEvent) {
                    if (!isEnabled) return
                    toggleSwitch.flip()
                }
            },
        )
    }

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        try {
            paintTile(g2)
        } finally {
            g2.dispose()
        }
        super.paintComponent(g)
    }

    /**
     * Test seam — Pattern I. Lets unit tests sample tile background pixels without a
     * full Swing event loop. Hover state is driven via [setHoveredForTest].
     */
    @TestOnly
    internal fun paintForTest(g: Graphics2D) {
        paintTile(g)
    }

    /** Test seam — flip the hover flag without dispatching a real `MouseEntered`. */
    @TestOnly
    internal fun setHoveredForTest(hovered: Boolean) {
        isHovered = hovered
    }

    private fun paintTile(g2: Graphics2D) {
        if (!isHovered) return
        g2.color = JBUI.CurrentTheme.ActionButton.hoverBackground()
        g2.fillRect(0, 0, width, height)
    }
}
