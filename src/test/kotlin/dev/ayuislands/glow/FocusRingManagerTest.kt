package dev.ayuislands.glow

import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import java.awt.Color
import java.awt.Component
import java.awt.Window
import java.awt.event.FocusEvent
import java.awt.event.FocusListener
import java.awt.event.HierarchyListener
import java.awt.image.BufferedImage
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JTextField
import javax.swing.SwingUtilities
import javax.swing.border.Border
import javax.swing.border.EmptyBorder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class FocusRingManagerTest {
    @Test
    fun `text fields, combo boxes, and named search fields are eligible`() =
        onFocusEdt {
            val manager = FocusRingManager()
            assertTrue(manager.isTextInputComponent(JTextField()))
            assertTrue(manager.isTextInputComponent(JComboBox<String>()))
            assertTrue(manager.isTextInputComponent(SearchTextFieldFixture()))
        }

    @Test
    fun `panels, labels, and buttons are ineligible`() =
        onFocusEdt {
            val manager = FocusRingManager()
            assertFalse(manager.isTextInputComponent(JPanel()))
            assertFalse(manager.isTextInputComponent(JLabel()))
            assertFalse(manager.isTextInputComponent(JButton()))
        }

    @Test
    fun `initialization attaches one listener pair only to nested eligible controls`() =
        withFocusWindows { manager, windows ->
            val field = JTextField()
            val combo = JComboBox<String>()
            val search = SearchTextFieldFixture()
            val button = JButton()
            val panel = JPanel().apply { add(JPanel().apply { add(field) }) }
            panel.add(combo)
            panel.add(search)
            panel.add(button)
            val baselines = listOf(field, combo, search, button).associateWith(::listenerBaseline)
            windows += focusWindow(panel)

            manager.initializeFocusRingGlow(Color.ORANGE, GlowStyle.SOFT, 50)

            listOf(field, combo, search).forEach { component ->
                assertEquals(baselines.getValue(component).focusCount + 1, component.focusListeners.size)
                assertEquals(baselines.getValue(component).hierarchyCount + 1, component.hierarchyListeners.size)
            }
            assertEquals(baselines.getValue(button).focusCount, button.focusListeners.size)
            assertEquals(baselines.getValue(button).hierarchyCount, button.hierarchyListeners.size)
        }

    @Test
    fun `repeated initialization neither duplicates listeners nor wraps an existing ring`() =
        withFocusWindows { manager, windows ->
            val field = FocusField().apply { isFocusSimulated = true }
            val originalBorder = field.border
            val baseline = listenerBaseline(field)
            windows += focusWindow(JPanel().apply { add(field) })

            manager.initializeFocusRingGlow(Color.ORANGE, GlowStyle.SOFT, 50)
            val installedBorder = assertIs<GlowFocusBorder>(field.border)
            manager.initializeFocusRingGlow(Color.RED, GlowStyle.SHARP_NEON, 90)

            assertSame(installedBorder, field.border)
            assertEquals(baseline.focusCount + 1, field.focusListeners.size)
            assertEquals(baseline.hierarchyCount + 1, field.hierarchyListeners.size)
            manager.removeFocusListeners()
            assertSame(originalBorder, field.border)
        }

    @Test
    fun `later window discovery installs new controls without changing processed controls`() =
        withFocusWindows { manager, windows ->
            val first = JTextField()
            val second = JTextField()
            val firstBaseline = listenerBaseline(first)
            val secondBaseline = listenerBaseline(second)
            windows += focusWindow(JPanel().apply { add(first) })
            manager.initializeFocusRingGlow(Color.ORANGE, GlowStyle.SOFT, 50)
            val firstOwnedListener = addedFocusListener(first, firstBaseline)
            windows += focusWindow(JPanel().apply { add(second) })

            manager.initializeFocusRingGlow(Color.ORANGE, GlowStyle.SOFT, 50)

            assertSame(firstOwnedListener, addedFocusListener(first, firstBaseline))
            assertEquals(firstBaseline.focusCount + 1, first.focusListeners.size)
            assertEquals(secondBaseline.focusCount + 1, second.focusListeners.size)
        }

    @Test
    fun `focus events preserve the original border and ignore duplicate gains`() =
        withFocusWindows { manager, windows ->
            val originalBorder = EmptyBorder(2, 3, 4, 5)
            val field = JTextField().apply { border = originalBorder }
            val baseline = listenerBaseline(field)
            windows += focusWindow(JPanel().apply { add(field) })
            manager.initializeFocusRingGlow(Color.ORANGE, GlowStyle.SOFT, 50)
            val listener = addedFocusListener(field, baseline)

            listener.focusGained(FocusEvent(field, FocusEvent.FOCUS_GAINED))
            val ring = assertIs<GlowFocusBorder>(field.border)
            listener.focusGained(FocusEvent(field, FocusEvent.FOCUS_GAINED))
            assertSame(ring, field.border)
            listener.focusLost(FocusEvent(field, FocusEvent.FOCUS_LOST))

            assertSame(originalBorder, field.border)
        }

    @Test
    fun `enabled update changes focused output while retaining listener and external border`() =
        withFocusWindows { manager, windows ->
            val originalBorder = EmptyBorder(5, 4, 3, 2)
            val field =
                FocusField().apply {
                    isFocusSimulated = true
                    border = originalBorder
                }
            val baseline = listenerBaseline(field)
            windows += focusWindow(JPanel().apply { add(field) })
            manager.initializeFocusRingGlow(Color.ORANGE, GlowStyle.SOFT, 30)
            val firstPixels = paintCurrentBorder(field)
            val replacement = EmptyBorder(5, 4, 3, 2)
            field.border = replacement

            manager.updateFocusRingGlow(Color.CYAN, GlowStyle.SHARP_NEON, 100, enabled = true)

            assertIs<GlowFocusBorder>(field.border)
            val updatedPixels = paintCurrentBorder(field)
            val expectedPixels = paintBorder(field, GlowFocusBorder(replacement, Color.CYAN, GlowStyle.SHARP_NEON, 100))
            val wrongColor = paintBorder(field, GlowFocusBorder(replacement, Color.ORANGE, GlowStyle.SHARP_NEON, 100))
            val wrongStyle = paintBorder(field, GlowFocusBorder(replacement, Color.CYAN, GlowStyle.SOFT, 100))
            val wrongIntensity = paintBorder(field, GlowFocusBorder(replacement, Color.CYAN, GlowStyle.SHARP_NEON, 30))
            assertNotEquals(firstPixels, updatedPixels)
            assertEquals(expectedPixels, updatedPixels)
            assertNotEquals(wrongColor, updatedPixels)
            assertNotEquals(wrongStyle, updatedPixels)
            assertNotEquals(wrongIntensity, updatedPixels)
            assertEquals(baseline.focusCount + 1, field.focusListeners.size)
            assertEquals(baseline.hierarchyCount + 1, field.hierarchyListeners.size)
            manager.removeFocusListeners()
            assertSame(replacement, field.border)
        }

    @Test
    fun `focus lifecycle preserves nested component order`() =
        withFocusWindows { manager, windows ->
            val nested =
                JPanel().apply {
                    add(JTextField())
                    add(JButton())
                    add(JComboBox<String>())
                }
            val root =
                JPanel().apply {
                    add(JLabel("Before"))
                    add(nested)
                    add(JLabel("After"))
                }
            val rootOrder = root.components.toList()
            val nestedOrder = nested.components.toList()
            windows += focusWindow(root)

            manager.initializeFocusRingGlow(Color.ORANGE, GlowStyle.SOFT, 50)
            assertComponentOrder(root, rootOrder, nested, nestedOrder)
            manager.updateFocusRingGlow(Color.CYAN, GlowStyle.SHARP_NEON, 80, enabled = true)
            assertComponentOrder(root, rootOrder, nested, nestedOrder)
            manager.updateFocusRingGlow(Color.CYAN, GlowStyle.SHARP_NEON, 80, enabled = false)
            assertComponentOrder(root, rootOrder, nested, nestedOrder)
            manager.updateFocusRingGlow(Color.CYAN, GlowStyle.SHARP_NEON, 80, enabled = true)
            assertComponentOrder(root, rootOrder, nested, nestedOrder)
            manager.removeFocusListeners()
            assertComponentOrder(root, rootOrder, nested, nestedOrder)
            manager.dispose()
            assertComponentOrder(root, rootOrder, nested, nestedOrder)
        }

    @Test
    fun `disable and reenable restore exact state and reproduce focused output`() =
        withFocusWindows { manager, windows ->
            val field = FocusField().apply { isFocusSimulated = true }
            val originalBorder = field.border
            val baseline = listenerBaseline(field)
            windows += focusWindow(JPanel().apply { add(field) })
            manager.initializeFocusRingGlow(Color.ORANGE, GlowStyle.SOFT, 50)
            val initialPixels = paintCurrentBorder(field)

            manager.updateFocusRingGlow(Color.ORANGE, GlowStyle.SOFT, 50, enabled = false)
            assertSame(originalBorder, field.border)
            assertListenerBaseline(field, baseline)
            manager.updateFocusRingGlow(Color.ORANGE, GlowStyle.SOFT, 50, enabled = true)

            assertEquals(initialPixels, paintCurrentBorder(field))
            assertEquals(baseline.focusCount + 1, field.focusListeners.size)
            assertEquals(baseline.hierarchyCount + 1, field.hierarchyListeners.size)
        }

    @Test
    fun `repeated cleanup preserves third party state and permits initialization again`() =
        withFocusWindows { manager, windows ->
            val externalListener =
                object : FocusListener {
                    override fun focusGained(event: FocusEvent) = Unit

                    override fun focusLost(event: FocusEvent) = Unit
                }

            val externalHierarchyListener = HierarchyListener { }
            val field =
                FocusField().apply {
                    isFocusSimulated = true
                    addFocusListener(externalListener)
                    addHierarchyListener(externalHierarchyListener)
                }
            val originalBorder = field.border
            val baseline = listenerBaseline(field)
            windows += focusWindow(JPanel().apply { add(field) })
            manager.initializeFocusRingGlow(Color.ORANGE, GlowStyle.SOFT, 50)

            manager.removeFocusListeners()
            manager.removeFocusListeners()
            manager.dispose()
            manager.dispose()

            assertSame(originalBorder, field.border)
            assertListenerBaseline(field, baseline)
            assertTrue(field.focusListeners.contains(externalListener))
            assertTrue(field.hierarchyListeners.contains(externalHierarchyListener))
            manager.initializeFocusRingGlow(Color.ORANGE, GlowStyle.SOFT, 50)
            assertIs<GlowFocusBorder>(field.border)
            assertEquals(baseline.focusCount + 1, field.focusListeners.size)
        }
}

