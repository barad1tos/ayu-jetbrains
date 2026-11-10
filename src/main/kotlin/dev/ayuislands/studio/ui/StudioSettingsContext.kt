package dev.ayuislands.studio.ui

import com.intellij.openapi.editor.colors.EditorColorsScheme
import dev.ayuislands.font.FontPanelResources
import dev.ayuislands.font.FontTerminalAction
import dev.ayuislands.licensing.LicenseEntitlement
import dev.ayuislands.settings.AccentPanelAction
import dev.ayuislands.settings.AccentPanelContext
import dev.ayuislands.settings.AccentPanelEvent
import dev.ayuislands.settings.GlowPanelEvent
import dev.ayuislands.settings.IgnorePreviewInput
import dev.ayuislands.settings.PluginTarget
import dev.ayuislands.settings.PluginsPanelEvent
import dev.ayuislands.settings.SyntaxCapabilityState
import dev.ayuislands.settings.SyntaxPanelEvent
import dev.ayuislands.settings.VcsPanelEvent
import dev.ayuislands.settings.WorkspacePanelEvent
import dev.ayuislands.settings.mappings.MappingsContext
import dev.ayuislands.settings.mappings.MappingsEdit
import dev.ayuislands.settings.mappings.MappingsPanelAction
import dev.ayuislands.syntax.SyntaxIntensityApplicator

/** One host observation supplies all section views; no control performs ambient service reads. */
internal data class StudioSettingsContext(
    val entitlement: LicenseEntitlement,
    val accent: AccentPanelContext,
    val font: FontPanelResources,
    val editor: StudioEditorContext,
    val mappings: MappingsContext,
    val plugins: Set<PluginTarget>,
    val syntax: SyntaxCapabilityState,
)

/** Explicit native actions. Completed preference edits return to the existing draft staging path. */
internal interface StudioSectionActions {
    fun accent(action: AccentPanelAction): AccentPanelEvent?

    fun mapping(action: MappingsPanelAction): MappingsEdit?

    fun font(action: FontTerminalAction)

    fun glow(action: GlowPanelEvent)

    fun syntax(action: SyntaxPanelEvent)

    fun vcs(action: VcsPanelEvent)

    fun workspace(action: WorkspacePanelEvent)

    fun plugins(action: PluginsPanelEvent): PluginsPanelEvent?
}

/** Catalog prompts and review confirmation are owned by the native host lifetime. */
internal interface StudioHostDialogs {
    fun profileName(currentName: String?): String?

    fun review(dialog: StudioReviewDialog): Boolean
}

/** Native source observations shared by isolated editor and integration samples. */
internal data class StudioEditorContext(
    val scheme: EditorColorsScheme,
    val syntaxBase: SyntaxIntensityApplicator.Request,
    val ignore: IgnorePreviewInput?,
)
