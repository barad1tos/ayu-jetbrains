package dev.ayuislands.studio.ui

import com.intellij.ide.BrowserUtil
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.ColorPicker
import dev.ayuislands.accent.AccentHex
import dev.ayuislands.accent.ProjectLanguageDetector
import dev.ayuislands.font.FontCatalog
import dev.ayuislands.font.FontPanelEvent
import dev.ayuislands.font.FontPreset
import dev.ayuislands.font.FontTerminalAction
import dev.ayuislands.licensing.LicenseChecker
import dev.ayuislands.rotation.ContrastAwareColorGenerator
import dev.ayuislands.settings.AccentPanelAction
import dev.ayuislands.settings.AccentPanelEvent
import dev.ayuislands.settings.AccentPanelGroup
import dev.ayuislands.settings.GlowPanelEvent
import dev.ayuislands.settings.PluginsPanelEvent
import dev.ayuislands.settings.SettingsOpenContext
import dev.ayuislands.settings.SyntaxPanelEvent
import dev.ayuislands.settings.VcsPanelEvent
import dev.ayuislands.settings.WorkspacePanelEvent
import dev.ayuislands.settings.mappings.MappingPrompts
import dev.ayuislands.settings.mappings.MappingsEdit
import dev.ayuislands.settings.mappings.MappingsPanelAction
import dev.ayuislands.settings.mappings.MappingsTable
import dev.ayuislands.settings.mappings.ProjectMapping
import dev.ayuislands.studio.model.AppearanceSnapshot
import dev.ayuislands.studio.model.RawValue
import dev.ayuislands.studio.model.SharedPreferences
import dev.ayuislands.studio.session.StudioAccentEditing
import dev.ayuislands.studio.session.StudioMappingsEditing
import dev.ayuislands.studio.session.StudioPluginsEditing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.awt.KeyboardFocusManager
import java.awt.datatransfer.StringSelection
import kotlin.coroutines.CoroutineContext

