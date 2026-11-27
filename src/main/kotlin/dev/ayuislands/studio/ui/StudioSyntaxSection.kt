package dev.ayuislands.studio.ui

import com.intellij.openapi.Disposable
import com.intellij.openapi.util.Disposer
import dev.ayuislands.settings.SyntaxCapabilityState
import dev.ayuislands.settings.SyntaxPreviewComponent
import dev.ayuislands.studio.model.AppearanceSnapshot
import dev.ayuislands.studio.model.DraftRecord
import dev.ayuislands.studio.model.PreviewMode
import dev.ayuislands.studio.model.StudioSettingsSection
import dev.ayuislands.studio.session.StudioSyntaxEditing
import dev.ayuislands.studio.session.StudioSyntaxProjection
import dev.ayuislands.syntax.PrimitiveCategory
import java.awt.BorderLayout
import javax.swing.JPanel

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
    private val sampleSlot =
        JPanel(BorderLayout()).apply {
            isOpaque = false
            isVisible = false
            accessibleContext.accessibleName = "Syntax preview"
        }
    private var previewMode = PreviewMode.CURRENT
    private var comparisonView: SyntaxComparisonView? = null
    private var renderedPreview: SyntaxPreviewFrame? = null
    private var sample: SyntaxPreviewComponent? = null
    private val controls =
        StudioSyntaxControls(languageExpanded, sampleSlot, ::showPrimitive, onAction) { edit ->
            stage { working -> StudioSyntaxEditing.edit(working, edit) }
        }
    val content = StudioSectionContent(StudioSettingsSection.SYNTAX, controls, ::selectPreview)

    fun render(
        view: StudioSyntaxView,
        comparison: StudioSyntaxComparison? = null,
    ) {
        val capability = view.capability.takeIf { it.languageId == view.language }
        comparisonView = comparison?.let { SyntaxComparisonView(it, view.language, capability) }
        controls.render(StudioSyntaxProjection.settings(view.draft), view)
        selectPreview(previewMode)
    }

    private fun selectPreview(mode: PreviewMode) {
        previewMode = mode
        val view = comparisonView
        sampleSlot.isVisible = view != null
        if (view == null) return
        val selected = if (mode == PreviewMode.CURRENT) view.comparison.current else view.comparison.draft
        val frame = SyntaxPreviewFrame(selected, view.language, view.capability)
        if (renderedPreview == frame) return
        val editor = sample ?: mountPreview(frame)
        editor.updatePreview(selected.variant, frame.language, selected.scheme)
        editor.showCapability(frame.capability)
        renderedPreview = frame
    }

    private fun mountPreview(frame: SyntaxPreviewFrame): SyntaxPreviewComponent =
        SyntaxPreviewComponent(frame.sample.variant, frame.language, sampleScheme = frame.sample.scheme).also {
            Disposer.register(this, it)
            sampleSlot.add(it, BorderLayout.CENTER)
            sample = it
        }

    private fun showPrimitive(category: PrimitiveCategory) {
        if (sampleSlot.isVisible) sample?.showPrimitive(category)
    }

    override fun dispose() = controls.closeStylePopups()
}

private data class SyntaxComparisonView(
    val comparison: StudioSyntaxComparison,
    val language: String,
    val capability: SyntaxCapabilityState?,
)

private data class SyntaxPreviewFrame(
    val sample: StudioSyntaxSample,
    val language: String,
    val capability: SyntaxCapabilityState?,
)
