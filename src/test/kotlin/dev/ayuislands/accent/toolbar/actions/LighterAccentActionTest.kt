package dev.ayuislands.accent.toolbar.actions

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.Presentation
import dev.ayuislands.accent.AccentContext
import dev.ayuislands.accent.AccentHex
import dev.ayuislands.accent.AyuVariant
import dev.ayuislands.accent.color.AccentHsl
import dev.ayuislands.accent.toolbar.QuickSwitcherCommands
import dev.ayuislands.accent.toolbar.QuickSwitcherFixture
import dev.ayuislands.settings.AccentPanelEvent
import dev.ayuislands.studio.session.StudioQuickCommand
import io.mockk.every
import io.mockk.mockk
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LighterAccentActionTest {
    @Test fun `updates use the existing context and license gate on BGT`() {
        QuickSwitcherFixture().use { fixture ->
            val action = LighterAccentAction()
            assertEquals(ActionUpdateThread.BGT, action.getActionUpdateThread())
            for (active in listOf(false, true)) {
                for (licensed in listOf(false, true)) {
                    fixture.context = if (active) AccentContext.Ayu(AyuVariant.MIRAGE) else null
                    fixture.licensed = licensed
                    val event = event()
                    action.update(event)
                    assertEquals(active && licensed, event.presentation.isEnabledAndVisible)
                }
            }
        }
    }

    @Test fun `one invocation sends the existing HSL step to the captured target`() {
        QuickSwitcherFixture().use { fixture ->
            LighterAccentAction().actionPerformed(event())
            assertEquals(
                listOf<Pair<dev.ayuislands.studio.model.TargetObservation, StudioQuickCommand>>(
                    fixture.target to
                        StudioQuickCommand.Accent(
                            AccentPanelEvent.Color(
                                AyuVariant.MIRAGE,
                                AccentHsl.lighten(AccentHex.unsafeOf(fixture.hex)).value,
                            ),
                        ),
                ),
                fixture.requests,
            )
        }
    }

    @Test fun `external and clamped colors keep the same command route`() {
        QuickSwitcherFixture().use { fixture ->
            fixture.context = AccentContext.External
            for (hex in listOf("#000000", "#FFFFFF")) {
                fixture.hex = hex
                LighterAccentAction().actionPerformed(event())
                assertEquals(
                    StudioQuickCommand.ExternalAccent(AccentHsl.lighten(AccentHex.unsafeOf(hex)).value),
                    fixture.requests.last().second,
                )
            }
        }
    }

    @Test fun `a captured popup target does not follow a later window focus`() {
        QuickSwitcherFixture().use { fixture ->
            val commands = QuickSwitcherCommands.capture()!!
            every { fixture.service.capture(any()) } returns fixture.target.copy(generation = 99)
            LighterAccentAction(commands).actionPerformed(event())
            assertEquals(fixture.target, fixture.requests.single().first)
        }
    }

    @Test fun `license loss or unavailable authority cannot submit a color`() {
        QuickSwitcherFixture().use { fixture ->
            fixture.licensed = false
            LighterAccentAction().actionPerformed(event())
            fixture.licensed = true
            fixture.available = false
            LighterAccentAction().actionPerformed(event())
            assertTrue(fixture.requests.isEmpty())
        }
    }

    private fun event(): AnActionEvent =
        mockk<AnActionEvent>(relaxed = true).also {
            every { it.presentation } returns Presentation()
        }
}
