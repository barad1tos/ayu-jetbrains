package dev.ayuislands.settings

import dev.ayuislands.accent.AccentColor
import java.awt.Cursor
import java.awt.event.MouseEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AccentColorPanelTest {
    private val testPresets =
        listOf(
            AccentColor("#FF6A00", "Orange"),
            AccentColor("#5C6773", "Gray"),
        )

    private fun createMouseEvent(source: java.awt.Component): MouseEvent =
        MouseEvent(source, MouseEvent.MOUSE_CLICKED, System.currentTimeMillis(), 0, 5, 5, 1, false)

    @Test
    fun `click on preset fires onPresetSelected`() {
        var selectedColor: AccentColor? = null
        val panel =
            AccentColorPanel(
                presets = testPresets,
                onPresetSelected = { color -> selectedColor = color },
                onCustomTrigger = {},
                onReset = {},
            )

        val presetGrid = panel.components.last()
        val firstPreset = (presetGrid as javax.swing.JPanel).getComponent(0)
        assertTrue(firstPreset.isEnabled, "Preset should be enabled by default")

        val event = createMouseEvent(firstPreset)
        firstPreset.mouseListeners.forEach { listener -> listener.mouseClicked(event) }

        assertEquals(testPresets[0], selectedColor, "Callback should fire with first preset color")
        assertEquals(testPresets[0].hex, panel.selectedPreset, "selectedPreset should be set")
    }

    @Test
    fun `click on disabled preset does not fire callback`() {
        var callbackFired = false
        val panel =
            AccentColorPanel(
                presets = testPresets,
                onPresetSelected = { callbackFired = true },
                onCustomTrigger = {},
                onReset = {},
            )

        val presetGrid = panel.components.last()
        val firstPreset = (presetGrid as javax.swing.JPanel).getComponent(0)
        firstPreset.isEnabled = false

        val event = createMouseEvent(firstPreset)
        firstPreset.mouseListeners.forEach { listener -> listener.mouseClicked(event) }

        assertFalse(callbackFired, "Callback should not fire when preset is disabled")
    }

    @Test
    fun `selectedPreset property triggers repaint without error`() {
        val panel =
            AccentColorPanel(
                presets = testPresets,
                onPresetSelected = {},
                onCustomTrigger = {},
                onReset = {},
            )

        panel.selectedPreset = testPresets[0].hex
        assertEquals(testPresets[0].hex, panel.selectedPreset)

        panel.selectedPreset = null
        assertNull(panel.selectedPreset)
    }

    @Test
    fun `customColor property triggers repaint without error`() {
        val panel =
            AccentColorPanel(
                presets = testPresets,
                onPresetSelected = {},
                onCustomTrigger = {},
                onReset = {},
            )

        panel.customColor = "#AABBCC"
        assertEquals("#AABBCC", panel.customColor)

        panel.customColor = null
        assertNull(panel.customColor)
    }

    @Test
    fun `disabled preset has default cursor`() {
        val panel =
            AccentColorPanel(
                presets = testPresets,
                onPresetSelected = {},
                onCustomTrigger = {},
                onReset = {},
            )

        val presetGrid = panel.components.last()
        val firstPreset = (presetGrid as javax.swing.JPanel).getComponent(0)
        firstPreset.isEnabled = false

        assertEquals(
            Cursor.getDefaultCursor(),
            firstPreset.cursor,
            "Disabled preset should have default cursor",
        )
    }

    @Test
    fun `enabled preset has hand cursor`() {
        val panel =
            AccentColorPanel(
                presets = testPresets,
                onPresetSelected = {},
                onCustomTrigger = {},
                onReset = {},
            )

        val presetGrid = panel.components.last()
        val firstPreset = (presetGrid as javax.swing.JPanel).getComponent(0)
        firstPreset.isEnabled = true

        assertEquals(
            Cursor.getPredefinedCursor(Cursor.HAND_CURSOR),
            firstPreset.cursor,
            "Enabled preset should have hand cursor",
        )
    }

    @Test
    fun `reset callback fires on reset click`() {
        var resetFired = false
        val panel =
            AccentColorPanel(
                presets = testPresets,
                onPresetSelected = {},
                onCustomTrigger = {},
                onReset = { resetFired = true },
            )

        val leftWithSeparator = panel.components[0] as javax.swing.JPanel
        val leftColumn = leftWithSeparator.components[0] as javax.swing.JPanel
        val resetWrapper = leftColumn.getComponent(2) as javax.swing.JPanel
        val resetLabel = resetWrapper.getComponent(0)

        val event = createMouseEvent(resetLabel)
        resetLabel.mouseListeners.forEach { listener -> listener.mouseClicked(event) }

        assertTrue(resetFired, "Reset callback should fire")
    }

    @Test
    fun `custom trigger callback fires on click`() {
        var triggerFired = false
        val panel =
            AccentColorPanel(
                presets = testPresets,
                onPresetSelected = {},
                onCustomTrigger = { triggerFired = true },
                onReset = {},
            )

        val leftWithSeparator = panel.components[0] as javax.swing.JPanel
        val leftColumn = leftWithSeparator.components[0] as javax.swing.JPanel
        val customTrigger = leftColumn.getComponent(0)

        val event = createMouseEvent(customTrigger)
        customTrigger.mouseListeners.forEach { listener -> listener.mouseClicked(event) }

        assertTrue(triggerFired, "Custom trigger callback should fire")
    }
}
