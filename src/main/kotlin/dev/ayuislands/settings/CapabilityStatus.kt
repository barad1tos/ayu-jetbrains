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

private data class CapabilityPresentation(
    val message: String,
    val action: String? = null,
)

private fun SyntaxCapabilityState.presentation(): CapabilityPresentation? =
    when (this) {
        is SyntaxCapabilityState.Checking -> CapabilityPresentation("Checking language support…")
        is SyntaxCapabilityState.PluginUnavailable ->
            CapabilityPresentation(recovery.instruction, "Open Marketplace")
        is SyntaxCapabilityState.TemporarilyUnavailable -> CapabilityPresentation(reason, "Retry")
        is SyntaxCapabilityState.Incompatible ->
            CapabilityPresentation("Some language controls could not be verified.", "Retry")
        is SyntaxCapabilityState.Confirmed ->
            evidence.conditionalAbsences
                .takeIf { it.isNotEmpty() }
                ?.let {
                    CapabilityPresentation(
                        "Some controls need semantic highlighting. Enable it for $languageId " +
                            "under Editor | Color Scheme, then return here.",
                        "Open Highlighting Settings",
                    )
                }
    }

private const val CAPABILITY_GAP = 8
