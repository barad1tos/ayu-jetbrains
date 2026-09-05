package dev.ayuislands.glow

import java.awt.Color
import java.awt.Component
import java.awt.event.FocusEvent
import java.awt.event.FocusListener
import java.awt.event.HierarchyEvent
import javax.swing.JPanel
import javax.swing.JTextField
import javax.swing.SwingUtilities
import javax.swing.border.EmptyBorder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame

class FocusRingCleanupTest {
    @Test
    fun `reenabling glow on an already focused field restores the ring immediately`() {
        SwingUtilities.invokeAndWait {
            val manager = FocusRingManager()
            val field =
                object : JTextField() {
                    var isFocusSimulated = false

                    override fun hasFocus(): Boolean = isFocusSimulated
                }
            field.isFocusSimulated = true
            val border = field.border
            try {
                repeat(2) {
                    install(manager, field)
                    assertIs<GlowFocusBorder>(field.border)
                    manager.updateFocusRingGlow(Color.ORANGE, GlowStyle.SOFT, 50, enabled = false)
                    assertSame(border, field.border)
                }
            } finally {
                manager.dispose()
            }
        }
    }

    @Test
    fun `disable restores focused border and removes both listeners`() =
        withFocusRing { manager, field ->
            val border = field.border
            val focusListeners = field.focusListeners.toList()
            val hierarchyListeners = field.hierarchyListeners.toList()
            gainFocus(install(manager, field), field)

            manager.updateFocusRingGlow(Color.ORANGE, GlowStyle.SOFT, 50, enabled = false)

            assertSame(border, field.border)
            assertEquals(focusListeners, field.focusListeners.toList())
            assertEquals(hierarchyListeners, field.hierarchyListeners.toList())
        }

    @Test
    fun `dispose restores focused border and remains safe when repeated`() =
        withFocusRing { manager, field ->
            val border = field.border
            gainFocus(install(manager, field), field)

            manager.dispose()
            manager.dispose()

            assertSame(border, field.border)
        }

    @Test
    fun `remove restores all focused components`() =
        withFocusRing { manager, first ->
            val second = JTextField()
            val firstBorder = first.border
            val secondBorder = second.border
            gainFocus(install(manager, first), first)
            gainFocus(install(manager, second), second)

            manager.removeFocusListeners()

            assertSame(firstBorder, first.border)
            assertSame(secondBorder, second.border)
        }

    @Test
    fun `displayability loss restores border and permits clean reinstallation`() =
        withFocusRing { manager, field ->
            val border = field.border
            val hierarchyListeners = field.hierarchyListeners.toList()
            gainFocus(install(manager, field), field)
            val event =
                HierarchyEvent(
                    field,
                    HierarchyEvent.HIERARCHY_CHANGED,
                    field,
                    field.parent,
                    HierarchyEvent.DISPLAYABILITY_CHANGED.toLong(),
                )

            field.hierarchyListeners.forEach { it.hierarchyChanged(event) }

            assertSame(border, field.border)
            assertEquals(hierarchyListeners, field.hierarchyListeners.toList())
            gainFocus(install(manager, field), field)
            manager.removeFocusListeners()
            assertSame(border, field.border)
        }

    @Test
    fun `disable and reenable do not nest borders or accumulate listeners`() =
        withFocusRing { manager, field ->
            val border = field.border
            val focusListeners = field.focusListeners.toList()
            val hierarchyListeners = field.hierarchyListeners.toList()

            repeat(3) {
                val listener = install(manager, field)
                gainFocus(listener, field)
                listener.focusLost(FocusEvent(field, FocusEvent.FOCUS_LOST))
                assertSame(border, field.border)
                gainFocus(listener, field)
                manager.updateFocusRingGlow(Color.ORANGE, GlowStyle.SOFT, 50, enabled = false)
                assertSame(border, field.border)
                assertEquals(focusListeners, field.focusListeners.toList())
                assertEquals(hierarchyListeners, field.hierarchyListeners.toList())
            }
        }

    @Test
    fun `focus loss and detach preserve an externally replaced border`() =
        withFocusRing { manager, field ->
            val listener = install(manager, field)
            gainFocus(listener, field)
            val replacement = EmptyBorder(2, 3, 4, 5)
            field.border = replacement

            listener.focusLost(FocusEvent(field, FocusEvent.FOCUS_LOST))
            assertSame(replacement, field.border)
            gainFocus(listener, field)
            val newerReplacement = EmptyBorder(5, 4, 3, 2)
            field.border = newerReplacement
            manager.dispose()

            assertSame(newerReplacement, field.border)
        }

    @Test
    fun `null original border is restored on disable`() =
        withFocusRing { manager, field ->
            field.border = null
            gainFocus(install(manager, field), field)

            manager.updateFocusRingGlow(Color.ORANGE, GlowStyle.SOFT, 50, enabled = false)

            assertNull(field.border)
        }

    @Test
    fun `duplicate focus events neither nest nor erase the original border`() =
        withFocusRing { manager, field ->
            val border = field.border
            val listener = install(manager, field)
            listener.focusLost(FocusEvent(field, FocusEvent.FOCUS_LOST))
            assertSame(border, field.border)
            gainFocus(listener, field)
            val wrapper = field.border

            gainFocus(listener, field)
            assertSame(wrapper, field.border)
            repeat(2) { listener.focusLost(FocusEvent(field, FocusEvent.FOCUS_LOST)) }

            assertSame(border, field.border)
        }

    @Test
    fun `detached listener cannot reinstall a glow border`() =
        withFocusRing { manager, field ->
            val border = field.border
            val listener = install(manager, field)
            manager.removeFocusListeners()

            listener.focusGained(FocusEvent(field, FocusEvent.FOCUS_GAINED))
            listener.focusLost(FocusEvent(field, FocusEvent.FOCUS_LOST))

            assertSame(border, field.border)
            listener.focusGained(FocusEvent(field, FocusEvent.FOCUS_GAINED))
            assertSame(border, field.border)
        }
}

private fun withFocusRing(test: (FocusRingManager, JTextField) -> Unit) {
    SwingUtilities.invokeAndWait {
        val manager = FocusRingManager()
        try {
            test(manager, JTextField())
        } finally {
            manager.dispose()
        }
    }
}

private fun install(
    manager: FocusRingManager,
    field: JTextField,
): FocusListener {
    val before = field.focusListeners.toSet()
    val container = JPanel().apply { add(field) }
    // Exercise real recursive installation without requiring a native Window in headless tests.
    val installer =
        FocusRingManager::class.java.getDeclaredMethod(
            "installFocusListenersRecursively",
            Component::class.java,
            Color::class.java,
            GlowStyle::class.java,
            Int::class.javaPrimitiveType,
        )
    installer.isAccessible = true
    installer.invoke(manager, container, Color.ORANGE, GlowStyle.SOFT, 50)
    return field.focusListeners.single { it !in before }
}

private fun gainFocus(
    listener: FocusListener,
    field: JTextField,
) {
    listener.focusGained(FocusEvent(field, FocusEvent.FOCUS_GAINED))
    assertIs<GlowFocusBorder>(field.border)
}
