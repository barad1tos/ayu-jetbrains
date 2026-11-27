package dev.ayuislands.studio.ui

import dev.ayuislands.font.FontPreset
import dev.ayuislands.font.FontSettings
import dev.ayuislands.font.FontStatus
import dev.ayuislands.studio.model.AppearanceSnapshot
import dev.ayuislands.studio.model.DraftRecord
import dev.ayuislands.studio.model.PreviewMode
import dev.ayuislands.studio.model.SharedIntent
import dev.ayuislands.studio.model.StudioFields
import dev.ayuislands.studio.model.StudioSettingsSection
import dev.ayuislands.studio.session.StudioFontEditing
import dev.ayuislands.studio.session.StudioFontProjection
import dev.ayuislands.studio.session.StudioFontSettings
import dev.ayuislands.studio.session.StudioFontTerminal
import dev.ayuislands.studio.session.StudioFontTerminalPolicy
import java.awt.Color

internal data class StudioCuratedFont(
    val status: FontStatus,
    val family: String?,
)

internal data class StudioFontAvailability(
    val curated: Map<FontPreset, StudioCuratedFont>,
    val families: List<String>,
)

internal fun StudioFontAvailability.familyFor(settings: FontSettings): String =
    if (settings.preset.isCurated) {
        curated[settings.preset]?.family ?: settings.preset.fontFamily
    } else {
        settings.fontFamily
    }

internal data class StudioFontAppearance(
    val appearance: AppearanceSnapshot,
    val accent: Color,
)

internal data class StudioFontView(
    val current: StudioFontAppearance,
    val draft: StudioFontAppearance,
    val availability: StudioFontAvailability,
    val editable: Boolean,
    val system: StudioFields,
    val manualInstall: Boolean,
)

internal enum class StudioFontOperation { INSTALL, REINSTALL, DELETE }

internal sealed interface StudioFontAction {
    sealed interface Resource : StudioFontAction

    data class ManageFont(
        val preset: FontPreset,
        val operation: StudioFontOperation,
    ) : Resource

    data class CopyInstallCommand(
        val preset: FontPreset,
    ) : Resource

    data class OpenInstallTerminal(
        val preset: FontPreset,
        val terminal: StudioFontTerminal,
    ) : Resource

    data class SelectTerminal(
        val terminal: StudioFontTerminal,
    ) : StudioFontAction

    data class DiscloseCustomize(
        val expanded: Boolean,
    ) : StudioFontAction
}

internal data class StudioFontSample(
    val settings: FontSettings,
    val family: String,
    val installed: Boolean,
    val accent: Color,
)

/** The section receives appearance and availability from the host, and stages only explicit edits. */
internal class StudioFontSection(
    customizeExpanded: Boolean,
    stage: ((DraftRecord) -> DraftRecord) -> Unit,
    stageSystem: ((StudioFields) -> List<SharedIntent.Field>) -> Unit,
    onAction: (StudioFontAction) -> Unit,
) {
    private val handleAction: (StudioFontAction) -> Unit = { action ->
        if (action is StudioFontAction.SelectTerminal) {
            stageSystem { system -> StudioFontTerminalPolicy.edit(system, action.terminal) }
        } else {
            onAction(action)
        }
    }
    private val controls =
        StudioFontControls(customizeExpanded, handleAction) { edit ->
            stage { draft -> StudioFontEditing.edit(draft, edit) }
        }
    private var mode = PreviewMode.CURRENT
    private var currentSample: StudioFontSample? = null
    private var draftSample: StudioFontSample? = null
    val content = StudioSectionContent(StudioSettingsSection.FONT, controls, ::preview)

    fun render(view: StudioFontView) {
        val current = StudioFontProjection.settings(view.current.appearance)
        val draft = StudioFontProjection.settings(view.draft.appearance)
        currentSample = sample(current, view.availability, view.current.accent)
        draftSample = sample(draft, view.availability, view.draft.accent)
        controls.render(
            draft,
            view.availability,
            view.editable,
            view.manualInstall,
            StudioFontTerminalPolicy.selected(view.system),
        )
        preview(mode)
    }

    private fun preview(selected: PreviewMode) {
        mode = selected
        controls.showPreview(if (selected == PreviewMode.CURRENT) currentSample else draftSample)
    }

    private fun sample(
        font: StudioFontSettings,
        availability: StudioFontAvailability,
        accent: Color,
    ): StudioFontSample? {
        if (!font.enabled) return null
        val settings = font.resolved ?: return null
        val curated = availability.curated[settings.preset]
        val family = availability.familyFor(settings)
        val installed =
            if (settings.preset.isCurated) {
                curated?.status == FontStatus.HEALTHY
            } else {
                availability.families.any { it.equals(family, ignoreCase = true) }
            }
        return StudioFontSample(settings, family, installed, accent)
    }
}
