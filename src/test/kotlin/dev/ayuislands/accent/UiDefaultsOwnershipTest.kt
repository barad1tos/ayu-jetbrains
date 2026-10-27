package dev.ayuislands.accent

import dev.ayuislands.glow.GlowTabMode
import java.awt.Color
import java.beans.PropertyChangeListener
import java.util.concurrent.CancellationException
import javax.swing.SwingUtilities
import javax.swing.UIDefaults
import javax.swing.UIManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class UiDefaultsOwnershipTest {
    @Test
    fun `pure writes retain the original value across accent changes`() {
        val original = UiOverrideValue(Color.BLUE)
        val first = planUiOverride(UiOverrideState.Unowned, original, UiOverrideValue(Color.RED))
        val second = planUiOverride(first.next, UiOverrideValue(Color.RED), UiOverrideValue(Color.GREEN))

        assertSame(original, assertIs<UiOverrideState.Owned>(second.next).original)
        assertSame(Color.GREEN, second.write?.raw)
    }

    @Test
    fun `pure release preserves a foreign equal RGB color`() {
        val owned = UiOverrideState.Owned(UiOverrideValue(Color.BLUE), UiOverrideValue(Color.RED))
        val foreign = UiOverrideValue(Color(255, 0, 0))

        val next = planUiOverride(owned, foreign, null)

        assertSame(UiOverrideState.Unowned, next.next)
        assertNull(next.write)
    }

    @Test
    fun `pure automatic refresh relinquishes a foreign value until release`() {
        val owned = UiOverrideState.Owned(UiOverrideValue(Color.BLUE), UiOverrideValue(Color.RED))
        val foreign = UiOverrideValue(Color.YELLOW)
        val lost = planUiOverride(owned, foreign, UiOverrideValue(Color.GREEN))
        val refresh = planUiOverride(lost.next, foreign, UiOverrideValue(Color.CYAN))

        assertSame(UiOverrideState.Relinquished, refresh.next)
        assertNull(refresh.write)
        assertSame(UiOverrideState.Unowned, planUiOverride(refresh.next, foreign, null).next)
    }

    @Test
    fun `completed applies preserve the first baseline until native override is disabled`() =
        onEdt { owner ->
            UIManager.put(KEY, Color.BLUE)
            write(owner, KEY, Color.RED)
            write(owner, KEY, Color.GREEN)

            owner.restore()
            owner.restore()

            assertSame(Color.BLUE, UIManager.get(KEY))
        }

    @Test
    fun `restored absence follows the current theme instead of the old resolved color`() =
        onEdt { owner ->
            val defaults = UIManager.getLookAndFeelDefaults()
            val original = defaults.put(KEY, Color.BLUE)
            try {
                write(owner, KEY, Color.RED)
                defaults[KEY] = Color.GREEN

                owner.restore()

                assertSame(Color.GREEN, UIManager.get(KEY))
                assertNull(UIManager.put(KEY, null))
            } finally {
                defaults[KEY] = original
            }
        }

    @Test
    fun `native restoration retains lazy providers without evaluating them`() =
        onEdt { owner ->
            var evaluations = 0
            val original =
                UIDefaults.LazyValue {
                    evaluations++
                    Color.BLUE
                }
            UIManager.put(KEY, original)
            write(owner, KEY, Color.RED)

            owner.restore()

            assertEquals(0, evaluations)
            assertSame(UIManager.put(KEY, null), original)
        }

    @Test
    fun `foreign custom colors survive automatic refresh and disabling override`() =
        onEdt { owner ->
            UIManager.put(KEY, Color.BLUE)
            write(owner, KEY, Color.RED)
            val foreign = Color(255, 0, 0)
            UIManager.put(KEY, foreign)
            write(owner, KEY, Color.GREEN)

            owner.restore()

            assertSame(UIManager.get(KEY), foreign)
            write(owner, KEY, Color.YELLOW)
            owner.restore()
            assertSame(UIManager.get(KEY), foreign)
        }

    @Test
    fun `a reentrant foreign write during accent application is preserved`() =
        onEdt { owner ->
            UIManager.put(KEY, Color.BLUE)
            val foreign = Color.MAGENTA
            val listener =
                PropertyChangeListener { event ->
                    if (event.propertyName == KEY && event.newValue === Color.RED) UIManager.put(KEY, foreign)
                }
            withListener(listener) { write(owner, KEY, Color.RED) }

            owner.restore()

            assertSame(foreign, UIManager.get(KEY))
        }

    @Test
    fun `an aborted apply restores earlier ownership for the next disable`() =
        onEdt { owner ->
            UIManager.put(KEY, Color.BLUE)
            write(owner, KEY, Color.RED)
            val checkpoint = UiDefaultsCheckpoint()
            owner.record(checkpoint)
            owner.put(KEY, Color.GREEN, checkpoint)
            val failure = CancellationException("accent apply cancelled")

            assertSame(failure, assertFailsWith<CancellationException> { checkpoint.restoreAfter(failure) })
            assertSame(Color.RED, UIManager.get(KEY))
            owner.restore()
            assertSame(Color.BLUE, UIManager.get(KEY))
        }

    @Test
    fun `a cancelled first apply leaves no stale claim over the restored native color`() =
        onEdt { owner ->
            UIManager.put(KEY, Color.BLUE)
            val checkpoint = UiDefaultsCheckpoint()
            owner.record(checkpoint)
            owner.put(KEY, Color.RED, checkpoint)
            checkpoint.restore()
            val foreign = Color.CYAN
            UIManager.put(KEY, foreign)

            owner.restore()

            assertSame(foreign, UIManager.get(KEY))
        }

    @Test
    fun `restoration attempts other keys when a property listener fails`() =
        onEdt { owner ->
            UIManager.put(KEY, Color.BLUE)
            UIManager.put(OTHER_KEY, Color.GREEN)
            write(owner, KEY, Color.RED)
            write(owner, OTHER_KEY, Color.RED)
            val failure = IllegalStateException("native control listener failed")
            val listener =
                PropertyChangeListener { event ->
                    if (event.propertyName == KEY && event.newValue === Color.BLUE) throw failure
                }

            withListener(listener) {
                assertSame(failure, assertFailsWith<IllegalStateException> { owner.restore() })
            }
            owner.restore()
            assertSame(Color.BLUE, UIManager.get(KEY))
            assertSame(Color.GREEN, UIManager.get(OTHER_KEY))
        }

    @Test
    fun `restoration reobserves later keys changed by an earlier native listener`() =
        onEdt { owner ->
            UIManager.put(KEY, Color.BLUE)
            UIManager.put(OTHER_KEY, Color.GREEN)
            write(owner, KEY, Color.RED)
            write(owner, OTHER_KEY, Color.RED)
            val foreign = Color.MAGENTA
            val listener =
                PropertyChangeListener { event ->
                    if (event.propertyName == KEY && event.newValue === Color.BLUE) UIManager.put(OTHER_KEY, foreign)
                }

            withListener(listener) { owner.restore() }

            assertSame(Color.BLUE, UIManager.get(KEY))
            assertSame(foreign, UIManager.get(OTHER_KEY))
        }

    @Test
    fun `cleanup cancellation preserves its cause and still restores remaining controls`() =
        onEdt { owner ->
            UIManager.put(KEY, Color.BLUE)
            UIManager.put(OTHER_KEY, Color.GREEN)
            write(owner, KEY, Color.RED)
            write(owner, OTHER_KEY, Color.RED)
            val cancellation = CancellationException("native control cancelled")
            val listener =
                PropertyChangeListener { event ->
                    if (event.propertyName == KEY && event.newValue === Color.BLUE) throw cancellation
                }

            withListener(listener) {
                assertSame(cancellation, assertFailsWith<CancellationException> { owner.restore() })
            }
            assertSame(Color.BLUE, UIManager.get(KEY))
            assertSame(Color.GREEN, UIManager.get(OTHER_KEY))
            owner.restore()
        }

    @Test
    fun `native opt out keeps explicit tab colors independently for each mode`() {
        for (mode in GlowTabMode.entries) {
            val policy = accentUiPolicy(true, false, true, mode)
            val palette = accentUiPalette(AccentUiInput(Color.RED, null, mode, Color.WHITE.rgb, policy))
            assertFalse("Button.default.startBackground" in palette)
            assertFalse("Component.focusColor" in palette)
            assertEquals(mode != GlowTabMode.OFF, AccentUiKeys.TAB_UNDERLINE in palette)
            assertEquals(mode != GlowTabMode.OFF, policy.overrideTabGeometry)
            assertTrue(
                accentUiPalette(
                    AccentUiInput(
                        Color.RED,
                        null,
                        mode,
                        Color.WHITE.rgb,
                        accentUiPolicy(true, false, false, mode),
                    ),
                ).isEmpty(),
            )
            val ayu =
                accentUiPalette(
                    AccentUiInput(
                        Color.RED,
                        Color.GRAY,
                        mode,
                        Color.WHITE.rgb,
                        accentUiPolicy(false, false, false, mode),
                    ),
                )
            assertEquals(Color.RED.rgb, ayu["Component.focusColor"])
            assertEquals(
                if (mode ==
                    GlowTabMode.OFF
                ) {
                    Color.GRAY.rgb
                } else {
                    Color.RED.rgb
                },
                ayu[AccentUiKeys.TAB_UNDERLINE],
            )
        }
    }

    private fun write(
        owner: UiDefaultsOwnership,
        key: String,
        value: Color,
    ) {
        val checkpoint = UiDefaultsCheckpoint()
        owner.record(checkpoint)
        owner.put(key, value, checkpoint)
        checkpoint.commit()
    }

    private fun withListener(
        listener: PropertyChangeListener,
        action: () -> Unit,
    ) {
        val defaults = UIManager.getDefaults()
        defaults.addPropertyChangeListener(listener)
        try {
            action()
        } finally {
            defaults.removePropertyChangeListener(listener)
        }
    }

    private fun onEdt(action: (UiDefaultsOwnership) -> Unit) {
        SwingUtilities.invokeAndWait {
            val saved = listOf(KEY, OTHER_KEY).associateWith { UIManager.put(it, null) }
            val owner = UiDefaultsOwnership()
            try {
                action(owner)
            } finally {
                owner.restore()
                saved.forEach { (key, value) -> UIManager.put(key, value) }
            }
        }
    }

    companion object {
        private const val KEY = "Ayu.test.nativeAccent"
        private const val OTHER_KEY = "Ayu.test.nativeBorder"
    }
}