private data class ListenerBaseline(
    val focusListeners: List<FocusListener>,
    val hierarchyListeners: List<HierarchyListener>,
) {
    val focusCount: Int = focusListeners.size
    val hierarchyCount: Int = hierarchyListeners.size
}

private class SearchTextFieldFixture : JPanel()

private class FocusField : JTextField() {
    var isFocusSimulated = false

    override fun hasFocus(): Boolean = isFocusSimulated
}

private fun withFocusWindows(test: (FocusRingManager, MutableList<Window>) -> Unit) {
    onFocusEdt {
        val windows = mutableListOf<Window>()
        mockkStatic(Window::class)
        try {
            every { Window.getWindows() } answers { windows.toTypedArray() }
            val manager = FocusRingManager()
            try {
                test(manager, windows)
            } finally {
                manager.dispose()
            }
        } finally {
            unmockkStatic(Window::class)
        }
    }
}

private fun focusWindow(content: JPanel): Window = mockk { every { components } returns arrayOf(content) }

private fun listenerBaseline(component: JComponent): ListenerBaseline =
    ListenerBaseline(component.focusListeners.toList(), component.hierarchyListeners.toList())

private fun addedFocusListener(
    field: JTextField,
    baseline: ListenerBaseline,
): FocusListener = field.focusListeners.single { it !in baseline.focusListeners }

