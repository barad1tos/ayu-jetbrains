package dev.ayuislands.settings

import com.intellij.openapi.project.Project
import dev.ayuislands.accent.AyuVariant
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import io.mockk.verify
import io.mockk.verifyOrder
import kotlin.test.Test
import kotlin.test.assertEquals

class AyuSettingsCompositionTest {
    @Test
    fun `Ayu theme builds every tab and participant in lifecycle order`() {
        val session = SettingsSession()
        val panels = settingsPanels()
        val contextProject = mockk<Project>()
        val openContext = SettingsOpenContext(contextProject, activeFileType = mockk())
        val composition =
            AyuSettingsComposition(
                AyuVariant.MIRAGE,
                session,
                panels,
                openContext,
            )

        val tabs = composition.buildContentTabs()

        assertEquals(
            listOf("Accent", "Font", "Glow", "Syntax", "VCS", "Workspace", "Plugins"),
            tabs.map { it.first },
        )
        assertEquals(
            listOf("System", "Accent", "Chrome", "Elements", "Font", "Glow", "Syntax", "VCS", "Workspace", "Plugins"),
            session.participantNames,
        )
        verify(exactly = 1) {
            panels.font.buildPanel(any())
            panels.effects.buildPanel(any())
            panels.syntax.buildPanel(
                any(),
                AyuVariant.MIRAGE,
                openContext.project,
                openContext.activeFileType,
            )
            panels.vcs.buildPanel(any(), AyuVariant.MIRAGE)
            panels.workspace.buildPanel(any())
            panels.plugins.buildPanel(any())
        }
    }

    @Test
    fun `external theme keeps tabs but excludes Ayu-only participants`() {
        val session = SettingsSession()
        val panels = settingsPanels()
        val composition = AyuSettingsComposition(null, session, panels)

        val tabs = composition.buildContentTabs()

        assertEquals(
            listOf("Accent", "Font", "Glow", "Syntax", "VCS", "Workspace", "Plugins"),
            tabs.map { it.first },
        )
        assertEquals(
            listOf("Font", "Glow", "Workspace", "Plugins"),
            session.participantNames,
        )
        verify(exactly = 1) {
            panels.font.buildPanel(any())
            panels.effects.buildPanel(any())
            panels.workspace.buildPanel(any())
            panels.plugins.buildPanel(any())
        }
        verify(exactly = 0) {
            panels.accentGroup.accent.buildPanel(any(), any(), any(), any())
            panels.syntax.buildPanel(any(), any())
            panels.vcs.buildPanel(any(), any())
        }
    }

    @Test
    fun `composition wires accent sections and preview relay`() {
        val accent = spyk(AyuIslandsAccentPanel())
        val panels = settingsPanels(accent)
        val appearance = panels.accentGroup.appearance
        val chrome = panels.accentGroup.chrome
        val elements = panels.accentGroup.elements
        every { appearance.buildPanel(any(), any()) } answers {
            secondArg<com.intellij.ui.dsl.builder.Panel.() -> Unit>().invoke(firstArg())
        }
        every { accent.buildPanel(any(), AyuVariant.MIRAGE, any(), any()) } answers {
            thirdArg<com.intellij.ui.dsl.builder.Panel.() -> Unit>().invoke(firstArg())
            arg<com.intellij.ui.dsl.builder.Panel.() -> Unit>(3).invoke(firstArg())
        }
        val composition = AyuSettingsComposition(AyuVariant.MIRAGE, SettingsSession(), panels)

        composition.buildContentTabs()
        accent.onAccentChanged?.invoke("#5CCFE6")

        verifyOrder {
            accent.buildPanel(any(), AyuVariant.MIRAGE, any(), any())
            appearance.buildPanel(any(), any())
            accent.installSystemAccentCheckbox(any())
            chrome.buildPanel(any(), AyuVariant.MIRAGE)
            elements.buildPanel(any(), AyuVariant.MIRAGE)
        }
        verify(exactly = 1) { elements.updatePreviewAccent("#5CCFE6") }
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

    private fun settingsPanels(accent: AyuIslandsAccentPanel = mockk(relaxed = true)): AyuSettingsPanels =
        AyuSettingsPanels(
            accentGroup =
                AccentSettingsPanels(
                    appearance = mockk(relaxed = true),
                    accent = accent,
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
