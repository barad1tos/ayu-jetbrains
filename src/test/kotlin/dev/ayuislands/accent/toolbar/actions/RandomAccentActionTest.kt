package dev.ayuislands.accent.toolbar.actions

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.Presentation
import dev.ayuislands.accent.AccentContext
import dev.ayuislands.accent.AyuVariant
import dev.ayuislands.accent.toolbar.QuickSwitcherFixture
import dev.ayuislands.rotation.ContrastAwareColorGenerator
import dev.ayuislands.settings.AccentPanelEvent
import dev.ayuislands.studio.session.StudioQuickCommand
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.verify
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RandomAccentActionTest {
    @Test fun `generator output is submitted once for the captured variant and external context`() {
        QuickSwitcherFixture().use { fixture ->
            mockkObject(ContrastAwareColorGenerator)
            every { ContrastAwareColorGenerator.generate(any()) } returns "#123456"
            RandomAccentAction().actionPerformed(event())
            assertEquals(
                StudioQuickCommand.Accent(AccentPanelEvent.Color(AyuVariant.MIRAGE, "#123456")),
                fixture.requests.single().second,
            )
            fixture.context = AccentContext.External
            RandomAccentAction().actionPerformed(event())
            assertEquals(StudioQuickCommand.ExternalAccent("#123456"), fixture.requests.last().second)
            verify(exactly = 2) { ContrastAwareColorGenerator.generate(AyuVariant.MIRAGE) }
        }
    }

    @Test fun `BGT availability and invocation both retain the premium context gate`() {
        QuickSwitcherFixture().use { fixture ->
            val action = RandomAccentAction()
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
            fixture.licensed = false
            action.actionPerformed(event())
            assertTrue(fixture.requests.isEmpty())
        }
    }

    private fun event(): AnActionEvent =
        mockk<AnActionEvent>(relaxed = true).also {
            every { it.presentation } returns Presentation()
        }
}