private fun assertListenerBaseline(
    field: JTextField,
    baseline: ListenerBaseline,
) {
    assertEquals(baseline.focusListeners, field.focusListeners.toList())
    assertEquals(baseline.hierarchyListeners, field.hierarchyListeners.toList())
}

private fun assertComponentOrder(
    root: JPanel,
    rootOrder: List<Component>,
    nested: JPanel,
    nestedOrder: List<Component>,
) {
    assertEquals(rootOrder.size, root.componentCount)
    assertEquals(nestedOrder.size, nested.componentCount)
    root.components.forEachIndexed { index, component -> assertSame(rootOrder[index], component) }
    nested.components.forEachIndexed { index, component -> assertSame(nestedOrder[index], component) }
}

private fun paintCurrentBorder(field: JTextField): List<Int> = paintBorder(field, field.border)

private fun paintBorder(
    field: JTextField,
    border: Border,
): List<Int> {
    val image = BufferedImage(100, 50, BufferedImage.TYPE_INT_ARGB)
    val graphics = image.createGraphics()
    try {
        border.paintBorder(field, graphics, 0, 0, image.width, image.height)
    } finally {
        graphics.dispose()
    }
    return image.getRGB(0, 0, image.width, image.height, null, 0, image.width).toList()
}

private fun onFocusEdt(block: () -> Unit) {
    if (SwingUtilities.isEventDispatchThread()) block() else SwingUtilities.invokeAndWait(block)
}
