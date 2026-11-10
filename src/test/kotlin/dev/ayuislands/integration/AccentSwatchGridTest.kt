package dev.ayuislands.integration

import com.intellij.testFramework.ApplicationRule
import dev.ayuislands.accent.AYU_ACCENT_PRESETS
import dev.ayuislands.accent.toolbar.popup.PopupSwatch
import dev.ayuislands.settings.AccentSwatchGrid
import org.junit.Rule
import org.junit.Test
import java.awt.Component
import java.awt.Container
import java.awt.event.ActionEvent
import java.awt.event.KeyEvent
import javax.accessibility.AccessibleState
import javax.swing.AbstractButton
import javax.swing.JComponent
import javax.swing.KeyStroke
import javax.swing.SwingUtilities
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AccentSwatchGridTest {
    @get:Rule
    val application = ApplicationRule()

    @Test
    fun `twelve named swatches form equal two by six rows with a separate Custom action`() {
        SwingUtilities.invokeAndWait {
            val chosen = mutableListOf<String>()
            val grid = AccentSwatchGrid(chosen::add) {}
            grid.render("#FFD966", isEditable = true)
            val swatches = descendants(grid).filterIsInstance<PopupSwatch>()
            assertEquals(AYU_ACCENT_PRESETS.map { it.hex }, swatches.map { it.hex.value })
            val custom = descendants(grid).filterIsInstance<AbstractButton>().single { it.text == "Custom…" }
            for (width in listOf(320, 480, 760)) {
                grid.setSize(width, grid.preferredSize.height)
                layoutTree(grid)
                val positions = swatches.map { SwingUtilities.convertPoint(it.parent, it.location, grid) }
                assertEquals(2, positions.map { it.y }.distinct().size)
                assertEquals(listOf(6, 6), positions.groupBy { it.y }.values.map { it.size })
                assertEquals(1, swatches.map { it.size }.distinct().size)
                val customY = SwingUtilities.convertPoint(custom.parent, custom.location, grid).y
                assertTrue(customY >= positions.maxOf { it.y } + swatches.first().height)
                for ((preset, swatch) in AYU_ACCENT_PRESETS.zip(swatches)) {
                    assertTrue(swatch.accessibleContext.accessibleName.contains(preset.name))
                    assertTrue(swatch.toolTipText.contains(preset.hex))
                    assertTrue(swatch.isFocusable)
                }
            }
            assertTrue(chosen.isEmpty())
        }
    }

    @Test
    fun `render preserves caller selection and only explicit enabled actions emit intents`() {
        SwingUtilities.invokeAndWait {
            val chosen = mutableListOf<String>()
            var customRequests = 0
            val grid = AccentSwatchGrid(chosen::add) { customRequests++ }
            val swatches = descendants(grid).filterIsInstance<PopupSwatch>()
            val buttons = descendants(grid).filterIsInstance<AbstractButton>()
            val custom = buttons.single { it.text == "Custom…" }

            for (raw in listOf(null, "future-raw-color", "#123456")) {
                grid.render(raw, isEditable = true)
                assertTrue(swatches.none { it.selected })
                if (raw != null) assertTrue(custom.toolTipText.contains(raw))
            }
            assertNotNull(custom.icon, "Custom must retain the rendered color specimen")
            assertTrue(custom.toolTipText.contains("#123456"))
            val firstSwatch: Component = swatches.first()
            val first = buttons.single { it === firstSwatch }
            first.doClick(0)
            assertEquals(listOf(AYU_ACCENT_PRESETS.first().hex), chosen)
            assertTrue(swatches.none { it.selected }, "Only the observing owner accepts a selection")
            custom.doClick(0)
            assertEquals(1, customRequests)

            val selected = AYU_ACCENT_PRESETS.last().hex
            grid.render(selected.lowercase(), isEditable = true)
            assertEquals(listOf(selected), swatches.filter { it.selected }.map { it.hex.value })
            assertTrue(
                swatches
                    .last()
                    .accessibleContext.accessibleStateSet
                    .contains(AccessibleState.CHECKED),
            )
            grid.render(selected, isEditable = false)
            assertTrue(buttons.all { !it.isEnabled })
            assertTrue(custom.isVisible, "Permission changes must not remove the custom color control")
            buttons.forEach { it.doClick(0) }
            assertEquals(listOf(AYU_ACCENT_PRESETS.first().hex), chosen)
            assertEquals(1, customRequests)
            assertEquals(listOf(selected), swatches.filter { it.selected }.map { it.hex.value })
        }
    }

    @Test
    fun `native Space binding and accessibility activate the same guarded swatch action`() {
        SwingUtilities.invokeAndWait {
            val chosen = mutableListOf<String>()
            val grid = AccentSwatchGrid(chosen::add) {}
            grid.render(null, isEditable = true)
            val swatch = descendants(grid).filterIsInstance<PopupSwatch>().first()
            space(swatch)
            assertEquals(listOf(swatch.hex.value), chosen)
            val accessible = assertNotNull(swatch.accessibleContext.accessibleAction)
            assertEquals(1, accessible.accessibleActionCount)
            assertTrue(accessible.doAccessibleAction(0))
            assertEquals(listOf(swatch.hex.value, swatch.hex.value), chosen)
            grid.render(null, isEditable = false)
            space(swatch)
            accessible.doAccessibleAction(0)
            assertEquals(2, chosen.size)
            assertFalse(swatch.selected)
        }
    }

    private fun space(component: JComponent) {
        for (released in listOf(false, true)) {
            val stroke = KeyStroke.getKeyStroke(KeyEvent.VK_SPACE, 0, released)
            val key = assertNotNull(component.getInputMap(JComponent.WHEN_FOCUSED).get(stroke))
            val action = assertNotNull(component.actionMap.get(key))
            action.actionPerformed(ActionEvent(component, ActionEvent.ACTION_PERFORMED, "space"))
        }
    }

    private fun descendants(root: Container): List<Component> =
        root.components.flatMap { listOf(it) + if (it is Container) descendants(it) else emptyList() }

    private fun layoutTree(component: Component) {
        if (component is Container) {
            component.doLayout()
            component.components.forEach(::layoutTree)
        }
    }
}
