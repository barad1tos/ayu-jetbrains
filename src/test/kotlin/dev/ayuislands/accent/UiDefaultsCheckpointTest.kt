package dev.ayuislands.accent

import java.awt.Color
import java.beans.PropertyChangeListener
import java.util.concurrent.CancellationException
import javax.swing.SwingUtilities
import javax.swing.UIDefaults
import javax.swing.UIManager
import javax.swing.plaf.metal.MetalLookAndFeel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class UiDefaultsCheckpointTest {
    @Test
    fun `completed visual apply stays committed when a later notification fails`() =
        onEdt {
            UIManager.put(FOCUS_KEY, Color.BLUE)
            val checkpoint = UiDefaultsCheckpoint()
            checkpoint.put(FOCUS_KEY, Color.RED)
            checkpoint.commit()
            val failure = IllegalStateException("notification failed after successful apply")
            assertSame(failure, assertFailsWith<IllegalStateException> { checkpoint.restoreAfter(failure) })
            assertSame(Color.RED, UIManager.get(FOCUS_KEY))
        }

    @Test
    fun `restored inherited color continues following native defaults`() =
        onEdt {
            val native = UIManager.getLookAndFeelDefaults()
            val saved = native.put(FOCUS_KEY, Color.BLUE)
            try {
                val checkpoint = UiDefaultsCheckpoint()
                checkpoint.put(FOCUS_KEY, Color.RED)
                checkpoint.restore()
                assertEquals(expected = Color.BLUE, actual = UIManager.getColor(FOCUS_KEY))
                native[FOCUS_KEY] = Color.GREEN
                assertEquals(expected = Color.GREEN, actual = UIManager.getColor(FOCUS_KEY))
                assertNull(UIManager.put(FOCUS_KEY, null))
            } finally {
                native[FOCUS_KEY] = saved
            }
        }

    @Test
    fun `restoring a lazy override does not evaluate or replace its provider`() =
        onEdt {
            var evaluations = 0
            val lazy =
                UIDefaults.LazyValue {
                    evaluations++
                    Color.BLUE
                }
            UIManager.put(FOCUS_KEY, lazy)
            val checkpoint = UiDefaultsCheckpoint()
            checkpoint.put(FOCUS_KEY, Color.RED)
            checkpoint.restore()
            assertEquals(0, evaluations)
            assertSame(expected = lazy, actual = UIManager.put(FOCUS_KEY, null))
        }

    @Test
    fun `repeated writes restore the value before the operation`() =
        onEdt {
            UIManager.put(FOCUS_KEY, Color.BLUE)
            val checkpoint = UiDefaultsCheckpoint()
            checkpoint.put(FOCUS_KEY, Color.RED)
            checkpoint.put(FOCUS_KEY, Color.GREEN)
            checkpoint.restore()
            checkpoint.restore()
            assertSame(Color.BLUE, UIManager.get(FOCUS_KEY))
        }

    @Test
    fun `external equal color object prevents restoration and further writes`() =
        onEdt {
            val manual = Color(255, 0, 0)
            UIManager.put(FOCUS_KEY, Color.BLUE)
            val checkpoint = UiDefaultsCheckpoint()
            checkpoint.put(FOCUS_KEY, Color.RED)
            UIManager.put(FOCUS_KEY, manual)
            checkpoint.put(FOCUS_KEY, Color.GREEN)
            checkpoint.put(FOCUS_KEY, Color.YELLOW)
            checkpoint.restore()
            assertSame(expected = manual, actual = UIManager.get(FOCUS_KEY))
        }

    @Test
    fun `listener failure after mutation still permits restoration`() =
        onEdt {
            UIManager.put(FOCUS_KEY, Color.BLUE)
            val checkpoint = UiDefaultsCheckpoint()
            val listener =
                PropertyChangeListener { event ->
                    if (event.propertyName == FOCUS_KEY && event.newValue === Color.RED) {
                        error("listener failed after accent write")
                    }
                }
            withListener(listener) {
                assertFailsWith<IllegalStateException> { checkpoint.put(FOCUS_KEY, Color.RED) }
                checkpoint.restore()
            }
            assertSame(Color.BLUE, UIManager.get(FOCUS_KEY))
        }

    @Test
    fun `failed restoration continues other keys and can be retried`() =
        onEdt {
            UIManager.put(FOCUS_KEY, Color.BLUE)
            UIManager.put(BORDER_KEY, Color.GREEN)
            val checkpoint = UiDefaultsCheckpoint()
            checkpoint.put(BORDER_KEY, Color.RED)
            checkpoint.put(FOCUS_KEY, Color.RED)
            val listener =
                PropertyChangeListener { event ->
                    if (event.propertyName == FOCUS_KEY && event.newValue === Color.BLUE) {
                        error("listener failed during restoration")
                    }
                }
            withListener(listener) {
                assertFailsWith<IllegalStateException> { checkpoint.restore() }
                assertSame(Color.GREEN, UIManager.get(BORDER_KEY))
            }
            checkpoint.restore()
            assertSame(Color.BLUE, UIManager.get(FOCUS_KEY))
        }

    @Test
    fun `cancellation restores owned colors before propagation`() =
        onEdt {
            UIManager.put(FOCUS_KEY, Color.BLUE)
            val checkpoint = UiDefaultsCheckpoint()
            checkpoint.put(FOCUS_KEY, Color.RED)
            val cancellation = CancellationException("accent operation cancelled")
            assertSame(cancellation, assertFailsWith<CancellationException> { checkpoint.restoreAfter(cancellation) })
            assertSame(Color.BLUE, UIManager.get(FOCUS_KEY))
        }

    @Test
    fun `linkage failure during recovery preserves cancellation and restores other keys`() =
        onEdt {
            UIManager.put(FOCUS_KEY, Color.BLUE)
            UIManager.put(BORDER_KEY, Color.GREEN)
            val checkpoint = UiDefaultsCheckpoint()
            checkpoint.put(BORDER_KEY, Color.RED)
            checkpoint.put(FOCUS_KEY, Color.RED)
            val cancellation = CancellationException("accent cancelled")
            val recoveryFailure = NoClassDefFoundError("unloaded UI listener")
            val listener =
                PropertyChangeListener { event ->
                    if (event.propertyName == FOCUS_KEY && event.newValue === Color.BLUE) throw recoveryFailure
                }
            withListener(listener) {
                val thrown = assertFailsWith<CancellationException> { checkpoint.restoreAfter(cancellation) }
                assertSame(cancellation, thrown)
                assertTrue(thrown.suppressed.contains(recoveryFailure))
                assertSame(Color.GREEN, UIManager.get(BORDER_KEY))
            }
        }

    @Test
    fun `recovery cancellation preserves the original failure and other recovery errors`() =
        onEdt {
            UIManager.put(FOCUS_KEY, Color.BLUE)
            UIManager.put(BORDER_KEY, Color.GREEN)
            val checkpoint = UiDefaultsCheckpoint()
            checkpoint.put(FOCUS_KEY, Color.RED)
            checkpoint.put(BORDER_KEY, Color.RED)
            val failure = IllegalStateException("accent failed")
            val recoveryFailure = IllegalArgumentException("border listener failed")
            val cancellation = CancellationException("focus listener cancelled")
            val listener =
                PropertyChangeListener { event ->
                    if (event.propertyName == BORDER_KEY && event.newValue === Color.GREEN) throw recoveryFailure
                    if (event.propertyName == FOCUS_KEY && event.newValue === Color.BLUE) throw cancellation
                }
            withListener(listener) {
                val thrown = assertFailsWith<CancellationException> { checkpoint.restoreAfter(failure) }
                assertSame(cancellation, thrown)
                assertTrue(thrown.suppressed.contains(failure))
                assertTrue(thrown.suppressed.contains(recoveryFailure))
            }
        }

    @Test
    fun `fatal recovery error propagates immediately without continuing cleanup`() =
        onEdt {
            UIManager.put(FOCUS_KEY, Color.BLUE)
            UIManager.put(BORDER_KEY, Color.GREEN)
            val checkpoint = UiDefaultsCheckpoint()
            checkpoint.put(BORDER_KEY, Color.RED)
            checkpoint.put(FOCUS_KEY, Color.RED)
            val fatal = OutOfMemoryError("synthetic fatal recovery failure")
            val listener =
                PropertyChangeListener { event ->
                    if (event.propertyName == FOCUS_KEY && event.newValue === Color.BLUE) throw fatal
                }
            withListener(listener) {
                val thrown = assertFailsWith<OutOfMemoryError> { checkpoint.restoreAfter(CancellationException()) }
                assertSame(fatal, thrown)
                assertSame(Color.RED, UIManager.get(BORDER_KEY))
            }
        }

    @Test
    fun `superseded look and feel is never restored or written through`() =
        onEdt {
            val original = UIManager.getLookAndFeel()
            val checkpoint = UiDefaultsCheckpoint()
            checkpoint.put(FOCUS_KEY, Color.RED)
            try {
                UIManager.setLookAndFeel(MetalLookAndFeel())
                UIManager.put(FOCUS_KEY, Color.GREEN)
                checkpoint.restore()
                assertFailsWith<IllegalStateException> { checkpoint.put(FOCUS_KEY, Color.BLUE) }
                assertSame(Color.GREEN, UIManager.get(FOCUS_KEY))
            } finally {
                UIManager.setLookAndFeel(original)
            }
        }

    private fun onEdt(block: () -> Unit) {
        SwingUtilities.invokeAndWait {
            val focus = UIManager.put(FOCUS_KEY, null)
            val border = UIManager.put(BORDER_KEY, null)
            try {
                block()
            } finally {
                UIManager.put(FOCUS_KEY, focus)
                UIManager.put(BORDER_KEY, border)
            }
        }
    }

    private fun withListener(
        listener: PropertyChangeListener,
        block: () -> Unit,
    ) {
        val defaults = UIManager.getDefaults()
        defaults.addPropertyChangeListener(listener)
        try {
            block()
        } finally {
            defaults.removePropertyChangeListener(listener)
        }
    }

    private companion object {
        const val FOCUS_KEY = "Component.focusColor"
        const val BORDER_KEY = "Button.default.focusedBorderColor"
    }
}