/** Native prompts return completed edits; only the host stages appearance and shared preferences. */
internal class StudioNativeActions(
    private val opened: SettingsOpenContext,
    private val opening: StudioSettingsBinding,
    private val context: () -> StudioSettingsContext,
    private val appearance: () -> AppearanceSnapshot,
    private val shared: () -> SharedPreferences,
    private val ioContext: CoroutineContext = Dispatchers.IO,
) {
    private val metadata = StudioUiMetadata(opening)
    private val prompts = MappingPrompts()

    fun accent(action: AccentPanelAction): AccentPanelEvent? =
        when (action) {
            is AccentPanelAction.Custom -> {
                val current = StudioAccentEditing.settings(appearance(), shared()).colors[action.variant]
                chooseColor(current)?.let { AccentPanelEvent.Color(action.variant, it) }
            }
            is AccentPanelAction.Shuffle -> {
                val color = ContrastAwareColorGenerator.generate(action.variant)
                metadata.edit("lastShuffleColor", RawValue.Text(color))
                AccentPanelEvent.Color(action.variant, color)
            }
            is AccentPanelAction.Reset -> {
                if (Messages.showYesNoDialog("Reset the accent color for this variant?", "Reset Accent", null) ==
                    Messages.YES
                ) {
                    AccentPanelEvent.ResetColor(action.variant)
                } else {
                    null
                }
            }
            is AccentPanelAction.Expanded -> {
                metadata.expand(action.group, action.value)
                null
            }
            AccentPanelAction.Unlock -> {
                LicenseChecker.requestLicense("Unlock accent customization")
                null
            }
            is AccentPanelAction.Mapping -> null
        }

    fun mapping(action: MappingsPanelAction): MappingsEdit? {
        val settings = StudioMappingsEditing.settings(shared().mappings)
        return when (action) {
            is MappingsPanelAction.Add ->
                when (action.table) {
                    MappingsTable.PROJECTS ->
                        prompts
                            .projectMapping(
                                opened.project,
                                settings.projects.map { it.key }.toSet(),
                            )?.let(MappingsEdit::AddProject)
                    MappingsTable.LANGUAGES ->
                        prompts
                            .languageMapping(
                                opened.project,
                                settings.languages.map { it.key }.toSet(),
                            )?.let(MappingsEdit::AddLanguage)
                }
            is MappingsPanelAction.EditColor -> {
                val rows = if (action.table == MappingsTable.PROJECTS) settings.projects else settings.languages
                val row = rows.singleOrNull { it.key == action.key }
                row?.let {
                    prompts.accentHex(opened.project, it.hex, it.displayName ?: it.key)?.let { color ->
                        MappingsEdit.Color(action.table, action.key, color)
                    }
                }
            }
            MappingsPanelAction.PinCurrent -> pinCurrent()
            MappingsPanelAction.Rescan -> {
                rescanProject()
                null
            }
            MappingsPanelAction.Unlock -> {
                LicenseChecker.requestLicense("Unlock accent mappings")
                null
            }
            is MappingsPanelAction.Expanded -> {
                metadata.expand(AccentPanelGroup.OVERRIDES, action.value)
                if (action.value) metadata.acknowledge("accent-from-project-icon")
                null
            }
        }
    }

    private fun rescanProject() {
        opened.project?.takeUnless { it.isDisposed || it.isDefault }?.let(ProjectLanguageDetector::rescan)
    }

    private fun pinCurrent(): MappingsEdit.AddProject? {
        val observed = context().mappings
        val key = observed.projectKey
        val color = observed.currentAccentHex
        return if (observed.canPinCurrent && key != null && color != null) {
            MappingsEdit.AddProject(ProjectMapping(key, opened.project?.name ?: key, color))
        } else {
            null
        }
    }

    fun font(action: FontTerminalAction) {
        when (action) {
            is FontPanelEvent.CopyCommand -> copyCommand(action.preset)
            is FontPanelEvent.OpenTerminal -> {
                if (!copyCommand(action.preset)) return
                if (action.terminal == "SYSTEM") {
                    opening.scope.launch(ioContext) { ProcessBuilder("open", "-a", "Terminal").start() }
                } else {
                    opened.project?.takeUnless { it.isDisposed }?.let { project ->
                        ToolWindowManager.getInstance(project).getToolWindow("Terminal")?.activate(null)
                    }
                }
            }
        }
    }

    fun glow(action: GlowPanelEvent) {
        when (action) {
            is GlowPanelEvent.BadgeSeen -> metadata.acknowledge(action.id)
            GlowPanelEvent.Unlock -> LicenseChecker.requestLicense("Unlock glow effects")
            else -> Unit
        }
    }

    fun syntax(action: SyntaxPanelEvent) {
        when (action) {
            SyntaxPanelEvent.OpenHelp ->
                BrowserUtil.browse(
                    "https://www.jetbrains.com/help/idea/configuring-colors-and-fonts.html",
                )
            SyntaxPanelEvent.Unlock -> LicenseChecker.requestLicense("Unlock per-language syntax customization")
            else -> Unit
        }
    }

    fun vcs(action: VcsPanelEvent) {
        when (action) {
            is VcsPanelEvent.Expanded -> metadata.expand(action.section, action.value)
            VcsPanelEvent.Unlock -> LicenseChecker.requestLicense("Unlock VCS color intensity customization")
            else -> Unit
        }
    }

    fun workspace(action: WorkspacePanelEvent) {
        if (action == WorkspacePanelEvent.Unlock) LicenseChecker.requestLicense("Unlock workspace customization")
    }

    fun plugins(action: PluginsPanelEvent): PluginsPanelEvent? =
        when (action) {
            PluginsPanelEvent.CustomAccent -> {
                val current = StudioPluginsEditing.settings(shared().plugins).external.accent
                chooseColor(current)?.let(PluginsPanelEvent::Accent)
            }
            is PluginsPanelEvent.PluginPage -> {
                BrowserUtil.browse(action.target.url)
                null
            }
            PluginsPanelEvent.Unlock -> {
                LicenseChecker.requestLicense("Unlock plugin integrations")
                null
            }
            else -> null
        }

    private fun copyCommand(preset: FontPreset): Boolean {
        val entry = FontCatalog.forPreset(preset) ?: return false
        CopyPasteManager.getInstance().setContents(StringSelection("brew install --cask ${entry.brewCaskSlug}"))
        return true
    }

    private fun chooseColor(current: String?): String? {
        val parent = KeyboardFocusManager.getCurrentKeyboardFocusManager().activeWindow
        val chosen =
            ColorPicker.showDialog(
                parent,
                "Choose Accent Color",
                AccentHex.of(current)?.toColor(),
                true,
                emptyList(),
                false,
            )
                ?: return null
        return "#%02X%02X%02X".format(chosen.red, chosen.green, chosen.blue)
    }
}
