package dev.ayuislands.studio

import dev.ayuislands.accent.AccentUiInput
import dev.ayuislands.accent.AccentUiKeys
import dev.ayuislands.accent.UiDefaultsOwnership
import dev.ayuislands.accent.accentUiPolicy
import dev.ayuislands.accent.readRawUiDefault
import dev.ayuislands.glow.GlowTabMode
import dev.ayuislands.studio.model.StudioProcess
import dev.ayuislands.studio.persistence.RestoreStatus
import dev.ayuislands.studio.session.RestoreContinuation
import dev.ayuislands.studio.session.StudioAccentUiOperation
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.awt.Color
import java.beans.PropertyChangeEvent
import java.beans.PropertyChangeListener
import java.util.UUID
import javax.swing.SwingUtilities
import javax.swing.UIManager
import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class StudioAccentUiOperationTest {
    @Test
    fun `lease loss in the first setter prevents later setters and restores the written key`() =
        exercise { fixture ->
            val operation = fixture.capture(Color.CYAN)
            var isCurrent = true
            var laterWrites = 0

            withListener({ event ->
                when (event.propertyName) {
                    FIRST -> isCurrent = false
                    SECOND -> laterWrites++
                }
            }) {
                assertFalse(
                    operation.apply { _, write ->
                        if (isCurrent) write() else return@apply false
                        true
                    },
                )
                assertEquals(0, laterWrites)
                assertEquals(RestoreStatus.OWNED, operation.status(FIRST))
                assertEquals(RestoreStatus.PENDING, operation.status(SECOND))
            }

            assertEquals(RestoreContinuation.COMPLETE, operation.restore(emptyList()))
            assertEquals(RestoreStatus.RESTORED, operation.status(FIRST))
            assertSame(expected = fixture.original, actual = UIManager.get(FIRST))
        }

    @Test
    fun `an observed foreign value prevents later reacquisition without a reset`() =
        exercise { fixture ->
            val previous = fixture.install(Color.CYAN)
            UIManager.put(FIRST, Color.MAGENTA)
            val relinquished = fixture.capture(Color.GREEN)
            assertTrue(relinquished.applyUnchecked())
            assertEquals(RestoreStatus.OWNED, relinquished.status(FIRST))
            relinquished.accept()

            UIManager.put(FIRST, previous)
            val next = fixture.capture(Color.ORANGE)
            assertTrue(next.applyUnchecked())
            next.accept()
            assertSame(expected = previous, actual = UIManager.get(FIRST))

            val released = fixture.capture(Color.ORANGE, hasOverride = false)
            assertTrue(released.applyUnchecked())
            released.accept()
            fixture.install(Color.ORANGE)
            assertEquals(Color.ORANGE.rgb, UIManager.getColor(FIRST).rgb)
        }

    @Test
    fun `rollback cannot take back a key already observed as foreign`() =
        exercise { fixture ->
            var installed: Any? = null
            val operation = fixture.capture(Color.CYAN)
            withListener({ event ->
                if (event.propertyName == SECOND) {
                    installed = UIManager.get(FIRST)
                    UIManager.put(FIRST, Color.MAGENTA)
                }
            }) {
                assertTrue(operation.applyUnchecked())
            }

            assertEquals(RestoreStatus.FOREIGN, operation.status(FIRST))
            val restoredByForeignWriter = requireNotNull(installed)
            UIManager.put(FIRST, restoredByForeignWriter)
            assertEquals(RestoreContinuation.COMPLETE, operation.restore(emptyList()))
            assertSame(expected = restoredByForeignWriter, actual = UIManager.get(FIRST))
            assertSame(expected = fixture.original, actual = UIManager.get(SECOND))
            assertEquals(RestoreStatus.FOREIGN, operation.status(FIRST))
        }

    @Test
    fun `a rejected prepared write retires the previous claim when the key went foreign`() =
        exercise { fixture ->
            val previous = fixture.install(Color.CYAN)
            val operation = fixture.capture(Color.GREEN)
            UIManager.put(FIRST, Color.MAGENTA)
            assertFailsWith<IllegalStateException> { operation.applyUnchecked() }
            assertEquals(RestoreStatus.FOREIGN, operation.status(FIRST))
            operation.restore(emptyList())

            UIManager.put(FIRST, previous)
            fixture.install(Color.ORANGE)
            assertSame(expected = previous, actual = UIManager.get(FIRST))
        }

    @Test
    fun `rolling back a claim-only release keeps a later foreign raw value`() =
        exercise { fixture ->
            fixture.install(Color.CYAN)
            UIManager.put(FIRST, Color.MAGENTA)
            val relinquished = fixture.capture(Color.GREEN)
            assertTrue(relinquished.applyUnchecked())
            relinquished.accept()

            val release = fixture.capture(Color.GREEN, hasOverride = false)
            withListener({ event ->
                if (event.propertyName == SECOND) UIManager.put(FIRST, Color.PINK)
            }) {
                assertTrue(release.applyUnchecked())
            }
            release.restore(emptyList())
            fixture.install(Color.ORANGE)
            assertSame(expected = Color.PINK, actual = UIManager.get(FIRST))
        }

    private fun exercise(action: suspend (Fixture) -> Unit) {
        runBlocking {
            withContext(edt) {
                val defaults = UIManager.getDefaults()
                val previous = AccentUiKeys.colors.associateWith { readRawUiDefault(defaults, it) }
                val fixture = Fixture()
                UIManager.put(FIRST, fixture.original)
                UIManager.put(SECOND, fixture.original)
                try {
                    action(fixture)
                } finally {
                    previous.forEach { (key, raw) -> UIManager.put(key, raw) }
                }
            }
        }
    }

    private class Fixture {
        private val owner = UiDefaultsOwnership()
        val original = Color(0x336699)

        suspend fun capture(
            color: Color,
            hasOverride: Boolean = true,
        ): StudioAccentUiOperation {
            val mode = GlowTabMode.MINIMAL
            val policy =
                accentUiPolicy(external = !hasOverride, overrideEnabled = false, chromeAllowed = false, tabMode = mode)
            val process = ProcessHandle.current()
            return requireNotNull(
                StudioAccentUiOperation.capture(
                    AccentUiInput(color, null, mode, Color.WHITE.rgb, policy),
                    StudioProcess(process.pid(), process.info().startInstant().orElse(null)),
                    UUID.randomUUID().toString(),
                    edt,
                    owner,
                ),
            )
        }

        suspend fun install(color: Color): Any {
            val operation = capture(color)
            assertTrue(operation.applyUnchecked())
            operation.accept()
            return requireNotNull(UIManager.get(FIRST))
        }
    }

    private companion object {
        private val FIRST = AccentUiKeys.accent.first()
        private val SECOND = AccentUiKeys.accent[1]
        private val edt =
            object : CoroutineDispatcher() {
                override fun dispatch(
                    context: CoroutineContext,
                    block: Runnable,
                ) = SwingUtilities.invokeLater(block)
            }

        fun StudioAccentUiOperation.status(key: String): RestoreStatus =
            receipts.single { it.identity.field == key }.status

        suspend fun StudioAccentUiOperation.applyUnchecked(): Boolean =
            apply { _, write ->
                write()
                true
            }

        suspend fun withListener(
            onChange: (PropertyChangeEvent) -> Unit,
            action: suspend () -> Unit,
        ) {
            val defaults = UIManager.getDefaults()
            val listener = PropertyChangeListener(onChange)
            defaults.addPropertyChangeListener(listener)
            try {
                action()
            } finally {
                defaults.removePropertyChangeListener(listener)
            }
        }
    }
}
