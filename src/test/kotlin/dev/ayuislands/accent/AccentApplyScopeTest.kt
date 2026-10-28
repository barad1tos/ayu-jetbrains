package dev.ayuislands.accent

import java.awt.Color
import java.beans.PropertyChangeListener
import javax.swing.SwingUtilities
import javax.swing.UIManager
import javax.swing.plaf.metal.MetalLookAndFeel
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class AccentApplyScopeTest {
    @Test
    fun `resource retirement failure keeps committed colors and attempts independent cleanup`() =
        withScope { scope ->
            UIManager.put(KEY, Color.BLUE)
            var independentReleased = false
            val interrupted =
                ResourceCheckpoint {
                    assertTrue(scope.isCommitted)
                    assertSame(Color.RED, UIManager.getColor(KEY))
                    error("old highlighter removal interrupted")
                }
            scope.record(interrupted)
            scope.record(ResourceCheckpoint { independentReleased = true })
            scope.runStep { putAccentUiDefault(KEY, Color.RED) }

            assertFailsWith<IllegalStateException> { scope.restoringOnFailure { scope.commit() } }

            assertTrue(scope.isCommitted)
            assertTrue(independentReleased)
            assertTrue(interrupted.hasPending)
            assertSame(Color.RED, UIManager.getColor(KEY))
            scope.close()
            assertTrue(interrupted.isRetained)
        }

    @Test
    fun `restoration can defer caret work until the active scope has closed`() =
        withScope { scope ->
            var ran = false
            scope.uiDefaults.onRestore {
                scope.onClose {
                    assertSame(null, AccentApplyScope.active)
                    ran = true
                }
            }
            scope.runStep { putAccentUiDefault(KEY, Color.RED) }

            scope.restore()
            scope.close()

            assertTrue(ran)
        }

    @Test
    fun `a later segment cannot paint a replacement look and feel`() =
        withScope { scope ->
            val original = UIManager.getLookAndFeel()
            scope.runStep { putAccentUiDefault(KEY, Color.RED) }
            try {
                UIManager.setLookAndFeel(MetalLookAndFeel())

                var executed = false
                assertFailsWith<IllegalStateException> { scope.runStep { executed = true } }
                assertFalse(executed)
                assertFailsWith<IllegalStateException> {
                    scope.runStep { putAccentUiDefault(OTHER_KEY, Color.GREEN) }
                }
            } finally {
                UIManager.setLookAndFeel(original)
                scope.restore()
            }
        }

    @Test
    fun `pending child recovery survives commit until the next accent settles it`() {
        SwingUtilities.invokeAndWait {
            val previous = readRawUiDefault(UIManager.getDefaults(), KEY)
            var failing = true
            var restored = false
            UIManager.put(KEY, Color.BLUE)
            val scope = AccentApplyScope()
            scope.open()
            try {
                scope.runStep {
                    val rollback = scope.elementRollback()
                    scope.uiDefaults.onRestore {
                        if (failing) error("ownership restoration interrupted")
                        restored = true
                    }
                    putAccentUiDefault(KEY, Color.RED)
                    assertFailsWith<IllegalStateException> { rollback() }
                }
                scope.commit()
                scope.close()
                failing = false
                val next = AccentApplyScope()
                next.open()
                try {
                    next.runStep { putAccentUiDefault(KEY, Color.GREEN) }
                    next.restore()
                } finally {
                    next.close()
                }

                assertTrue(restored, "the previous ownership receipt must survive an independent commit")
                assertSame(expected = Color.BLUE, actual = UIManager.getColor(KEY))
            } finally {
                failing = false
                AccentApplyScope.active?.close()
                val cleanup = AccentApplyScope()
                cleanup.open()
                try {
                    cleanup.runStep { cleanup.uiDefaults }
                    cleanup.restore()
                } finally {
                    cleanup.close()
                    UIManager.put(KEY, previous)
                }
            }
        }
    }

    @Test
    fun `abort unwinds overlapping writes to the original accent`() =
        withScope { scope ->
            UIManager.put(KEY, Color.BLUE)
            scope.runStep { putAccentUiDefault(KEY, Color.RED) }
            scope.runStep { putAccentUiDefault(KEY, Color.GREEN) }

            scope.restore()

            assertSame(expected = Color.BLUE, actual = UIManager.getColor(KEY))
        }

    @Test
    fun `later element cannot reclaim a foreign color during the same apply`() =
        withScope { scope ->
            UIManager.put(KEY, Color.BLUE)
            scope.runStep { putAccentUiDefault(KEY, Color.RED) }
            UIManager.put(KEY, Color.YELLOW)
            scope.runStep { putAccentUiDefault(KEY, Color.GREEN) }
            assertSame(expected = Color.YELLOW, actual = UIManager.getColor(KEY))
            UIManager.put(KEY, Color.RED)
            scope.runStep { putAccentUiDefault(KEY, Color.PINK) }
            assertSame(expected = Color.RED, actual = UIManager.getColor(KEY))
            scope.restore()

            assertSame(expected = Color.RED, actual = UIManager.getColor(KEY))
        }

    @Test
    fun `retry preserves a rollback subscriber's foreign edit while independent keys recover`() =
        withScope { scope ->
            UIManager.put(KEY, Color.BLUE)
            UIManager.put(OTHER_KEY, Color.WHITE)
            scope.runStep { putAccentUiDefault(KEY, Color.RED) }
            scope.runStep { putAccentUiDefault(OTHER_KEY, Color.PINK) }
            scope.runStep { putAccentUiDefault(KEY, Color.GREEN) }
            val defaults = UIManager.getDefaults()
            val listener =
                PropertyChangeListener { event ->
                    if (event.propertyName == KEY && event.newValue === Color.RED) {
                        UIManager.put(KEY, Color.GREEN)
                        error("newer write remains after interrupted restoration")
                    }
                }
            defaults.addPropertyChangeListener(listener)
            try {
                assertFailsWith<IllegalStateException> { scope.restore() }
                assertSame(expected = Color.GREEN, actual = UIManager.getColor(KEY))
                assertSame(expected = Color.WHITE, actual = UIManager.getColor(OTHER_KEY))
            } finally {
                defaults.removePropertyChangeListener(listener)
            }

            scope.restore()

            assertSame(expected = Color.GREEN, actual = UIManager.getColor(KEY))
        }

    @Test
    fun `failed restore attempts independent keys before reporting failure`() =
        withScope { scope ->
            UIManager.put(KEY, Color.BLUE)
            UIManager.put(OTHER_KEY, Color.WHITE)
            scope.runStep { putAccentUiDefault(OTHER_KEY, Color.RED) }
            scope.runStep { putAccentUiDefault(KEY, Color.GREEN) }
            val defaults = UIManager.getDefaults()
            val listener =
                PropertyChangeListener { event ->
                    if (event.propertyName == KEY && event.newValue === Color.BLUE) {
                        error("listener failed after restoration")
                    }
                }
            defaults.addPropertyChangeListener(listener)
            try {
                assertFailsWith<IllegalStateException> { scope.restore() }

                assertSame(expected = Color.WHITE, actual = UIManager.getColor(OTHER_KEY))
                assertSame(expected = Color.BLUE, actual = UIManager.getColor(KEY))
            } finally {
                defaults.removePropertyChangeListener(listener)
            }
        }

    private fun withScope(action: (AccentApplyScope) -> Unit) {
        SwingUtilities.invokeAndWait {
            val saved = readRawUiDefault(UIManager.getDefaults(), KEY)
            val other = readRawUiDefault(UIManager.getDefaults(), OTHER_KEY)
            val scope = AccentApplyScope()
            scope.open()
            try {
                action(scope)
            } finally {
                if (AccentApplyScope.active === scope) scope.close()
                UIManager.put(KEY, saved)
                UIManager.put(OTHER_KEY, other)
            }
        }
    }

    private companion object {
        const val KEY = "ProgressBar.foreground"
        const val OTHER_KEY = "ProgressBar.progressCounterBackground"
    }

    private class ResourceCheckpoint(
        private val release: () -> Unit,
    ) : AccentCheckpoint {
        override var hasPending = true
            private set
        var isRetained = false
            private set

        override fun restore(blockedBy: List<AccentCheckpoint>) {
            hasPending = false
        }

        override fun commit() = Unit

        override fun releaseResources() {
            release()
            hasPending = false
        }

        override fun retain() {
            isRetained = true
        }
    }
}
