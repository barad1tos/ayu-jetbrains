package dev.ayuislands.studio.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBScrollPane
import dev.ayuislands.studio.model.AcceptanceChoices
import dev.ayuislands.studio.model.AcceptanceReview
import dev.ayuislands.studio.model.DraftId
import dev.ayuislands.studio.model.SessionIntent
import dev.ayuislands.studio.model.StudioDocument
import dev.ayuislands.studio.model.StudioList
import dev.ayuislands.studio.model.StudioSessionRecord
import javax.swing.JButton
import javax.swing.JComponent

/** Review returns explicit commands; opening or cancelling it never changes session work. */
internal sealed interface StudioReviewDecision {
    data class Continue(
        val choices: AcceptanceChoices,
        val intents: List<SessionIntent>,
    ) : StudioReviewDecision

    data class Edit(
        val draft: DraftId,
    ) : StudioReviewDecision

    data object Cancel : StudioReviewDecision
}

internal class StudioReviewDialog(
    items: StudioList<AcceptanceReview>,
    session: StudioSessionRecord,
    document: StudioDocument,
) : DialogWrapper(null as Project?, true) {
    var decision: StudioReviewDecision = StudioReviewDecision.Cancel
        private set

    private val form =
        StudioReviewForm(items, session, document, ::updateReadiness) { draft ->
            decision = StudioReviewDecision.Edit(draft)
            close(OK_EXIT_CODE)
        }
    internal val reviewContent: JComponent = JBScrollPane(form.panel)

    /** Actual native controls; the SDK's unit-test dialog peer does not expose a window. */
    internal val reviewButtons: List<JButton>
        get() = listOf(requireNotNull(getButton(okAction)), requireNotNull(getButton(cancelAction)))

    init {
        title = "Review Ayu Islands Changes"
        setOKButtonText("Continue")
        init()
        updateReadiness()
    }

    override fun createCenterPanel(): JComponent = reviewContent

    override fun doOKAction() {
        val selected = form.readDecision()
        if (selected == null) {
            setErrorText(form.error)
            return
        }
        decision = selected
        super.doOKAction()
    }

    private fun updateReadiness() {
        isOKActionEnabled = form.isResolved()
    }
}
