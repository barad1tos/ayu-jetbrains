package dev.ayuislands.glow

import java.awt.Color
import java.awt.event.FocusEvent
import java.beans.PropertyChangeListener
import javax.swing.BorderFactory
import javax.swing.JTextField
import javax.swing.SwingUtilities
import javax.swing.border.Border
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame

class GlowFocusCheckpointTest {
    @Test
    fun `recoloring a focused field retains listener registrations and restores its exact border`() =
        withField { field, listener ->
            val before = field.border
            val checkpoint = checkNotNull(listener.captureAppearance { it.copy(color = Color.CYAN, intensity = 81) })

            assertSame(before, field.border)
            assertEquals(setOf(GlowFocusField.APPEARANCE, GlowFocusField.BORDER), checkpoint.record.fields.keys)
            checkpoint.apply()
            assertSame(checkpoint.record.plannedBorder, field.border)
            assertEquals(GlowCheckpointPhase.APPLIED, checkpoint.record.phase)
            checkpoint.prepareRestore()
            checkpoint.restore()

            assertSame(before, field.border)
            assertSame(listener, field.focusListeners.single { it === listener })
            assertSame(listener, field.hierarchyListeners.single { it === listener })
            assertEquals(GlowFieldState.RESTORED, checkpoint.record.fields[GlowFocusField.BORDER])
        }

    @Test
    fun `accepting a disable leaves the exact IDE border and prevents focus callbacks repainting it`() =
        withField { field, listener ->
            assertNull(listener.captureAppearance { it.copy() })
            val checkpoint = checkNotNull(listener.captureAppearance { it.copy(enabled = false) })
            checkpoint.apply()
            checkpoint.accept()
            assertSame(field.nativeBorder, field.border)

            listener.focusLost(FocusEvent(field, FocusEvent.FOCUS_LOST))
            listener.focusGained(FocusEvent(field, FocusEvent.FOCUS_GAINED))

            assertSame(field.nativeBorder, checkpoint.record.baselineBorder)
            assertSame(field.nativeBorder, field.border)
            assertFailsWith<IllegalStateException> { checkpoint.restore() }
        }

    @Test
    fun `the same plain color is a no op but a dynamic color of the same RGB is a change`() =
        withField { _, listener ->
            assertNull(listener.captureAppearance { it.copy(color = Color(it.color.rgb, true)) })
            assertIs<GlowFocusListener.Checkpoint>(
                listener.captureAppearance { it.copy(color = object : Color(it.color.rgb, true) {}) },
            )
        }

    @Test
    fun `focus lost between rollback preparation and write requires a fresh border decision`() =
        withField { field, listener ->
            val checkpoint = checkNotNull(listener.captureAppearance { it.copy(style = GlowStyle.GRADIENT) })
            checkpoint.apply()
            checkpoint.prepareRestore()
            assertSame(checkpoint.record.beforeBorder, checkpoint.record.restoration?.desired)

            field.focused = false
            assertFailsWith<IllegalStateException> { checkpoint.restore() }
            assertSame(checkpoint.record.plannedBorder, field.border)
            checkpoint.prepareRestore()
            assertSame(checkpoint.record.baselineBorder, checkpoint.record.restoration?.desired)
            checkpoint.restore()

            assertSame(checkpoint.record.baselineBorder, field.border)
            assertEquals(GlowCheckpointPhase.RESTORED, checkpoint.record.phase)
        }

    @Test
    fun `rollback removes a reappearing original Glow border when its field has lost focus`() =
        withField { field, listener ->
            val checkpoint = checkNotNull(listener.captureAppearance { it.copy(color = Color.GREEN) })
            checkpoint.apply()
            field.focused = false
            field.border = checkpoint.record.beforeBorder
            checkpoint.prepareRestore()

            checkpoint.restore()

            assertSame(field.nativeBorder, field.border)
            assertEquals(GlowFieldState.RESTORED, checkpoint.record.fields[GlowFocusField.BORDER])
        }

    @Test
    fun `a foreign border before apply rejects the write and an edit during rollback is preserved`() =
        withField { field, listener ->
            val stale = checkNotNull(listener.captureAppearance { it.copy(color = Color.BLUE) })
            val foreign = BorderFactory.createEmptyBorder(4, 5, 6, 7)
            field.border = foreign
            assertFailsWith<IllegalStateException> { stale.apply() }
            stale.restore()
            assertSame(foreign, field.border)

            listener.focusLost(FocusEvent(field, FocusEvent.FOCUS_LOST))
            listener.focusGained(FocusEvent(field, FocusEvent.FOCUS_GAINED))
            val checkpoint = checkNotNull(listener.captureAppearance { it.copy(color = Color.BLUE) })
            checkpoint.apply()
            checkpoint.prepareRestore()
            field.border = foreign
            checkpoint.restore()

            assertSame(foreign, field.border)
            assertEquals(GlowFieldState.FOREIGN, checkpoint.record.fields[GlowFocusField.BORDER])
            assertEquals(GlowFieldState.RESTORED, checkpoint.record.fields[GlowFocusField.APPEARANCE])
        }

    @Test
    fun `a Swing property callback failure after the setter leaves enough evidence to restore once`() =
        withField { field, listener ->
            val before = field.border
            val checkpoint = checkNotNull(listener.captureAppearance { it.copy(color = Color.GREEN) })
            val failure = IllegalStateException("Border listener failed")
            val throwing = PropertyChangeListener { throw failure }

            field.addPropertyChangeListener("border", throwing)
            try {
                assertSame(failure, assertFailsWith<IllegalStateException> { checkpoint.apply() })
                assertSame(checkpoint.record.plannedBorder, field.border)
                assertEquals(GlowFieldState.WRITING, checkpoint.record.fields[GlowFocusField.BORDER])
                checkpoint.prepareRestore()
                assertSame(failure, assertFailsWith<IllegalStateException> { checkpoint.restore() })
                assertSame(before, field.border)
                checkpoint.prepareRestore()
                checkpoint.restore()
                assertEquals(GlowFieldState.RESTORED, checkpoint.record.fields[GlowFocusField.BORDER])
            } finally {
                field.removePropertyChangeListener("border", throwing)
            }
        }

