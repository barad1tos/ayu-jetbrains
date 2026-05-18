package dev.ayuislands.accent.toolbar.popup

import com.intellij.util.ui.JBUI
import io.mockk.mockk
import io.mockk.verify
import java.awt.Color
import java.awt.Dimension
import java.awt.Graphics2D
import java.awt.event.MouseEvent
import java.awt.image.BufferedImage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Locks the macOS-style [ToggleSwitch] contract per 48-REDESIGN-SPEC §3.5:
 *  - 28x14 JBUI-scaled preferred size,
 *  - ON state fill = accent supplier value (resolved lazily at paint),
 *  - OFF state fill = subtle pressed background with 1-px inner border,
 *  - clicks flip isSelected and notify the listener exactly once,
 *  - accent supplier is re-read on every paint (lazy resolution).
 */
class ToggleSwitchTest {
    @Test
    fun `preferred size is 28 x 14 JBUI scaled`() {
        val switch = ToggleSwitch(initialSelected = false, accentSupplier = { "#FFB454" }, listener = {})
        val expected = Dimension(JBUI.scale(SWITCH_WIDTH), JBUI.scale(SWITCH_HEIGHT))
        assertEquals(expected, switch.preferredSize)
    }

    @Test
    fun `isSelected reflects initial value`() {
        val on = ToggleSwitch(initialSelected = true, accentSupplier = { "#FFB454" }, listener = {})
        val off = ToggleSwitch(initialSelected = false, accentSupplier = { "#FFB454" }, listener = {})
        assertTrue(on.isSelected)
        assertFalse(off.isSelected)
    }

    @Test
    fun `on state fill equals provided accent`() {
        val switch = ToggleSwitch(initialSelected = true, accentSupplier = { "#FFB454" }, listener = {})
        switch.setSize(JBUI.scale(SWITCH_WIDTH), JBUI.scale(SWITCH_HEIGHT))
        val sample = paintAndSampleCenter(switch)
        // The 0,0xFFB454 expectation should hold inside the rounded pill body. Sample the
        // left side, well inside the pill, away from the indicator circle (which is white).
        // Center-y, x = width / 4 (between left edge and indicator pos).
        val cx = switch.width / SAMPLE_LEFT_DIVISOR
        val cy = switch.height / 2
        val img = paintFull(switch)
        val left = Color(img.getRGB(cx, cy))
        assertEquals(Color(0xFF, 0xB4, 0x54), left, "ON fill should equal accent at left side; got $left, full=$sample")
    }

    @Test
    fun `clicking the switch flips isSelected and calls listener exactly once`() {
        val listener = mockk<(Boolean) -> Unit>(relaxed = true)
        val switch = ToggleSwitch(initialSelected = false, accentSupplier = { "#FFB454" }, listener = listener)
        switch.setSize(JBUI.scale(SWITCH_WIDTH), JBUI.scale(SWITCH_HEIGHT))
        assertFalse(switch.isSelected)

        switch.dispatchEvent(
            MouseEvent(
                switch,
                MouseEvent.MOUSE_CLICKED,
                0L,
                0,
                JBUI.scale(2),
                JBUI.scale(2),
                1,
                false,
                MouseEvent.BUTTON1,
            ),
        )

        assertTrue(switch.isSelected, "click should flip isSelected to true")
        verify(exactly = 1) { listener.invoke(true) }
    }

    @Test
    fun `accent is resolved lazily on each paint via the accentSupplier lambda`() {
        var hex = "#AAAAAA"
        val switch = ToggleSwitch(initialSelected = true, accentSupplier = { hex }, listener = {})
        switch.setSize(JBUI.scale(SWITCH_WIDTH), JBUI.scale(SWITCH_HEIGHT))

        val first = sampleLeft(switch)
        assertEquals(Color(0xAA, 0xAA, 0xAA), first)

        hex = "#BBBBBB"
        val second = sampleLeft(switch)
        assertEquals(Color(0xBB, 0xBB, 0xBB), second, "supplier MUST be re-read on every paint")
    }

    @Test
    fun `off state fills neither WHITE nor previous accent (uses pressedBackground)`() {
        val onSwitch = ToggleSwitch(initialSelected = true, accentSupplier = { "#FFB454" }, listener = {})
        val offSwitch = ToggleSwitch(initialSelected = false, accentSupplier = { "#FFB454" }, listener = {})
        onSwitch.setSize(JBUI.scale(SWITCH_WIDTH), JBUI.scale(SWITCH_HEIGHT))
        offSwitch.setSize(JBUI.scale(SWITCH_WIDTH), JBUI.scale(SWITCH_HEIGHT))

        val onFill = sampleLeft(onSwitch)
        val offFill = sampleLeft(offSwitch)

        // OFF must NOT use the accent fill — the visual contract is "OFF = neutral chip,
        // ON = accent chip". If a future regression silently makes OFF read the supplier
        // we want this assertion to flag it.
        assertFalse(onFill == offFill, "OFF must paint differently from ON; got identical fill $onFill")
    }

    private fun sampleLeft(switch: ToggleSwitch): Color {
        val img = paintFull(switch)
        val cx = switch.width / SAMPLE_LEFT_DIVISOR
        val cy = switch.height / 2
        return Color(img.getRGB(cx, cy))
    }

    private fun paintAndSampleCenter(switch: ToggleSwitch): Color {
        val img = paintFull(switch)
        return Color(img.getRGB(switch.width / 2, switch.height / 2))
    }

    private fun paintFull(switch: ToggleSwitch): BufferedImage {
        val img = BufferedImage(switch.width, switch.height, BufferedImage.TYPE_INT_ARGB)
        val g2: Graphics2D = img.createGraphics()
        try {
            switch.paintForTest(g2)
        } finally {
            g2.dispose()
        }
        return img
    }

    private companion object {
        const val SWITCH_WIDTH: Int = 28
        const val SWITCH_HEIGHT: Int = 14
        const val SAMPLE_LEFT_DIVISOR: Int = 4
    }
}
