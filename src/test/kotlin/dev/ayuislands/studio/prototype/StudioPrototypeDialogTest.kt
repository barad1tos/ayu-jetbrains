package dev.ayuislands.studio.prototype

import java.awt.Component
import java.awt.Container
import java.lang.reflect.InvocationTargetException
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JComboBox
import javax.swing.JList
import javax.swing.JSpinner
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StudioPrototypeDialogTest {
    @Test
    fun `apply retains the window and cancel discards only later edits`() =
        withDialog { dialog, panel ->
            component<JList<*>>(panel, "Surface selection").selectedIndex = 3
            val fontSize = component<JSpinner>(panel, "Font size")
            val applyButton = buttons(dialog).single { it.text == "Apply" }
            assertFalse(applyButton.isEnabled)

            fontSize.value = 18.0
            assertTrue(applyButton.isEnabled)
            applyButton.doClick()

            assertFalse(dialog.isDisposed)
            assertEquals(18f, panel.session.current.fontSize)
            assertFalse(applyButton.isEnabled)

            fontSize.value = 22.0
            buttons(dialog).single { it.text == "Cancel" }.doClick()

            assertTrue(dialog.isDisposed)
            assertEquals(18f, panel.session.current.fontSize)
            assertEquals(18f, panel.session.draft.fontSize)
            assertFalse(panel.session.hasPendingChanges)
        }

    @Test
    fun `rejected OK retains the draft until a successful retry closes the window`() =
        withDialog { dialog, panel ->
            component<JList<*>>(panel, "Surface selection").selectedIndex = 3
            component<JSpinner>(panel, "Font size").value = 18.0
            component<JComboBox<*>>(panel, "Preview result").selectedItem = StudioPrototypeOutcome.REJECTED

            buttons(dialog).single { it.text == "OK" }.doClick()

            assertFalse(dialog.isDisposed)
            assertEquals(14f, panel.session.current.fontSize)
            assertEquals(18f, panel.session.draft.fontSize)
            assertEquals(StudioPrototypeResult.REJECTED, panel.session.lastResult)

            component<JComboBox<*>>(panel, "Preview result").selectedItem = StudioPrototypeOutcome.SUCCESS
            buttons(dialog).single { it.text == "OK" }.doClick()

            assertTrue(dialog.isDisposed)
            assertTrue(dialog.isOK)
            assertEquals(18f, panel.session.current.fontSize)
        }

    @Test
    fun `partial OK survives reverted edits and keeps native Apply available`() =
        withDialog { dialog, panel ->
            component<JList<*>>(panel, "Surface selection").selectedIndex = 3
            component<JSpinner>(panel, "Font size").value = 18.0
            component<JComboBox<*>>(panel, "Preview result").selectedItem = StudioPrototypeOutcome.FONT_UNAVAILABLE

            buttons(dialog).single { it.text == "OK" }.doClick()

            assertFalse(dialog.isDisposed)
            assertEquals(StudioPrototypeResult.PARTIAL, panel.session.lastResult)
            assertFalse(panel.session.hasPendingChanges)
            val applyButton = buttons(dialog).single { it.text == "Apply" }
            assertTrue(applyButton.isEnabled)

            component<JList<*>>(panel, "Surface selection").selectedIndex = 0
            val glowEnabled = component<JCheckBox>(panel, "Glow enabled")
            glowEnabled.doClick()
            glowEnabled.doClick()
            assertFalse(panel.session.hasPendingChanges)
            assertTrue(applyButton.isEnabled)

            component<JComboBox<*>>(panel, "Preview result").selectedItem = StudioPrototypeOutcome.SUCCESS
            applyButton.doClick()

            assertFalse(dialog.isDisposed)
            assertEquals(18f, panel.session.current.fontSize)
            assertEquals(StudioPrototypeResult.APPLIED, panel.session.lastResult)
        }

    private fun withDialog(block: (StudioPrototypeDialog, StudioPrototypePanel) -> Unit) {
        onEdt {
            val dialog = StudioPrototypeDialog(null)
            try {
                val panel = descendants(dialog.contentPane).filterIsInstance<StudioPrototypePanel>().single()
                block(dialog, panel)
            } finally {
                if (!dialog.isDisposed) dialog.doCancelAction()
            }
        }
    }

    private fun buttons(dialog: StudioPrototypeDialog): List<JButton> =
        descendants(dialog.contentPane).filterIsInstance<JButton>().toList()

    private inline fun <reified T : Component> component(
        container: Container,
        name: String,
    ): T = descendants(container).filterIsInstance<T>().single { it.name == name }

    private fun descendants(container: Container): Sequence<Component> =
        sequence {
            for (child in container.components) {
                yield(child)
                if (child is Container) yieldAll(descendants(child))
            }
        }

    private fun onEdt(action: () -> Unit) {
        try {
            SwingUtilities.invokeAndWait(action)
        } catch (exception: InvocationTargetException) {
            throw exception.targetException
        }
    }
}
