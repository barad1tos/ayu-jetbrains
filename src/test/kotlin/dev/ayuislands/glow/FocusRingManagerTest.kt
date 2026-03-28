package dev.ayuislands.glow

import java.awt.Color
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JTextField
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FocusRingManagerTest {
    @Test
    fun `isTextInputComponent returns true for JTextField`() {
        assertTrue(FocusRingManager().isTextInputComponent(JTextField()))
    }

    @Test
    fun `isTextInputComponent returns true for JComboBox`() {
        assertTrue(FocusRingManager().isTextInputComponent(JComboBox<String>()))
    }

    @Test
    fun `isTextInputComponent returns false for JPanel`() {
        assertFalse(FocusRingManager().isTextInputComponent(JPanel()))
    }

    @Test
    fun `isTextInputComponent returns false for JLabel`() {
        assertFalse(FocusRingManager().isTextInputComponent(JLabel()))
    }

    @Test
    fun `isTextInputComponent returns false for JButton`() {
        assertFalse(FocusRingManager().isTextInputComponent(JButton()))
    }

    // Lifecycle safety tests

    @Test
    fun `removeFocusListeners is safe on fresh instance`() {
        FocusRingManager().removeFocusListeners()
    }

    @Test
    fun `dispose is safe on fresh instance`() {
        FocusRingManager().dispose()
    }

    // initializeFocusRingGlow / updateFocusRingGlow — exercise full code paths
    // Window.getWindows() returns empty array in unit tests, but the methods
    // still execute their full logic (loop body is skipped, not crashed)

    @Test
    fun `initializeFocusRingGlow completes without error`() {
        val manager = FocusRingManager()
        manager.initializeFocusRingGlow(
            Color.ORANGE,
            GlowStyle.SOFT,
            50,
        )
        // No exception — method handles empty window list gracefully
    }

    @Test
    fun `updateFocusRingGlow with enabled true completes without error`() {
        val manager = FocusRingManager()
        manager.updateFocusRingGlow(
            Color.ORANGE,
            GlowStyle.SOFT,
            50,
            enabled = true,
        )
    }

    @Test
    fun `updateFocusRingGlow with enabled false clears listeners`() {
        val manager = FocusRingManager()
        // First initialize, then disable
        manager.initializeFocusRingGlow(Color.ORANGE, GlowStyle.SOFT, 50)
        manager.updateFocusRingGlow(
            Color.ORANGE,
            GlowStyle.SOFT,
            50,
            enabled = false,
        )
        // No exception — disabled path removes listeners and skips window walk
    }

    @Test
    fun `dispose after initialize completes without error`() {
        val manager = FocusRingManager()
        manager.initializeFocusRingGlow(Color.ORANGE, GlowStyle.SOFT, 50)
        manager.dispose()
    }

    @Test
    fun `double initialize is safe`() {
        val manager = FocusRingManager()
        manager.initializeFocusRingGlow(Color.ORANGE, GlowStyle.SOFT, 50)
        manager.initializeFocusRingGlow(Color.RED, GlowStyle.SHARP_NEON, 80)
    }

    @Test
    fun `update after dispose is safe`() {
        val manager = FocusRingManager()
        manager.dispose()
        manager.updateFocusRingGlow(
            Color.ORANGE,
            GlowStyle.SOFT,
            50,
            enabled = true,
        )
    }
}
