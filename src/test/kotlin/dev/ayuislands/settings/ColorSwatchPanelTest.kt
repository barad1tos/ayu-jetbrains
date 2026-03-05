package dev.ayuislands.settings

import dev.ayuislands.accent.AccentColor
import java.awt.Cursor
import java.awt.event.MouseEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ColorSwatchPanelTest {
    private val testColors =
        listOf(
            AccentColor("#FF6A00", "Orange"),
            AccentColor("#5C6773", "Gray"),
        )

    private fun createMouseEvent(source: java.awt.Component): MouseEvent =
        MouseEvent(source, MouseEvent.MOUSE_CLICKED, System.currentTimeMillis(), 0, 5, 5, 1, false)

    @Test
    fun `click on enabled swatch fires onColorSelected`() {
        var selectedColor: AccentColor? = null
        val panel = ColorSwatchPanel(testColors) { color -> selectedColor = color }

        val swatch = panel.getComponent(0)
        assertTrue(swatch.isEnabled, "Swatch should be enabled by default")

        val event = createMouseEvent(swatch)
        swatch.mouseListeners.forEach { listener -> listener.mouseClicked(event) }

        assertEquals(testColors[0], selectedColor, "Callback should have been fired with the first color")
    }

    @Test
    fun `click on disabled swatch does not fire onColorSelected`() {
        var callbackFired = false
        val panel = ColorSwatchPanel(testColors) { callbackFired = true }

        val swatch = panel.getComponent(0)
        swatch.isEnabled = false

        val event = createMouseEvent(swatch)
        swatch.mouseListeners.forEach { listener -> listener.mouseClicked(event) }

        assertFalse(callbackFired, "Callback should not fire when swatch is disabled")
    }

    @Test
    fun `disabled swatch has default cursor`() {
        val panel = ColorSwatchPanel(testColors) {}

        val swatch = panel.getComponent(0)
        swatch.isEnabled = false

        assertEquals(
            Cursor.getDefaultCursor(),
            swatch.cursor,
            "Disabled swatch should have default cursor",
        )
    }

    @Test
    fun `enabled swatch has hand cursor`() {
        val panel = ColorSwatchPanel(testColors) {}

        val swatch = panel.getComponent(0)
        swatch.isEnabled = true

        assertEquals(
            Cursor.getPredefinedCursor(Cursor.HAND_CURSOR),
            swatch.cursor,
            "Enabled swatch should have hand cursor",
        )
    }
}
