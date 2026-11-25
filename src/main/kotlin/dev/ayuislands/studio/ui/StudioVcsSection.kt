package dev.ayuislands.studio.ui

import dev.ayuislands.accent.AyuVariant
import dev.ayuislands.settings.VcsSection
import dev.ayuislands.studio.model.AppearanceSnapshot
import dev.ayuislands.studio.model.DraftRecord
import dev.ayuislands.studio.model.PreviewMode
import dev.ayuislands.studio.model.StudioSettingsSection
import dev.ayuislands.studio.session.StudioVcsEditing
import dev.ayuislands.studio.session.StudioVcsProjection
import dev.ayuislands.vcs.VcsColorSnapshot

internal data class StudioVcsView(
    val variant: AyuVariant,
    val current: AppearanceSnapshot,
    val draft: AppearanceSnapshot,
    val isEditable: Boolean,
    val isLicensed: Boolean,
)

/** The host supplies its latest draft for each edit and owns disclosure memory. */
internal class StudioVcsSection(
    expanded: Map<VcsSection, Boolean>,
    stage: ((DraftRecord) -> DraftRecord) -> Unit,
    disclose: (VcsSection, Boolean) -> Unit,
    unlock: () -> Unit,
) {
    private val controls =
        StudioVcsControls(expanded, disclose, unlock) { edit ->
            stage { working -> StudioVcsEditing.edit(working, edit) }
        }
    private var presentation: VcsPresentation? = null
    private var mode = PreviewMode.CURRENT
    val content = StudioSectionContent(StudioSettingsSection.VCS, controls, ::preview)

    fun render(view: StudioVcsView) {
        presentation =
            VcsPresentation(
                view.variant,
                StudioVcsProjection.snapshot(view.current),
                StudioVcsProjection.snapshot(view.draft),
            )
        controls.render(StudioVcsProjection.settings(view.draft), view.isEditable, view.isLicensed)
        preview(mode)
    }

    private fun preview(selected: PreviewMode) {
        mode = selected
        val rendered = presentation ?: return
        val snapshot = if (selected == PreviewMode.CURRENT) rendered.current else rendered.draft
        controls.showPreview(rendered.variant, snapshot)
    }
}

private data class VcsPresentation(
    val variant: AyuVariant,
    val current: VcsColorSnapshot,
    val draft: VcsColorSnapshot,
)
