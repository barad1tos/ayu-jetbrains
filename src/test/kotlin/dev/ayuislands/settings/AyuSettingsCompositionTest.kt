package dev.ayuislands.settings

import dev.ayuislands.accent.AyuVariant
import io.mockk.mockk
import io.mockk.verify
import kotlin.test.Test
import kotlin.test.assertEquals

class AyuSettingsCompositionTest {
    @Test
    fun `Ayu theme builds every tab and participant in lifecycle order`() {
        val session = SettingsSession()
        val composition = AyuSettingsComposition(AyuVariant.MIRAGE, session, settingsPanels())

        val tabs = composition.buildContentTabs()

        assertEquals(
            listOf("Accent", "Font", "Glow", "Syntax", "VCS", "Workspace", "Plugins"),
            tabs.map { it.first },
        )
        assertEquals(
            listOf("System", "Accent", "Chrome", "Elements", "Font", "Glow", "Syntax", "VCS", "Workspace", "Plugins"),
            session.participantNames,
        )
    }

    @Test
    fun `external theme keeps tabs but excludes Ayu-only participants`() {
        val session = SettingsSession()
        val composition = AyuSettingsComposition(null, session, settingsPanels())

        val tabs = composition.buildContentTabs()

        assertEquals(
            listOf("Accent", "Font", "Glow", "Syntax", "VCS", "Workspace", "Plugins"),
            tabs.map { it.first },
        )
        assertEquals(
            listOf("Font", "Glow", "Workspace", "Plugins"),
            session.participantNames,
        )
    }

    @Test
    fun `reset accent default changes only the Accent participant`() {
        val panels = settingsPanels()
        val composition = AyuSettingsComposition(AyuVariant.MIRAGE, SettingsSession(), panels)

        composition.resetAccentDefault()

        verify(exactly = 1) { panels.accentGroup.accent.resetToDefault() }
        verify(exactly = 0) {
            panels.accentGroup.appearance.reset()
            panels.accentGroup.chrome.reset()
            panels.accentGroup.elements.reset()
            panels.font.reset()
            panels.effects.reset()
            panels.syntax.reset()
            panels.vcs.reset()
            panels.workspace.reset()
            panels.plugins.reset()
        }
    }

    private fun settingsPanels(): AyuSettingsPanels =
        AyuSettingsPanels(
            accentGroup =
                AccentSettingsPanels(
                    appearance = mockk(relaxed = true),
                    accent = mockk(relaxed = true),
                    chrome = mockk(relaxed = true),
                    elements = mockk(relaxed = true),
                ),
            font = mockk(relaxed = true),
            effects = mockk(relaxed = true),
            syntax = mockk(relaxed = true),
            vcs = mockk(relaxed = true),
            workspace = mockk(relaxed = true),
            plugins = mockk(relaxed = true),
        )
}
