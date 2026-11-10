package dev.ayuislands.accent.toolbar

import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.ui.ColorPicker
import com.intellij.ui.components.ActionLink
import com.intellij.util.ui.JBUI
import dev.ayuislands.accent.AYU_ACCENT_PRESETS
import dev.ayuislands.accent.AccentContext
import dev.ayuislands.accent.AyuVariant
import dev.ayuislands.accent.toolbar.popup.Density
import dev.ayuislands.accent.toolbar.popup.PopupSwatch
import dev.ayuislands.settings.AccentPanelEvent
import dev.ayuislands.studio.model.AcceptanceBlock
import dev.ayuislands.studio.model.AcceptanceResult
import dev.ayuislands.studio.model.StudioList
import dev.ayuislands.studio.session.ImmediateExecutionResult
import dev.ayuislands.studio.session.StudioQuickCommand
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.verify
import java.awt.Color
import java.awt.Component
import java.awt.Container
import java.awt.GridLayout
import javax.swing.JLabel
import javax.swing.JPanel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class QuickSwitcherAccentGridTest {
    @Test fun `native swatch geometry order and link icons remain unchanged`() {
        QuickSwitcherFixture().use { _ ->
            val grid = QuickSwitcherAccentGrid()
            val north = (grid.component as JPanel).components.filterIsInstance<JPanel>().first()
            val layout = north.layout as GridLayout
            assertEquals(2, layout.rows)
            assertEquals(6, layout.columns)
            assertEquals(JBUI.scale(Density.SWATCH_GAP), layout.hgap)
            assertEquals(layout.hgap, layout.vgap)
            val swatches = north.components.filterIsInstance<PopupSwatch>()
            assertEquals(AYU_ACCENT_PRESETS.map { it.hex }, swatches.map { it.hex.value })
            assertEquals(
                listOf("Custom…", "More…"),
                descendants(grid.component)
                    .filterIsInstance<ActionLink>()
                    .map {
                        it.text
                    }.toList(),
            )
            assertTrue(descendants(grid.component).filterIsInstance<JLabel>().any { it.icon != null })
        }
    }

    @Test fun `selection changes only after accepted command and reflects the accepted color`() {
        QuickSwitcherFixture().use { fixture ->
            val grid = QuickSwitcherAccentGrid()
            val swatches = descendants(grid.component).filterIsInstance<PopupSwatch>().toList()
            val chosen = swatches.first { !it.selected }
            val before = swatches.map { it.selected }
            chosen.doClick()
            assertEquals(before, swatches.map { it.selected })
            fixture.completions.single()(
                ImmediateExecutionResult.Complete(
                    AcceptanceResult.Blocked(StudioList(listOf(AcceptanceBlock.READ_ONLY))),
                ),
            )
            assertEquals(before, swatches.map { it.selected })
            chosen.doClick()
            fixture.hex = chosen.hex.value
            fixture.completions.last()(ImmediateExecutionResult.Complete(AcceptanceResult.NoChanges))
            assertEquals(listOf(chosen), swatches.filter { it.selected })
        }
    }

    @Test fun `external swatch produces the explicit manual external command`() {
        QuickSwitcherFixture().use { fixture ->
            fixture.context = AccentContext.External
            val grid = QuickSwitcherAccentGrid()
            val chosen = descendants(grid.component).filterIsInstance<PopupSwatch>().first()
            chosen.doClick()
            assertEquals(StudioQuickCommand.ExternalAccent(chosen.hex.value), fixture.requests.single().second)
        }
    }

    @Test fun `native custom picker is opaque seeded with accepted color and cancellation submits nothing`() {
        QuickSwitcherFixture().use { fixture ->
            mockkStatic(ColorPicker::class)
            every { ColorPicker.showDialog(any(), any<String>(), any(), any<Boolean>(), any(), any<Boolean>()) } returns
                null
            val grid = QuickSwitcherAccentGrid()
            val link = descendants(grid.component).filterIsInstance<ActionLink>().first()
            link.doClick()
            verify(exactly = 1) {
                ColorPicker.showDialog(
                    any(),
                    "Choose Accent Color",
                    Color.decode(fixture.hex),
                    false,
                    emptyList(),
                    false,
                )
            }
            assertTrue(fixture.requests.isEmpty())
            every { ColorPicker.showDialog(any(), any<String>(), any(), any<Boolean>(), any(), any<Boolean>()) } returns
                Color(0x12, 0x34, 0x56)
            link.doClick()
            assertEquals(
                StudioQuickCommand.Accent(AccentPanelEvent.Color(AyuVariant.MIRAGE, "#123456")),
                fixture.requests.single().second,
            )
        }
    }

    @Test fun `More keeps the captured project and native settings route`() {
        QuickSwitcherFixture().use { fixture ->
            mockkStatic(ShowSettingsUtil::class)
            val settings = mockk<ShowSettingsUtil>(relaxed = true)
            every { ShowSettingsUtil.getInstance() } returns settings
            val grid = QuickSwitcherAccentGrid()
            descendants(grid.component).filterIsInstance<ActionLink>().last().doClick()
            verify(exactly = 1) { settings.showSettingsDialog(fixture.project, "Ayu Islands") }
        }
    }

    @Test fun `unavailable capture does not invent selected accent or submit`() {
        QuickSwitcherFixture().use { fixture ->
            fixture.available = false
            val grid = QuickSwitcherAccentGrid()
            val swatches = descendants(grid.component).filterIsInstance<PopupSwatch>().toList()
            assertFalse(swatches.any { it.selected })
            swatches.first().doClick()
            assertTrue(fixture.requests.isEmpty())
        }
    }

    private fun descendants(component: Component): Sequence<Component> =
        sequence {
            yield(component)
            if (component is Container) for (child in component.components) yieldAll(descendants(child))
        }
}
