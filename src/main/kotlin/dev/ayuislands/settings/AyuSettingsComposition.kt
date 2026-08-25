package dev.ayuislands.settings

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.ui.dsl.builder.Panel
import com.intellij.ui.dsl.builder.panel
import dev.ayuislands.accent.AyuVariant
import javax.swing.JComponent

internal class AccentSettingsPanels(
    val appearance: AyuIslandsAppearancePanel = AyuIslandsAppearancePanel(),
    val accent: AyuIslandsAccentPanel = AyuIslandsAccentPanel(),
    val chrome: AyuIslandsChromePanel = AyuIslandsChromePanel(),
    val elements: AyuIslandsElementsPanel = AyuIslandsElementsPanel(),
)

internal class AyuSettingsPanels(
    val accentGroup: AccentSettingsPanels = AccentSettingsPanels(),
    val font: FontPresetPanel = FontPresetPanel(),
    val effects: AyuIslandsEffectsPanel = AyuIslandsEffectsPanel(),
    val syntax: AyuIslandsSyntaxPanel = AyuIslandsSyntaxPanel(),
    val vcs: VcsColorPanel = VcsColorPanel(),
    val workspace: WorkspacePanel = WorkspacePanel(),
    val plugins: PluginsPanel = PluginsPanel(),
)

internal class AyuSettingsComposition(
    private val variant: AyuVariant?,
    private val session: SettingsSession,
    private val panels: AyuSettingsPanels = AyuSettingsPanels(),
    private val contextProject: Project? = null,
) {
    fun buildContentTabs(): List<Pair<String, JComponent>> {
        lateinit var tabs: List<Pair<String, JComponent>>
        session.build {
            tabs =
                listOf(
                    "Accent" to buildAccentTab(),
                    "Font" to buildFontTab(),
                    "Glow" to buildGlowTab(),
                    "Syntax" to
                        buildAyuOnlyTab("Syntax", "syntax intensity", panels.syntax) { activeVariant ->
                            panels.syntax.buildPanel(this, activeVariant, contextProject)
                        },
                    "VCS" to
                        buildAyuOnlyTab("VCS", "VCS colors", panels.vcs) { activeVariant ->
                            panels.vcs.buildPanel(this, activeVariant)
                        },
                    "Workspace" to buildWorkspaceTab(),
                    "Plugins" to buildPluginsTab(),
                )
        }
        return tabs
    }

    fun resetAccentDefault() {
        panels.accentGroup.accent.resetToDefault()
    }

    private fun SettingsSession.buildAccentTab(): JComponent =
        panel {
            val activeVariant = variant
            if (activeVariant == null) {
                buildAyuThemeRequiredMessage("accent colors")
            } else {
                val accentGroup = panels.accentGroup
                include(
                    namedParticipant("System", accentGroup.appearance),
                    namedParticipant("Accent", accentGroup.accent),
                    namedParticipant("Chrome", accentGroup.chrome),
                    namedParticipant("Elements", accentGroup.elements),
                ) {
                    accentGroup.accent.onAccentChanged = { hex -> accentGroup.elements.updatePreviewAccent(hex) }
                    accentGroup.accent.buildPanel(
                        panel = this@panel,
                        variant = activeVariant,
                        buildSystemSection = {
                            accentGroup.appearance.buildPanel(this) {
                                accentGroup.accent.installSystemAccentCheckbox(this)
                            }
                        },
                        buildChromeSection = {
                            accentGroup.chrome.buildPanel(this, activeVariant)
                        },
                    )
                    accentGroup.elements.buildPanel(this@panel, activeVariant)
                    buildResetAccentRow()
                }
            }
        }

    private fun SettingsSession.buildFontTab(): JComponent =
        panel {
            include(namedParticipant("Font", panels.font)) {
                panels.font.buildPanel(this@panel)
            }
        }

    private fun SettingsSession.buildGlowTab(): JComponent =
        panel {
            include(namedParticipant("Glow", panels.effects)) {
                panels.effects.buildPanel(this@panel)
            }
        }

    private fun SettingsSession.buildAyuOnlyTab(
        participantName: String,
        sectionName: String,
        participant: SettingsParticipant,
        build: Panel.(AyuVariant) -> Unit,
    ): JComponent =
        panel {
            val activeVariant = variant
            if (activeVariant == null) {
                buildAyuThemeRequiredMessage(sectionName)
            } else {
                include(namedParticipant(participantName, participant)) {
                    build(this@panel, activeVariant)
                }
            }
        }

    private fun SettingsSession.buildWorkspaceTab(): JComponent =
        panel {
            include(namedParticipant("Workspace", panels.workspace)) {
                panels.workspace.buildPanel(this@panel)
            }
        }

    private fun SettingsSession.buildPluginsTab(): JComponent =
        panel {
            include(namedParticipant("Plugins", panels.plugins)) {
                panels.plugins.buildPanel(this@panel)
            }
        }

    private fun Panel.buildResetAccentRow() {
        row {
            link("Reset accent to default…") {
                val result =
                    Messages.showYesNoDialog(
                        "Reset the pending accent color to its default?",
                        "Reset Accent",
                        Messages.getWarningIcon(),
                    )
                if (result == Messages.YES) resetAccentDefault()
            }
        }
    }

    private fun Panel.buildAyuThemeRequiredMessage(sectionName: String) {
        row {
            comment("Activate an Ayu Islands theme to configure $sectionName.")
        }
    }
}
