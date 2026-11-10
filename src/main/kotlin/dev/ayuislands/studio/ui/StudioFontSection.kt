package dev.ayuislands.studio.ui

import dev.ayuislands.font.FontPanelEvent
import dev.ayuislands.font.FontPanelPreview
import dev.ayuislands.font.FontPanelView
import dev.ayuislands.font.FontTerminalAction
import dev.ayuislands.settings.FontControls
import dev.ayuislands.studio.fields.StudioFieldEdits
import dev.ayuislands.studio.model.DraftRecord
import dev.ayuislands.studio.model.FieldPart
import dev.ayuislands.studio.model.FieldValue
import dev.ayuislands.studio.model.LegacyFieldKey
import dev.ayuislands.studio.model.LegacyStore
import dev.ayuislands.studio.model.PreviewMode
import dev.ayuislands.studio.model.RawValue
import dev.ayuislands.studio.model.SessionIntent
import dev.ayuislands.studio.model.SharedIntent
import dev.ayuislands.studio.model.StudioField
import dev.ayuislands.studio.model.StudioSettingsSection
import dev.ayuislands.studio.session.StudioFontEditing
import dev.ayuislands.studio.session.StudioFontProjection

/** Binds shared Font controls to explicit session intents and local Current/Draft observations. */
internal class StudioFontSection(
    private val stage: (List<SessionIntent>) -> Unit,
    private val resource: (FontPanelEvent.Resource) -> Unit,
    private val terminal: (FontTerminalAction) -> Unit,
) {
    private val controls = FontControls(::handle)
    private var presentation: FontPresentation? = null
    private var mode = PreviewMode.CURRENT
    val content = StudioSectionContent(StudioSettingsSection.FONT, controls, ::preview)

    fun render(
        draft: DraftRecord,
        view: FontPanelView,
        currentPreview: FontPanelPreview?,
        terminalPreference: StudioField,
    ) {
        require(
            view.selection == StudioFontProjection.selection(draft.appearance),
        ) { "Font view belongs to another draft" }
        require(terminalPreference.key == LegacyFieldKey(LegacyStore.SETTINGS, "fontInstallTerminal"))
        val destination = (terminalPreference.value as? RawValue.Text)?.value
        require(destination == view.resources.terminal) { "Terminal view does not match the shared preference" }
        presentation = FontPresentation(draft, view, currentPreview, terminalPreference)
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
                        latest.currentPreview
                    } else {
                        latest.view.preview
                    },
            ),
        )
    }

    private fun handle(event: FontPanelEvent) {
        val latest = presentation ?: return
        when (event) {
            is FontPanelEvent.Resource -> {
                resource(event)
            }

            is FontTerminalAction -> {
                terminal(event)
            }

            is FontPanelEvent.Terminal -> {
                val before = latest.terminalPreference
                val after =
                    StudioFieldEdits.edit(
                        before,
                        FieldPart.Value,
                        FieldValue.Present(RawValue.Text(event.value)),
                    )
                stage(listOf(SessionIntent.Shared(SharedIntent.Field(before, after))))
            }

            else -> {
                val edits = StudioFontEditing.edits(latest.draft, event)
                if (edits.isNotEmpty()) stage(edits)
            }
        }
    }
}

private data class FontPresentation(
    val draft: DraftRecord,
    val view: FontPanelView,
    val currentPreview: FontPanelPreview?,
    val terminalPreference: StudioField,
)
