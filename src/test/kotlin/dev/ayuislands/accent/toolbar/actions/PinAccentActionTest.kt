package dev.ayuislands.accent.toolbar.actions

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.Presentation
import dev.ayuislands.accent.AccentContext
import dev.ayuislands.accent.AyuVariant
import dev.ayuislands.accent.toolbar.QuickSwitcherFixture
import dev.ayuislands.studio.session.StudioQuickCommand
import io.mockk.every
import io.mockk.mockk
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PinAccentActionTest {
    @Test fun `pin submits the captured project color without mutating accepted mappings`() {
        QuickSwitcherFixture().use { fixture ->
            fixture.mappings.projectAccents["/other"] = "#123456"
            PinAccentAction().actionPerformed(event())
            assertEquals(
                listOf<Pair<dev.ayuislands.studio.model.TargetObservation, StudioQuickCommand>>(
                    fixture.target to StudioQuickCommand.Pin(fixture.hex),
                ),
                fixture.requests,
            )
            assertEquals(mapOf("/other" to "#123456"), fixture.mappings.projectAccents)
        }
    }

    @Test fun `pin retains Ayu and premium gates on BGT and at invocation`() {
        QuickSwitcherFixture().use { fixture ->
            val action = PinAccentAction()
            assertEquals(ActionUpdateThread.BGT, action.getActionUpdateThread())
            for (native in listOf(false, true)) {
                for (licensed in listOf(false, true)) {
                    fixture.context = if (native) AccentContext.Ayu(AyuVariant.MIRAGE) else AccentContext.External
                    fixture.licensed = licensed
                    val event = event()
                    action.update(event)
                    assertEquals(native && licensed, event.presentation.isEnabledAndVisible)
                    if (!native || !licensed) action.actionPerformed(event)
                }
            }
            assertTrue(fixture.requests.isEmpty())
        }
    }

    @Test fun `missing authority never becomes a global pin`() {
        QuickSwitcherFixture().use { fixture ->
            fixture.available = false
            PinAccentAction().actionPerformed(event())
            assertTrue(fixture.requests.isEmpty())
        }
    }

    private fun event(): AnActionEvent =
        mockk<AnActionEvent>(relaxed = true).also {
            every { it.presentation } returns Presentation()
        }
}
