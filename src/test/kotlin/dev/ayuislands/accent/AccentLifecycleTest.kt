package dev.ayuislands.accent

import java.awt.Color
import java.beans.PropertyChangeListener
import java.util.concurrent.CancellationException
import javax.swing.SwingUtilities
import javax.swing.UIManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class AccentLifecycleTest {
    @Test
    fun `lifecycle interruption stops the remaining direct UI writes`() =
        onEdt {
            val posted = ArrayDeque<() -> Unit>()
            val queue = AccentLifecycleQueue { posted.addLast(it) }
            val scope = AccentApplyScope()
            val defaults = UIManager.getDefaults()
            val listener =
                PropertyChangeListener { event ->
                    if (event.propertyName == KEY && event.newValue === Color.RED) queue.request { }
                }
            scope.open()
            defaults.addPropertyChangeListener(listener)
            try {
                scope.runStep {
                    val checkpoint = scope.uiDefaults
                    checkpoint.put(KEY, Color.RED)

                    assertFailsWith<IllegalStateException> { checkpoint.put(KEY, Color.GREEN) }
                }
            } finally {
                defaults.removePropertyChangeListener(listener)
                scope.restore()
                scope.close()
                queue.afterApply()
                while (posted.isNotEmpty()) posted.removeFirst().invoke()
            }
        }

    @Test
    fun `new lifecycle plan waits for the previous accent rollback`() =
        onEdt {
            val posted = ArrayDeque<() -> Unit>()
            val queue = AccentLifecycleQueue { posted.addLast(it) }
            val scope = AccentApplyScope()
            var completed = false
            scope.open()
            try {
                scope.runStep { putAccentUiDefault(KEY, Color.RED) }
                queue.request {
                    assertNull(AccentApplyScope.active)
                    assertSame(expected = Color.BLUE, actual = UIManager.getColor(KEY))
                    completed = true
                }

                assertFalse(completed)
                assertFailsWith<IllegalStateException> { scope.runStep { putAccentUiDefault(KEY, Color.GREEN) } }
                scope.restore()
            } finally {
                scope.close()
                queue.afterApply()
            }
            assertFalse(completed)
            posted.removeFirst().invoke()
            assertTrue(completed)
            assertTrue(posted.isEmpty())
        }

    @Test
    fun `queued lifecycle plans preserve arrival order through a failed plan`() =
        onEdt {
            val posted = ArrayDeque<() -> Unit>()
            val queue = AccentLifecycleQueue { posted.addLast(it) }
            val events = mutableListOf<String>()
            val scope = AccentApplyScope()
            scope.open()
            try {
                queue.request {
                    events += "theme"
                    error("theme plan failed")
                }
                queue.request { events += "license" }
                scope.restore()
            } finally {
                scope.close()
                queue.afterApply()
            }

            assertFailsWith<IllegalStateException> { posted.removeFirst().invoke() }
            posted.removeFirst().invoke()

            assertEquals(expected = listOf("theme", "license"), actual = events)
        }

    @Test
    fun `publication lifecycle request retains committed colors until its own plan runs`() =
        onEdt {
            val posted = ArrayDeque<() -> Unit>()
            val queue = AccentLifecycleQueue { posted.addLast(it) }
            val scope = AccentApplyScope()
            scope.open()
            try {
                scope.runStep { putAccentUiDefault(KEY, Color.RED) }
                scope.commit()
                queue.request { UIManager.put(KEY, Color.GREEN) }
                assertSame(expected = Color.RED, actual = UIManager.getColor(KEY))
            } finally {
                scope.close()
                queue.afterApply()
            }

            posted.removeFirst().invoke()

            assertSame(expected = Color.GREEN, actual = UIManager.getColor(KEY))
        }

    @Test
    fun `cancelled apply releases an independent lifecycle request after restoration`() =
        onEdt {
            val posted = ArrayDeque<() -> Unit>()
            val queue = AccentLifecycleQueue { posted.addLast(it) }
            val scope = AccentApplyScope()
            val cancellation = CancellationException("cancelled accent")
            val observed = mutableListOf<Color?>()
            scope.open()
            try {
                val thrown =
                    assertFailsWith<CancellationException> {
                        scope.restoringOnFailure {
                            scope.runStep { putAccentUiDefault(KEY, Color.RED) }
                            queue.request { observed += UIManager.getColor(KEY) }
                            throw cancellation
                        }
                    }
                assertSame(expected = cancellation, actual = thrown)
            } finally {
                scope.close()
                queue.afterApply()
            }

            posted.removeFirst().invoke()

            assertSame(expected = Color.BLUE, actual = observed.single())
        }

    private fun onEdt(action: () -> Unit) {
        SwingUtilities.invokeAndWait {
            val previous = readRawUiDefault(UIManager.getDefaults(), KEY)
            UIManager.put(KEY, Color.BLUE)
            try {
                action()
            } finally {
                UIManager.put(KEY, previous)
            }
        }
    }

    private companion object {
        const val KEY = "ProgressBar.foreground"
    }
}
