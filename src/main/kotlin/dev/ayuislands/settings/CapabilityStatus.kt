package dev.ayuislands.settings

import com.intellij.util.ui.JBUI
import java.awt.FlowLayout
import javax.swing.JButton
import javax.swing.JLabel
import javax.swing.JPanel

internal class CapabilityStatus(
    onAction: () -> Unit,
) {
    private val message = JLabel()
    private val action = JButton().apply { addActionListener { onAction() } }

    val component =
        JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(CAPABILITY_GAP), 0)).apply {
            isOpaque = false
            isVisible = false
            add(message)
            add(action)
        }

    fun render(state: SyntaxCapabilityState?) {
        val presentation = state?.presentation()
        component.isVisible = presentation != null
        if (presentation == null) return
        message.text = presentation.message
        action.text = presentation.action.orEmpty()
        action.isVisible = presentation.action != null
        component.revalidate()
        component.repaint()
    }
}

internal data class CapabilityPresentation(
    val message: String,
    val action: String? = null,
)

internal fun SyntaxCapabilityState.presentation(): CapabilityPresentation? =
    when (this) {
        is SyntaxCapabilityState.Checking -> CapabilityPresentation("Checking language support…")
        is SyntaxCapabilityState.SupportUnavailable ->
            CapabilityPresentation(LANGUAGE_SUPPORT_INSTRUCTION, "Open Marketplace")
        is SyntaxCapabilityState.TemporarilyUnavailable -> CapabilityPresentation(reason, "Retry")
        is SyntaxCapabilityState.Incompatible ->
            CapabilityPresentation(
                "Some controls could not be verified — ${mismatches.describe()}. " +
                    "Update or enable $languageId language support, then Retry.",
                "Retry",
            )
        is SyntaxCapabilityState.Confirmed ->
            evidence.conditionalAbsences
                .takeIf { it.isNotEmpty() }
                ?.let { absences ->
                    CapabilityPresentation(
                        "These controls depend on semantic highlighting but are unavailable in the " +
                            "current $languageId configuration: ${absences.categories()}. " +
                            "Review highlighting settings, then return here.",
                        "Open Highlighting Settings",
                    )
                }
    }

private fun List<CapabilityMismatch>.describe(): String =
    joinToString("; ") { mismatch ->
        "${mismatch.primitive.displayName}: ${mismatch.reason}"
    }

private fun List<ConditionalAbsence>.categories(): String =
    joinToString(", ") { absence -> absence.primitive.displayName }

private const val CAPABILITY_GAP = 8
internal const val LANGUAGE_SUPPORT_INSTRUCTION =
    "To tune this language, please install its official plugin from JetBrains Marketplace."
