package dev.ayuislands.studio.ui

import com.intellij.openapi.Disposable
import com.intellij.openapi.util.Disposer
import dev.ayuislands.settings.SyntaxControls
import dev.ayuislands.settings.SyntaxPanelEvent
import dev.ayuislands.settings.SyntaxPanelView
import dev.ayuislands.settings.SyntaxPreviewInput
import dev.ayuislands.studio.model.DraftRecord
import dev.ayuislands.studio.model.PreviewMode
import dev.ayuislands.studio.model.SessionIntent
import dev.ayuislands.studio.model.StudioSettingsSection
import dev.ayuislands.studio.session.StudioSyntaxEditing
import dev.ayuislands.studio.session.StudioSyntaxProjection

/** Shared Syntax controls with explicit draft edits and isolated Current/Draft native samples. */
internal class StudioSyntaxSection(
    initialPreview: SyntaxPreviewInput,
    private val stage: (List<SessionIntent>) -> Unit,
    private val action: (SyntaxPanelEvent) -> Unit,
) : Disposable {
    private val controls = SyntaxControls(initialPreview, ::handle)
    private var presentation: SyntaxPresentation? = null
    private var renderedView: SyntaxPanelView? = null
    private var renderedSample: SyntaxPreviewInput? = null
    private var mode = PreviewMode.CURRENT
    val content = StudioSectionContent(StudioSettingsSection.SYNTAX, controls, ::preview)

    init {
        Disposer.register(this, controls)
    }

    fun render(
        draft: DraftRecord,
        view: SyntaxPanelView,
        currentPreview: SyntaxPreviewInput,
        draftPreview: SyntaxPreviewInput,
    ) {
        require(view.settings == StudioSyntaxProjection.settings(draft.appearance)) {
            "Syntax view belongs to another draft"
        }
        presentation = SyntaxPresentation(draft, view, currentPreview, draftPreview)
        preview(mode)
    }

    override fun dispose() = Unit

    private fun preview(selected: PreviewMode) {
        mode = selected
        val latest = presentation ?: return
        val sample = if (selected == PreviewMode.CURRENT) latest.current else latest.preview
        if (renderedView == latest.view && renderedSample === sample) return
        controls.render(latest.view, sample)
        renderedView = latest.view
        renderedSample = sample
    }

    private fun handle(event: SyntaxPanelEvent) {
        val latest = presentation ?: return
        val edits = StudioSyntaxEditing.edits(latest.draft, event)
        if (edits.isNotEmpty()) {
            stage(edits)
        } else {
            action(event)
        }
    }
}

private data class SyntaxPresentation(
    val draft: DraftRecord,
    val view: SyntaxPanelView,
    val current: SyntaxPreviewInput,
    val preview: SyntaxPreviewInput,
)
