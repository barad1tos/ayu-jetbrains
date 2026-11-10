package dev.ayuislands.accent.toolbar

import com.intellij.util.ui.JBUI
import dev.ayuislands.accent.AccentContext
import dev.ayuislands.accent.AccentResolver
import dev.ayuislands.studio.session.StudioQuickCommand
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.verify
import java.awt.event.MouseEvent
import javax.swing.JComponent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class QuickSwitcherChipPinToggleTest {
    @Test fun `inner click sends Pin or Unpin without tentatively rewriting ordered mappings`() {
        QuickSwitcherFixture().use { fixture ->
            fixture.mappings.projectAccents["/other"] = "#123456"
            val chip = QuickSwitcherChipComponent()
            click(chip, true)
            assertEquals(StudioQuickCommand.Pin(fixture.hex), fixture.requests.single().second)
            fixture.source = AccentResolver.Source.PROJECT_OVERRIDE
            click(chip, true)
            assertEquals(StudioQuickCommand.Unpin, fixture.requests.last().second)
            assertEquals(
                listOf("/other"),
                fixture.mappings.projectAccents.keys
                    .toList(),
            )
        }
    }

    @Test fun `outer free and external inner clicks keep the existing popup route`() {
        QuickSwitcherFixture().use { fixture ->
            mockkObject(QuickSwitcherPopup)
            every { QuickSwitcherPopup.show(any(), any()) } returns Unit
            val chip = QuickSwitcherChipComponent()
            click(chip, false)
            fixture.licensed = false
            click(chip, true)
            fixture.licensed = true
            fixture.context = AccentContext.External
            click(chip, true)
            verify(exactly = 3) { QuickSwitcherPopup.show(chip, chip) }
            assertTrue(fixture.requests.isEmpty())
        }
    }

    @Test fun `missing target cannot create a pin or reuse the previous project color`() {
        QuickSwitcherFixture().use { fixture ->
            val chip = QuickSwitcherChipComponent()
            fixture.available = false
            click(chip, true)
            chip.refreshFromFocusedProject()
            assertTrue(fixture.requests.isEmpty())
            assertEquals(null, chip.icon)
        }
    }

    private fun click(
        source: JComponent,
        inner: Boolean,
    ) {
        val coordinate = if (inner) JBUI.scale(QuickSwitcherChipComponent.CHIP_BOX_PX) / 2 else 0
        val event =
            MouseEvent(source, MouseEvent.MOUSE_PRESSED, 0L, 0, coordinate, coordinate, 1, false, MouseEvent.BUTTON1)
        source.mouseListeners.forEach { it.mousePressed(event) }
    }
}
