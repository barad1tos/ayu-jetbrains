package dev.ayuislands.studio.ui

import com.intellij.openapi.Disposable
import dev.ayuislands.settings.AccentControls
import dev.ayuislands.settings.AccentPanelAction
import dev.ayuislands.settings.AccentPanelEvent
import dev.ayuislands.settings.AccentPanelGroup
import dev.ayuislands.settings.AccentPanelView
import dev.ayuislands.settings.AccentPreviewInput
import dev.ayuislands.settings.SettingsBadges
import dev.ayuislands.settings.mappings.MappingsEdit
import dev.ayuislands.studio.model.DraftRecord
import dev.ayuislands.studio.model.PreviewMode
import dev.ayuislands.studio.model.SessionIntent
import dev.ayuislands.studio.model.SharedPreferences
import dev.ayuislands.studio.model.StudioSettingsSection
import dev.ayuislands.studio.session.StudioAccentEditing

/** One native Accent page; accepted and draft samples never change the live IDE or settings store. */
internal class StudioAccentSection(
    initialExpanded: Set<AccentPanelGroup>,
    private val stage: (List<SessionIntent>) -> Unit,
    action: (AccentPanelAction) -> Unit,
) : Disposable {
    private val mappings =
        StudioMappingsSection(AccentPanelGroup.OVERRIDES in initialExpanded, stage) {
            action(AccentPanelAction.Mapping(it))
        }
    private val controls = AccentControls(initialExpanded, mappings.component, ::handle, action)
    private val badge =
        SettingsBadges.registerGroupExpanded("accent-from-project-icon") { mappings.component.isExpanded }
    private var presentation: AccentPresentation? = null
    private var mode = PreviewMode.CURRENT
    val content = StudioSectionContent(StudioSettingsSection.ACCENT, controls, ::preview)

    fun render(
        draft: DraftRecord,
        shared: SharedPreferences,
        view: AccentPanelView,
        currentPreview: AccentPreviewInput,
        draftPreview: AccentPreviewInput,
    ) {
        require(view.settings == StudioAccentEditing.settings(draft.appearance, shared)) {
            "Accent view belongs to another draft"
        }
        presentation = AccentPresentation(draft, shared, view, currentPreview, draftPreview)
        mappings.render(
            shared.mappings,
            view.mappings.copy(
                isEditable = view.isEditable && view.mappings.isEditable,
                isLicensed = view.isLicensed && view.mappings.isLicensed,
            ),
        )
        preview(mode)
    }

    fun accept(event: AccentPanelEvent) = controls.edit(event)

    fun acceptMapping(edit: MappingsEdit) = mappings.accept(edit)

    override fun dispose() = badge.dispose()

    private fun preview(selected: PreviewMode) {
        mode = selected
        val latest = presentation ?: return
        controls.render(latest.view, if (selected == PreviewMode.CURRENT) latest.current else latest.preview)
    }

    private fun handle(event: AccentPanelEvent) {
        val latest = presentation ?: return
        val edits = StudioAccentEditing.edits(latest.draft, latest.shared, event)
        if (edits.isNotEmpty()) stage(edits)
    }
}

private data class AccentPresentation(
    val draft: DraftRecord,
    val shared: SharedPreferences,
    val view: AccentPanelView,
    val current: AccentPreviewInput,
    val preview: AccentPreviewInput,
)
