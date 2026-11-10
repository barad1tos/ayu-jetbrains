package dev.ayuislands.glow

import java.awt.Color
import java.awt.event.FocusEvent
import java.awt.event.FocusListener
import java.awt.event.HierarchyListener
import javax.swing.JTextField
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

class GlowFocusListenerTest {
    @Test
    fun `partial registration retains exact cleanup through failures before and after removal`() =
        onEdt {
            val field = FaultingFocusField()
            val focusBefore = field.focusListeners.toList()
            val hierarchyBefore = field.hierarchyListeners.toList()
            val borderBefore = field.border
            var detachments = 0
            val listener = listener(field, onDetached = { detachments++ })
            field.afterHierarchyAdd = { error("After hierarchy registration") }
            try {
                assertFailsWith<IllegalStateException> { listener.attach() }
                field.beforeFocusRemove = { error("Before focus removal") }
                assertFailsWith<IllegalStateException> { listener.detach() }

                field.beforeFocusRemove = null
                field.afterHierarchyRemove = { error("After hierarchy removal") }
                assertFailsWith<IllegalStateException> { listener.detach() }
                assertEquals(0, detachments)

                field.afterHierarchyRemove = null
                listener.detach()
                listener.detach()
                assertEquals(1, detachments)
                assertEquals(focusBefore, field.focusListeners.toList())
                assertEquals(hierarchyBefore, field.hierarchyListeners.toList())
                assertSame(borderBefore, field.border)
            } finally {
                field.beforeFocusRemove = null
                field.afterHierarchyRemove = null
                listener.detach()
            }
        }

    @Test
    fun `detachment during native insertion prevents the remaining registration`() =
        onEdt {
            val field = FaultingFocusField()
            val focusBefore = field.focusListeners.toList()
            val hierarchyBefore = field.hierarchyListeners.toList()
            val listener = listener(field)
            field.afterFocusAdd = listener::detach
            try {
                assertFailsWith<IllegalStateException> { listener.attach() }

                assertEquals(focusBefore, field.focusListeners.toList())
                assertEquals(hierarchyBefore, field.hierarchyListeners.toList())
            } finally {
                listener.detach()
            }
        }

    @Test
    fun `replaced component owner interrupts attachment and preserves foreign listeners`() =
        onEdt {
            val field = FaultingFocusField()
            val focusBefore = field.focusListeners.toList()
            val hierarchyBefore = field.hierarchyListeners.toList()
            var isOwned = true
            val listener = listener(field, ownsComponent = { isOwned })
            field.afterFocusAdd = { isOwned = false }
            try {
                assertFailsWith<IllegalStateException> { listener.attach() }
                listener.detach()

                assertEquals(focusBefore, field.focusListeners.toList())
                assertEquals(hierarchyBefore, field.hierarchyListeners.toList())
            } finally {
                listener.detach()
            }
        }

    @Test
    fun `retired listener cannot be attached again`() =
        onEdt {
            val field = FaultingFocusField()
            val focusBefore = field.focusListeners.toList()
            val listener = listener(field)
            try {
                listener.attach()
                listener.detach()

                assertFailsWith<IllegalStateException> { listener.attach() }
                assertEquals(focusBefore, field.focusListeners.toList())
            } finally {
                listener.detach()
            }
        }

    @Test
    fun `late focus callbacks cannot write after component ownership changes`() =
        onEdt {
            val field = FaultingFocusField()
            val borderBefore = field.border
            var isOwned = true
            val listener = listener(field, ownsComponent = { isOwned })
            try {
                listener.attach()
                isOwned = false
                listener.focusGained(FocusEvent(field, FocusEvent.FOCUS_GAINED))

                assertSame(borderBefore, field.border)
            } finally {
                listener.detach()
            }
        }

    private fun listener(
        field: JTextField,
        ownsComponent: () -> Boolean = { true },
        onDetached: (GlowFocusListener) -> Unit = {},
    ): GlowFocusListener =
        GlowFocusListener(field, Color.ORANGE, GlowStyle.SOFT, 50, { true }, ownsComponent, onDetached)

    private fun onEdt(block: () -> Unit) {
        if (SwingUtilities.isEventDispatchThread()) block() else SwingUtilities.invokeAndWait(block)
    }
}

private class FaultingFocusField : JTextField() {
    var afterFocusAdd: (() -> Unit)? = null
    var afterHierarchyAdd: (() -> Unit)? = null
    var beforeFocusRemove: (() -> Unit)? = null
    var afterHierarchyRemove: (() -> Unit)? = null

    override fun addFocusListener(listener: FocusListener) {
        super.addFocusListener(listener)
        afterFocusAdd?.invoke()
    }

    override fun addHierarchyListener(listener: HierarchyListener) {
        super.addHierarchyListener(listener)
        afterHierarchyAdd?.invoke()
    }

    override fun removeFocusListener(listener: FocusListener) {
        beforeFocusRemove?.invoke()
        super.removeFocusListener(listener)
    }

    override fun removeHierarchyListener(listener: HierarchyListener) {
        super.removeHierarchyListener(listener)
        afterHierarchyRemove?.invoke()
    }
}
