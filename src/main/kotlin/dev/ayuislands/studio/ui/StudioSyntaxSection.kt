package dev.ayuislands.studio.ui

import com.intellij.openapi.Disposable
import dev.ayuislands.settings.SyntaxCapabilityState
import dev.ayuislands.studio.model.AppearanceSnapshot
import dev.ayuislands.studio.model.DraftRecord
import dev.ayuislands.studio.model.StudioSettingsSection
import dev.ayuislands.studio.session.StudioSyntaxEditing
import dev.ayuislands.studio.session.StudioSyntaxProjection
import dev.ayuislands.syntax.PrimitiveCategory

internal data class StudioSyntaxView(
    val draft: AppearanceSnapshot,
    val language: String,
    val capability: SyntaxCapabilityState,
    val actuatedCategories: Set<PrimitiveCategory>?,
    val isEditable: Boolean,
    val isLicensed: Boolean,
)

internal sealed interface StudioSyntaxAction {
    data class SelectLanguage(
        val language: String,
    ) : StudioSyntaxAction

    data class DiscloseLanguage(
        val expanded: Boolean,
    ) : StudioSyntaxAction

    data object RecoverSupport : StudioSyntaxAction

    data object OpenColorScheme : StudioSyntaxAction

    data object Unlock : StudioSyntaxAction
}

/** Stage field edits against the host's latest draft and route other gestures separately. */
internal class StudioSyntaxSection(
    languageExpanded: Boolean,
    stage: ((DraftRecord) -> DraftRecord) -> Unit,
    onAction: (StudioSyntaxAction) -> Unit,
) : Disposable {
    private val controls =
        StudioSyntaxControls(languageExpanded, onAction) { edit ->
            stage { working -> StudioSyntaxEditing.edit(working, edit) }
        }
    val content = StudioSectionContent(StudioSettingsSection.SYNTAX, controls) {}

    fun render(view: StudioSyntaxView) {
        controls.render(StudioSyntaxProjection.settings(view.draft), view)
    }

    override fun dispose() = controls.closeStylePopups()
}
