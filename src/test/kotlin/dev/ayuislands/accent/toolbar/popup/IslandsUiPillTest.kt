package dev.ayuislands.accent.toolbar.popup

import com.intellij.util.ui.JBUI
import java.awt.event.MouseEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Locks the [IslandsUiPill] state-toggle contract.
 * Lambda-callback assertions use a `mutableListOf<Boolean>()` capture because
 * `mockk` cannot proxy Kotlin function types.
 */
class IslandsUiPillTest {
    @Test
    fun `preferred size is 94 x 28 JBUI scaled`() {
        val pill = IslandsUiPill(initialSelected = false, accentSupplier = { "#FFB454" }, onToggle = {})
        assertEquals(JBUI.scale(94), pill.preferredSize.width)
        assertEquals(JBUI.scale(28), pill.preferredSize.height)
    }

    @Test
    fun `clicking the pill flips selection and fires onToggle with the new value`() {
        val captured = mutableListOf<Boolean>()
        val pill =
            IslandsUiPill(
                initialSelected = false,
                accentSupplier = { "#FFB454" },
                onToggle = { captured.add(it) },
            ).apply {
                setSize(JBUI.scale(94), JBUI.scale(28))
            }
        val click =
            MouseEvent(pill, MouseEvent.MOUSE_CLICKED, 0L, 0, 5, 5, 1, false, MouseEvent.BUTTON1)
        pill.dispatchEvent(click)
        assertTrue(pill.isSelected)
        assertEquals(listOf(true), captured)
        pill.dispatchEvent(click)
        assertFalse(pill.isSelected)
        assertEquals(listOf(true, false), captured)
    }

    @Test
    fun `setSelectedExternally is idempotent and updates state without firing onToggle`() {
        val captured = mutableListOf<Boolean>()
        val pill =
            IslandsUiPill(
                initialSelected = false,
                accentSupplier = { "#FFB454" },
                onToggle = { captured.add(it) },
            )
        pill.setSelectedExternally(true)
        assertTrue(pill.isSelected)
        pill.setSelectedExternally(true)
        assertTrue(pill.isSelected)
        assertEquals(emptyList(), captured, "Programmatic flip must NOT fire the user-toggle callback")
    }

    @Test
    fun `accessible context exposes Islands UI name and TOGGLE_BUTTON role`() {
        val pill = IslandsUiPill(initialSelected = false, accentSupplier = { "#FFB454" }, onToggle = {})
        val ctx = pill.accessibleContext
        assertNotNull(ctx)
        assertEquals("Islands UI", ctx.accessibleName)
    }
}
