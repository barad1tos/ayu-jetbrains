package dev.ayuislands.accent.toolbar.popup

import com.intellij.util.ui.EmptyIcon
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Graphics2D
import java.awt.event.MouseEvent
import java.awt.image.BufferedImage
import javax.swing.Icon
import javax.swing.JLabel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Locks the [ToggleTile] composite:
 *  - composite has icon + label + [ToggleSwitch] in horizontal layout,
 *  - clicking anywhere on the tile flips the bound switch (tile is the click target,
 *    not only the switch glyph — a long-standing usability win),
 *  - hover state fills tile background with hoverBackground,
 *  - bound state field survives a tile click (binding sink works end-to-end).
 */
class ToggleTileTest {
    @Test
    fun `tile lays icon + label + switch via BorderLayout`() {
        val tile = newTile()
        // BorderLayout: WEST = icon JLabel, CENTER = label JLabel, EAST = ToggleSwitch.
        val layout = tile.layout as BorderLayout
        val west = layout.getLayoutComponent(BorderLayout.WEST)
        val center = layout.getLayoutComponent(BorderLayout.CENTER)
        val east = layout.getLayoutComponent(BorderLayout.EAST)
        assertTrue(west is JLabel, "WEST slot should be the icon JLabel; got ${west?.javaClass}")
        assertTrue(center is JLabel, "CENTER slot should be the label JLabel; got ${center?.javaClass}")
        assertTrue(east is ToggleSwitch, "EAST slot should be the ToggleSwitch; got ${east?.javaClass}")
    }

    @Test
    fun `clicking anywhere on the tile flips the bound switch`() {
        var captured: Boolean? = null
        val switch =
            ToggleSwitch(
                initialSelected = false,
                accentSupplier = { "#FFB454" },
            ) { newValue ->
                captured = newValue
            }
        val tile =
            ToggleTile(
                icon = EmptyIcon.create(SAMPLE_ICON_SIZE) as Icon,
                label = "Chrome tinting",
                toggleSwitch = switch,
            )
        tile.setSize(SAMPLE_TILE_WIDTH, SAMPLE_TILE_HEIGHT)

        assertFalse(switch.isSelected)
        tile.dispatchEvent(
            MouseEvent(
                tile,
                MouseEvent.MOUSE_CLICKED,
                0L,
                0,
                SAMPLE_TILE_WIDTH / 4,
                SAMPLE_TILE_HEIGHT / 2,
                1,
                false,
                MouseEvent.BUTTON1,
            ),
        )
        assertTrue(switch.isSelected, "tile click MUST flip the bound switch")
        assertEquals(true, captured, "switch listener MUST fire with new value when tile is clicked")
    }

    @Test
    fun `hover state fills tile background with hoverBackground (paintForTest)`() {
        val tile = newTile()
        tile.setSize(SAMPLE_TILE_WIDTH, SAMPLE_TILE_HEIGHT)
        tile.setHoveredForTest(true)

        val img = BufferedImage(SAMPLE_TILE_WIDTH, SAMPLE_TILE_HEIGHT, BufferedImage.TYPE_INT_ARGB)
        val g2: Graphics2D = img.createGraphics()
        try {
            tile.paintForTest(g2)
        } finally {
            g2.dispose()
        }
        val sampled = Color(img.getRGB(2, 2), true)
        // Don't assert exact RGB — under different LAFs the JBColor namedColor resolves
        // differently. Assert that the hover sample is NOT fully transparent and matches
        // the JBUI.CurrentTheme.ActionButton.hoverBackground() value.
        val expected =
            com.intellij.util.ui.JBUI.CurrentTheme.ActionButton
                .hoverBackground()
        assertEquals(expected.rgb, sampled.rgb, "hover should fill with ActionButton.hoverBackground")
    }

    @Test
    fun `idle state leaves tile background unpainted (parent shows through)`() {
        val tile = newTile()
        tile.setSize(SAMPLE_TILE_WIDTH, SAMPLE_TILE_HEIGHT)
        // Default isHoveredForTest = false.

        val img = BufferedImage(SAMPLE_TILE_WIDTH, SAMPLE_TILE_HEIGHT, BufferedImage.TYPE_INT_ARGB)
        val g2: Graphics2D = img.createGraphics()
        try {
            tile.paintForTest(g2)
        } finally {
            g2.dispose()
        }
        val sampled = Color(img.getRGB(2, 2), true)
        assertEquals(0, sampled.alpha, "idle tile should NOT paint background (alpha=0); got $sampled")
    }

    @Test
    fun `tile exposes its toggle switch as a read-only field`() {
        val switch =
            ToggleSwitch(initialSelected = true, accentSupplier = { "#FFB454" }, listener = {})
        val tile =
            ToggleTile(
                icon = EmptyIcon.create(SAMPLE_ICON_SIZE) as Icon,
                label = "Glow",
                toggleSwitch = switch,
            )
        assertSame(switch, tile.toggleSwitch, "tile should expose the same switch instance")
        assertTrue(tile.toggleSwitch.isSelected, "switch state should be readable through tile.toggleSwitch")
    }

    private fun newTile(): ToggleTile {
        val switch =
            ToggleSwitch(initialSelected = false, accentSupplier = { "#FFB454" }, listener = {})
        return ToggleTile(
            icon = EmptyIcon.create(SAMPLE_ICON_SIZE) as Icon,
            label = "Chrome tinting",
            toggleSwitch = switch,
        )
    }

    private companion object {
        const val SAMPLE_ICON_SIZE: Int = 16
        const val SAMPLE_TILE_WIDTH: Int = 134
        const val SAMPLE_TILE_HEIGHT: Int = 32
    }
}
