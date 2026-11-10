package dev.ayuislands.studio.ui

import dev.ayuislands.settings.GlowControls
import dev.ayuislands.settings.GlowPanelEvent
import dev.ayuislands.settings.GlowPanelView
import dev.ayuislands.settings.GlowPreview
import dev.ayuislands.studio.model.DraftRecord
import dev.ayuislands.studio.model.PreviewMode
import dev.ayuislands.studio.model.SessionIntent
import dev.ayuislands.studio.model.StudioSettingsSection
import dev.ayuislands.studio.session.StudioGlowEditing
import dev.ayuislands.studio.session.StudioGlowProjection

/** Binds shared Glow controls to draft edits and an isolated native contour. */
internal class StudioGlowSection(
    private val stage: (List<SessionIntent>) -> Unit,
    private val action: (GlowPanelEvent) -> Unit,
) {
    private val controls = GlowControls(::handle)
    private var presentation: GlowPresentation? = null
    private var mode = PreviewMode.CURRENT
    val content = StudioSectionContent(StudioSettingsSection.GLOW, controls, ::preview)

    fun render(
        draft: DraftRecord,
        view: GlowPanelView,
        currentPreview: GlowPreview,
    ) {
        require(
            view.settings == StudioGlowProjection.settings(draft.appearance),
        ) { "Glow view belongs to another draft" }
        presentation = GlowPresentation(draft, view, currentPreview)
        preview(mode)
    }

    private fun preview(selected: PreviewMode) {
        mode = selected
        val latest = presentation ?: return
        controls.render(
            latest.view.copy(
                preview =
                    if (selected ==
                        PreviewMode.CURRENT
                    ) {
                        latest.current
                    } else {
                        latest.view.preview
                    },
            ),
        )
    }

    private fun handle(event: GlowPanelEvent) {
        val latest = presentation ?: return
        when (event) {
            is GlowPanelEvent.BadgeSeen, GlowPanelEvent.Unlock -> {
                action(event)
            }

            else -> {
                val edits = StudioGlowEditing.edits(latest.draft, event)
                if (edits.isNotEmpty()) stage(edits)
            }
        }
    }
}

private data class GlowPresentation(
    val draft: DraftRecord,
    val view: GlowPanelView,
    val current: GlowPreview,
)
