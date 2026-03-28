package dev.ayuislands.glow

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
        val manager = FocusRingManager()
        assertTrue(manager.isTextInputComponent(JTextField()))
    }

    @Test
    fun `isTextInputComponent returns true for JComboBox`() {
        val manager = FocusRingManager()
        assertTrue(manager.isTextInputComponent(JComboBox<String>()))
    }

    @Test
    fun `isTextInputComponent returns false for JPanel`() {
        val manager = FocusRingManager()
        assertFalse(manager.isTextInputComponent(JPanel()))
    }

    @Test
    fun `isTextInputComponent returns false for JLabel`() {
        val manager = FocusRingManager()
        assertFalse(manager.isTextInputComponent(JLabel()))
    }

    @Test
    fun `isTextInputComponent returns false for JButton`() {
        val manager = FocusRingManager()
        assertFalse(manager.isTextInputComponent(JButton()))
    }

    @Test
    fun `removeFocusListeners is safe on fresh instance`() {
        val manager = FocusRingManager()
        manager.removeFocusListeners()
        // No exception thrown -- safe to call on empty listener map
    }

    @Test
    fun `dispose is safe on fresh instance`() {
        val manager = FocusRingManager()
        manager.dispose()
        // No exception thrown -- clears empty maps
    }
}