    @Test
    fun `canceling before the first write settles the checkpoint once and preserves a later border`() =
        withField { field, listener ->
            val before = field.border
            val checkpoint = checkNotNull(listener.captureAppearance { it.copy(color = Color.GREEN) })

            checkpoint.prepareRestore()
            checkpoint.restore()
            assertSame(before, field.border)
            assertEquals(GlowCheckpointPhase.RESTORED, checkpoint.record.phase)
            assertEquals(
                setOf(GlowFieldState.RESTORED),
                checkpoint.record.fields.values
                    .toSet(),
            )

            val foreign = BorderFactory.createEmptyBorder(2, 1, 0, 3)
            field.border = foreign
            checkpoint.restore()
            assertFailsWith<IllegalStateException> { checkpoint.apply() }
            assertFailsWith<IllegalStateException> { checkpoint.accept() }
            assertSame(foreign, field.border)
        }

    @Test
    fun `a newer border receipt blocks only the border while appearance can be restored`() =
        withField { field, listener ->
            val checkpoint = checkNotNull(listener.captureAppearance { it.copy(color = Color.GREEN) })
            checkpoint.apply()
            checkpoint.prepareRestore()

            val blocked = assertFailsWith<IllegalStateException> { checkpoint.restore(GlowFocusField.entries.toSet()) }
            assertEquals(1, blocked.suppressed.size)
            assertEquals(
                setOf(GlowFieldState.WRITING),
                checkpoint.record.fields.values
                    .toSet(),
            )
            assertFailsWith<IllegalStateException> { checkpoint.restore(setOf(GlowFocusField.BORDER)) }
            assertSame(checkpoint.record.plannedBorder, field.border)
            assertEquals(GlowFieldState.RESTORED, checkpoint.record.fields[GlowFocusField.APPEARANCE])
            assertEquals(GlowFieldState.WRITING, checkpoint.record.fields[GlowFocusField.BORDER])

            checkpoint.prepareRestore()
            checkpoint.restore()

            assertSame(checkpoint.record.beforeBorder, field.border)
        }

    @Test
    fun `a Swing callback can take ownership of the border during rollback`() =
        withField { field, listener ->
            val before = field.border
            val foreign = BorderFactory.createEmptyBorder(1, 2, 3, 4)
            val checkpoint = checkNotNull(listener.captureAppearance { it.copy(color = Color.GREEN) })
            checkpoint.apply()
            checkpoint.prepareRestore()
            val replaceOnRestore =
                PropertyChangeListener { change ->
                    if (change.newValue === before) field.border = foreign
                }

            field.addPropertyChangeListener("border", replaceOnRestore)
            try {
                checkpoint.restore()
                assertSame(foreign, field.border)
                assertEquals(GlowFieldState.FOREIGN, checkpoint.record.fields[GlowFocusField.BORDER])
                assertEquals(GlowFieldState.RESTORED, checkpoint.record.fields[GlowFocusField.APPEARANCE])
            } finally {
                field.removePropertyChangeListener("border", replaceOnRestore)
            }
        }

    @Test
    fun `rollback retires a detached listener without changing its replacement`() =
        withField { field, listener ->
            val checkpoint = checkNotNull(listener.captureAppearance { it.copy(color = Color.GREEN) })
            checkpoint.apply()
            listener.detach()
            val replacement = createListener(field)
            replacement.attach()
            try {
                val current = assertIs<GlowFocusBorder>(field.border)
                checkpoint.prepareRestore()
                checkpoint.restore()

                assertSame(current, field.border)
                assertEquals(GlowCheckpointPhase.RESTORED, checkpoint.record.phase)
                assertEquals(GlowFieldState.FOREIGN, checkpoint.record.fields[GlowFocusField.APPEARANCE])
                assertEquals(GlowFieldState.FOREIGN, checkpoint.record.fields[GlowFocusField.BORDER])
            } finally {
                replacement.detach()
            }
        }

    @Test
    fun `a replacement listener is never overwritten by a previously captured request`() =
        withField { field, listener ->
            val checkpoint = checkNotNull(listener.captureAppearance { it.copy(color = Color.GREEN) })
            listener.detach()
            val replacement = createListener(field)
            replacement.attach()
            try {
                val current = assertIs<GlowFocusBorder>(field.border)
                assertFailsWith<IllegalStateException> { checkpoint.apply() }
                checkpoint.restore()
                assertSame(current, field.border)
            } finally {
                replacement.detach()
            }
        }

    private fun withField(test: (FocusedField, GlowFocusListener) -> Unit) =
        SwingUtilities.invokeAndWait {
            val field = FocusedField()
            val listener = createListener(field)
            listener.attach()
            try {
                test(field, listener)
            } finally {
                listener.detach()
            }
        }

    private fun createListener(field: JTextField): GlowFocusListener =
        GlowFocusListener(field, Color.ORANGE, GlowStyle.SOFT, 50, callbacksAllowed = { true }, onDetached = {})
}

private class FocusedField : JTextField() {
    val nativeBorder: Border? = border
    var focused = true

    override fun hasFocus(): Boolean = focused
}
