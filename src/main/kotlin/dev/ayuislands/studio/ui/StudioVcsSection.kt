package dev.ayuislands.studio.ui

import dev.ayuislands.settings.VcsControls
import dev.ayuislands.settings.VcsPanelEvent
import dev.ayuislands.settings.VcsPanelView
import dev.ayuislands.settings.VcsPreviewInput
import dev.ayuislands.settings.VcsSection
import dev.ayuislands.studio.model.DraftRecord
import dev.ayuislands.studio.model.PreviewMode
import dev.ayuislands.studio.model.SessionIntent
import dev.ayuislands.studio.model.StudioSettingsSection
import dev.ayuislands.studio.session.StudioVcsEditing
import dev.ayuislands.studio.session.StudioVcsProjection

/** Binds native VCS controls to draft edits while Current/Draft samples remain explicit inputs. */
internal class StudioVcsSection(
    initialPreview: VcsPreviewInput,
    expanded: Map<VcsSection, Boolean>,
    private val stage: (List<SessionIntent>) -> Unit,
    private val action: (VcsPanelEvent) -> Unit,
) {
    private val controls = VcsControls(initialPreview, expanded, ::handle)
    private var presentation: VcsPresentation? = null
    private var mode = PreviewMode.CURRENT
    val content = StudioSectionContent(StudioSettingsSection.VCS, controls, ::preview)

    fun render(
        draft: DraftRecord,
        view: VcsPanelView,
        currentPreview: VcsPreviewInput,
        draftPreview: VcsPreviewInput,
    ) {
        require(view.settings == StudioVcsProjection.settings(draft.appearance)) { "VCS view belongs to another draft" }
        presentation = VcsPresentation(draft, view, currentPreview, draftPreview)
        preview(mode)
    }

    private fun preview(selected: PreviewMode) {
        mode = selected
        val latest = presentation ?: return
        controls.render(latest.view, if (selected == PreviewMode.CURRENT) latest.current else latest.preview)
    }

    private fun handle(event: VcsPanelEvent) {
        val latest = presentation ?: return
        val edits = StudioVcsEditing.edits(latest.draft, event)
        if (edits.isNotEmpty()) stage(edits) else action(event)
    }
}

private data class VcsPresentation(
    val draft: DraftRecord,
    val view: VcsPanelView,
    val current: VcsPreviewInput,
    val preview: VcsPreviewInput,
)
