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
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class UiDefaultsCheckpointTest {
    @Test
    fun `portable field blockers preserve the newer key while unrelated fields restore`() =
        onEdt {
            UIManager.put(FOCUS_KEY, Color.BLUE)
            val checkpoint = UiDefaultsCheckpoint()
            val blocked = assertNotNull(checkpoint.prepare(FOCUS_KEY, Color.RED))
            val independent = assertNotNull(checkpoint.prepare(BORDER_KEY, Color.GREEN))
            assertTrue(checkpoint.apply(blocked))
            assertTrue(checkpoint.apply(independent))
            assertFailsWith<IllegalStateException> { checkpoint.restoreFields(setOf(FOCUS_KEY)) }
            assertSame(Color.RED, UIManager.get(FOCUS_KEY))
            assertNull(readRawUiDefault(UIManager.getDefaults(), BORDER_KEY))
            assertEquals(AccentWriteState.OWNED, checkpoint.stateOf(blocked))
            assertEquals(AccentWriteState.RESTORED, checkpoint.stateOf(independent))
            assertTrue(checkpoint.hasPending)
            checkpoint.restoreFields(emptySet())
            assertSame(Color.BLUE, UIManager.get(FOCUS_KEY))
            assertFalse(checkpoint.hasPending)
        }

    @Test
    fun `receipt state distinguishes prepared owned restored and foreign without native writes`() =
        onEdt {
            UIManager.put(FOCUS_KEY, Color.BLUE)
            val checkpoint = UiDefaultsCheckpoint()
            val write = assertNotNull(checkpoint.prepare(FOCUS_KEY, Color.RED))
            assertEquals(AccentWriteState.PREPARED, checkpoint.stateOf(write))
            assertTrue(checkpoint.apply(write))
            assertEquals(AccentWriteState.OWNED, checkpoint.stateOf(write))
            checkpoint.restore()
            assertEquals(AccentWriteState.RESTORED, checkpoint.stateOf(write))
            assertFalse(checkpoint.hasPending)
            val next = assertNotNull(checkpoint.prepare(FOCUS_KEY, Color.GREEN))
            assertTrue(checkpoint.apply(next))
            val foreign = Color.MAGENTA
            UIManager.put(FOCUS_KEY, foreign)
            checkpoint.restore()
            assertEquals(AccentWriteState.RELINQUISHED, checkpoint.stateOf(next))
            assertFalse(checkpoint.hasPending)
            assertSame(foreign, UIManager.get(FOCUS_KEY))
            val fresh = assertNotNull(checkpoint.prepare(FOCUS_KEY, Color.YELLOW))
            assertTrue(checkpoint.apply(fresh))
            checkpoint.restore()
            assertSame(foreign, UIManager.get(FOCUS_KEY))
        }

    @Test
    fun `receipt records completed restoration even when its setter throws after mutation`() =
        onEdt {
            UIManager.put(FOCUS_KEY, Color.BLUE)
            val checkpoint = UiDefaultsCheckpoint()
            val write = assertNotNull(checkpoint.prepare(FOCUS_KEY, Color.RED))
            assertTrue(checkpoint.apply(write))
            val listener =
                PropertyChangeListener { event ->
                    if (event.propertyName == FOCUS_KEY && event.newValue === Color.BLUE) {
                        error("restored before listener failure")
                    }
                }
            UIManager.getDefaults().addPropertyChangeListener(listener)
            try {
                assertFailsWith<IllegalStateException> { checkpoint.restore() }
            } finally {
                UIManager.getDefaults().removePropertyChangeListener(listener)
            }
            assertEquals(AccentWriteState.RESTORED, checkpoint.stateOf(write))
            assertFalse(checkpoint.hasPending)
            assertSame(Color.BLUE, UIManager.get(FOCUS_KEY))
        }

    @Test
    fun `foreign callback during restoration stays foreign after retries`() =
        onEdt {
            UIManager.put(FOCUS_KEY, Color.BLUE)
            val checkpoint = UiDefaultsCheckpoint()
            val write = assertNotNull(checkpoint.prepare(FOCUS_KEY, Color.RED))
            assertTrue(checkpoint.apply(write))
            val foreign = Color.MAGENTA
            val listener =
                PropertyChangeListener { event ->
                    if (event.propertyName == FOCUS_KEY && event.newValue === Color.BLUE) {
                        UIManager.put(FOCUS_KEY, foreign)
                    }
                }
            withListener(listener) { checkpoint.restore() }
            assertSame(foreign, UIManager.get(FOCUS_KEY))
            assertEquals(AccentWriteState.RELINQUISHED, checkpoint.stateOf(write))
            checkpoint.restore()
            assertEquals(AccentWriteState.RELINQUISHED, checkpoint.stateOf(write))
            assertSame(foreign, UIManager.get(FOCUS_KEY))
            assertFalse(checkpoint.hasPending)
        }

    @Test
    fun `prepared defaults retain raw lazy identity without writing until acknowledged apply`() =
        onEdt {
            var evaluations = 0
            val lazy =
                UIDefaults.LazyValue {
                    evaluations++
                    Color.BLUE
                }
            UIManager.put(FOCUS_KEY, lazy)
            val checkpoint = UiDefaultsCheckpoint()
            val write = assertNotNull(checkpoint.prepare(FOCUS_KEY, Color.RED))
            assertSame(lazy, write.before)
            assertSame(lazy, write.baseline)
            assertSame(Color.RED, write.planned)
            assertSame(lazy, readRawUiDefault(UIManager.getDefaults(), FOCUS_KEY))
            assertFalse(checkpoint.hasPending)
            assertTrue(checkpoint.apply(write))
            assertTrue(checkpoint.hasPending)
            assertSame(Color.RED, readRawUiDefault(UIManager.getDefaults(), FOCUS_KEY))
            checkpoint.restore()
            assertSame(lazy, readRawUiDefault(UIManager.getDefaults(), FOCUS_KEY))
            assertEquals(0, evaluations)
        }

    @Test
    fun `prepared repeated write keeps the first rollback baseline and survives an unrelated key`() =
        onEdt {
            UIManager.put(FOCUS_KEY, Color.BLUE)
            val checkpoint = UiDefaultsCheckpoint()
            checkpoint.put(FOCUS_KEY, Color.RED)
            val write = assertNotNull(checkpoint.prepare(FOCUS_KEY, Color.GREEN))
            assertSame(Color.RED, write.before)
            assertSame(Color.BLUE, write.baseline)
            checkpoint.put(BORDER_KEY, Color.YELLOW)
            assertTrue(checkpoint.apply(write))
            assertSame(Color.GREEN, UIManager.get(FOCUS_KEY))
            assertFalse(checkpoint.apply(write))
            checkpoint.restore()
            assertSame(Color.BLUE, UIManager.get(FOCUS_KEY))
            assertNull(readRawUiDefault(UIManager.getDefaults(), BORDER_KEY))
        }

    @Test
    fun `stale same key preparations cannot overwrite newer writes or change their rollback baseline`() =
        onEdt {
            UIManager.put(FOCUS_KEY, Color.BLUE)
            val checkpoint = UiDefaultsCheckpoint()
            val first = assertNotNull(checkpoint.prepare(FOCUS_KEY, Color.RED))
            val stale = assertNotNull(checkpoint.prepare(FOCUS_KEY, Color.GREEN))
            assertTrue(checkpoint.apply(first))
            assertFalse(checkpoint.apply(stale))
            assertSame(Color.RED, UIManager.get(FOCUS_KEY))
            checkpoint.restore()
            assertSame(Color.BLUE, UIManager.get(FOCUS_KEY))
        }

    @Test
    fun `foreign equal raw object between preparation and apply retains users value`() =
        onEdt {
            UIManager.put(FOCUS_KEY, Color.BLUE)
            val checkpoint = UiDefaultsCheckpoint()
            checkpoint.put(FOCUS_KEY, Color.RED)
            val write = assertNotNull(checkpoint.prepare(FOCUS_KEY, Color.GREEN))
            val foreign = Color(255, 0, 0)
            UIManager.put(FOCUS_KEY, foreign)
            assertFalse(checkpoint.apply(write))
            assertNull(checkpoint.prepare(FOCUS_KEY, Color.YELLOW))
            checkpoint.restore()
            assertSame(expected = foreign, actual = UIManager.get(FOCUS_KEY))
        }

    @Test
    fun `unused default preparations expire on restoration commit and foreign checkpoint use`() =
        onEdt {
            UIManager.put(FOCUS_KEY, Color.BLUE)
            val cancelled = UiDefaultsCheckpoint()
            val beforeCancel = assertNotNull(cancelled.prepare(FOCUS_KEY, Color.RED))
            cancelled.restore()
            assertFalse(cancelled.apply(beforeCancel))
            val accepted = UiDefaultsCheckpoint()
            val beforeCommit = assertNotNull(accepted.prepare(FOCUS_KEY, Color.GREEN))
            accepted.commit()
            assertFalse(accepted.apply(beforeCommit))
            val other = UiDefaultsCheckpoint()
            val own = assertNotNull(other.prepare(FOCUS_KEY, Color.YELLOW))
            assertFalse(accepted.apply(own))
            assertSame(Color.BLUE, UIManager.get(FOCUS_KEY))
            assertTrue(other.apply(own))
            other.restore()
            assertSame(Color.BLUE, UIManager.get(FOCUS_KEY))
        }

    @Test
    fun `prepared setter failure after mutation retains native rollback ownership`() =
        onEdt {
            UIManager.put(FOCUS_KEY, Color.BLUE)
            val checkpoint = UiDefaultsCheckpoint()
            val write = assertNotNull(checkpoint.prepare(FOCUS_KEY, Color.RED))
            val listener =
                PropertyChangeListener { event ->
                    if (event.propertyName == FOCUS_KEY &&
                        event.newValue === Color.RED
                    ) {
                        error("Native write notification failed")
                    }
                }
            withListener(listener) {
                assertFailsWith<IllegalStateException> { checkpoint.apply(write) }
                assertTrue(checkpoint.hasPending)
                assertFalse(checkpoint.apply(write))
                checkpoint.restore()
            }
            assertSame(Color.BLUE, UIManager.get(FOCUS_KEY))
        }

    @Test
    fun `prepared write reports a foreign listener replacement without reclaiming its value`() =
        onEdt {
            UIManager.put(FOCUS_KEY, Color.BLUE)
            val checkpoint = UiDefaultsCheckpoint()
            val write = assertNotNull(checkpoint.prepare(FOCUS_KEY, Color.RED))
            val foreign = Color(0x123456)
            val listener =
                PropertyChangeListener { event ->
                    if (event.propertyName == FOCUS_KEY &&
                        event.newValue === Color.RED
                    ) {
                        UIManager.put(FOCUS_KEY, foreign)
                    }
                }
            withListener(listener) {
                assertFalse(checkpoint.apply(write))
                assertNull(checkpoint.prepare(FOCUS_KEY, Color.YELLOW))
                checkpoint.restore()
            }
            assertSame(expected = foreign, actual = UIManager.get(FOCUS_KEY))
            assertFalse(checkpoint.hasPending)
        }

    @Test
    fun `prepared writes reject replacement look and feel before any native write`() =
        onEdt {
            val original = UIManager.getLookAndFeel()
            val checkpoint = UiDefaultsCheckpoint()
            val write = assertNotNull(checkpoint.prepare(FOCUS_KEY, Color.RED))
            try {
                UIManager.setLookAndFeel(MetalLookAndFeel())
                UIManager.put(FOCUS_KEY, Color.GREEN)
                assertFailsWith<IllegalStateException> { checkpoint.apply(write) }
                assertSame(Color.GREEN, UIManager.get(FOCUS_KEY))
            } finally {
                UIManager.setLookAndFeel(original)
            }
        }

    @Test
    fun `completed visual apply stays committed when a later notification fails`() =
        onEdt {
            UIManager.put(FOCUS_KEY, Color.BLUE)
            val checkpoint = UiDefaultsCheckpoint()
            checkpoint.put(FOCUS_KEY, Color.RED)
            checkpoint.commit()
            val failure = IllegalStateException("notification failed after successful apply")
            assertSame(
                failure,
                assertFailsWith<IllegalStateException> {
                    restoringScope(checkpoint).restoringOnFailure { throw failure }
                },
            )
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
            assertSame(
                cancellation,
                assertFailsWith<CancellationException> {
                    restoringScope(checkpoint).restoringOnFailure { throw cancellation }
                },
            )
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
                val thrown =
                    assertFailsWith<CancellationException> {
                        restoringScope(
                            checkpoint,
                        ).restoringOnFailure { throw cancellation }
                    }
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
                val thrown =
                    assertFailsWith<CancellationException> {
                        restoringScope(
                            checkpoint,
                        ).restoringOnFailure { throw failure }
                    }
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
                val thrown =
                    assertFailsWith<OutOfMemoryError> {
                        restoringScope(
                            checkpoint,
                        ).restoringOnFailure { throw CancellationException() }
                    }
                assertSame(fatal, thrown)
                assertSame(Color.RED, UIManager.get(BORDER_KEY))
            }
        }

    @Test
    fun `restore runs registered actions once and commit discards them`() =
        onEdt {
            var restored = 0
            val torn = UiDefaultsCheckpoint()
            torn.onRestore { restored++ }
            torn.restore()
            torn.restore()
            assertEquals(1, restored)

            val completed = UiDefaultsCheckpoint()
            completed.onRestore { restored++ }
            completed.commit()
            completed.restore()
            assertEquals(1, restored)
        }

    @Test
    fun `restore runs registered actions when a listener throws after the table is restored`() =
        onEdt {
            UIManager.put(FOCUS_KEY, Color.BLUE)
            val checkpoint = UiDefaultsCheckpoint()
            checkpoint.put(FOCUS_KEY, Color.RED)
            var restored = 0
            checkpoint.onRestore { restored++ }
            val listener =
                PropertyChangeListener { event ->
                    if (event.propertyName == FOCUS_KEY && event.newValue === Color.BLUE) error("listener failed")
                }
            withListener(listener) {
                assertFailsWith<IllegalStateException> { checkpoint.restore() }
            }
            assertSame(Color.BLUE, UIManager.get(FOCUS_KEY))
            assertEquals(1, restored)
        }

    @Test
    fun `restore withholds registered actions while a write is still unrestored`() =
        onEdt {
            val original = UIManager.getLookAndFeel()
            val checkpoint = UiDefaultsCheckpoint()
            checkpoint.put(FOCUS_KEY, Color.RED)
            var restored = 0
            checkpoint.onRestore { restored++ }
            try {
                UIManager.setLookAndFeel(MetalLookAndFeel())
                checkpoint.restore()
                assertEquals(0, restored)
            } finally {
                UIManager.setLookAndFeel(original)
            }
        }

    @Test
    fun `fatal recovery error escapes a torn apply instead of being contained`() =
        onEdt {
            UIManager.put(FOCUS_KEY, Color.BLUE)
            val checkpoint = UiDefaultsCheckpoint()
            checkpoint.put(FOCUS_KEY, Color.RED)
            val fatal = OutOfMemoryError("synthetic fatal recovery failure")
            val listener =
                PropertyChangeListener { event ->
                    if (event.propertyName == FOCUS_KEY && event.newValue === Color.BLUE) throw fatal
                }
            withListener(listener) {
                val thrown =
                    assertFailsWith<OutOfMemoryError> {
                        restoringScope(checkpoint).restoreAfterTear(listOf(IllegalStateException("apply failed")))
                    }
                assertSame(fatal, thrown)
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

    private fun restoringScope(checkpoint: UiDefaultsCheckpoint): AccentApplyScope =
        AccentApplyScope().also { it.record(checkpoint) }
}
