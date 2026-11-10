package dev.ayuislands.accent.toolbar

import com.intellij.util.ui.JBUI
import dev.ayuislands.accent.AccentContext
import dev.ayuislands.accent.toolbar.popup.Density
import dev.ayuislands.accent.toolbar.popup.ToggleTile
import dev.ayuislands.settings.AccentPanelEvent
import dev.ayuislands.studio.model.AcceptanceResult
import dev.ayuislands.studio.session.ImmediateExecutionResult
import dev.ayuislands.studio.session.StudioQuickCommand
import java.awt.Container
import java.awt.GridLayout
import java.awt.image.BufferedImage
import javax.swing.JLabel
import javax.swing.JPanel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class QuickSwitcherRelatedTogglesSectionTest {
    @Test fun `native tile order geometry and free section remain unchanged`() {
        QuickSwitcherFixture().use {
            val component = QuickSwitcherRelatedTogglesSection().component as JPanel
            val layout = component.layout as GridLayout
            assertEquals(2, layout.rows)
            assertEquals(2, layout.columns)
            assertEquals(JBUI.scale(Density.TILE_GAP), layout.hgap)
            assertEquals(layout.hgap, layout.vgap)
            assertEquals(
                listOf("Chrome tinting", "Glow", "Accent rotation", "Follow system accent"),
                tiles(component).map(::label),
            )
            assertEquals(
                listOf("Follow system accent"),
                tiles(QuickSwitcherRelatedTogglesSection(showPremiumToggles = false).component as JPanel).map(::label),
            )
        }
    }

    @Test fun `each toggle dispatches one exact command without rewriting granular choices`() {
        QuickSwitcherFixture().use { fixture ->
            fixture.state.chromeTintingEnabled = true
            fixture.state.chromeStatusBar = true
            fixture.state.chromeMainToolbar = false
            fixture.state.glowEnabled = true
            fixture.state.accentRotationEnabled = true
            fixture.state.followSystemAccent = false
            val controls = tiles(QuickSwitcherRelatedTogglesSection().component as JPanel)
            controls.forEach { it.toggleSwitch.flip() }
            assertEquals(
                listOf(
                    StudioQuickCommand.Accent(AccentPanelEvent.ChromeEnabled(false)),
                    StudioQuickCommand.GlowEnabled(false),
                    StudioQuickCommand.Accent(AccentPanelEvent.RotationEnabled(false)),
                    StudioQuickCommand.Accent(AccentPanelEvent.FollowSystem(true)),
                ),
                fixture.requests.map { it.second },
            )
            assertTrue(fixture.state.chromeTintingEnabled)
            assertTrue(fixture.state.chromeStatusBar)
            assertFalse(fixture.state.chromeMainToolbar)
            assertTrue(fixture.state.accentRotationEnabled)
            assertFalse(fixture.state.followSystemAccent)
        }
    }

    @Test fun `async completion mirrors the accepted gate and rejected requests keep the old indicator`() {
        QuickSwitcherFixture().use { fixture ->
            fixture.state.glowEnabled = true
            val control = tiles(QuickSwitcherRelatedTogglesSection().component as JPanel)[1].toggleSwitch
            control.flip()
            assertTrue(control.isSelected)
            fixture.state.glowEnabled = false
            fixture.completions.single()(ImmediateExecutionResult.Complete(AcceptanceResult.NoChanges))
            assertFalse(control.isSelected)
        }
    }

    @Test fun `queued premium clicks after license loss are inert but Follow remains free`() {
        QuickSwitcherFixture().use { fixture ->
            val controls = tiles(QuickSwitcherRelatedTogglesSection().component as JPanel)
            fixture.licensed = false
            controls.forEach { it.toggleSwitch.flip() }
            assertEquals(
                listOf(
                    StudioQuickCommand.Accent(AccentPanelEvent.FollowSystem(!fixture.state.followSystemAccent)),
                ),
                fixture.requests.map {
                    it.second
                },
            )
        }
    }

    @Test fun `external accent remains paintable and disappearance is sent to app admission`() {
        QuickSwitcherFixture().use { fixture ->
            fixture.context = AccentContext.External
            fixture.state.glowEnabled = true
            val control = tiles(QuickSwitcherRelatedTogglesSection().component as JPanel)[1].toggleSwitch
            control.size = control.preferredSize
            val graphics = BufferedImage(control.width, control.height, BufferedImage.TYPE_INT_ARGB).createGraphics()
            try {
                control.paintForTest(graphics)
            } finally {
                graphics.dispose()
            }
            fixture.available = false
            control.flip()
            // The captured target is still submitted; app admission owns disappearance and must reject it.
            assertEquals(1, fixture.requests.size)
            assertFalse(control.isSelected)
        }
    }

    private fun tiles(panel: JPanel): List<ToggleTile> = panel.components.filterIsInstance<ToggleTile>()

    private fun label(component: Container): String = requireNotNull(findLabel(component))

    private fun findLabel(component: Container): String? =
        component.components.firstNotNullOfOrNull { child ->
            when (child) {
                is JLabel -> child.text?.takeIf { it.isNotEmpty() }
                is Container -> findLabel(child)
                else -> null
            }
        }
}
