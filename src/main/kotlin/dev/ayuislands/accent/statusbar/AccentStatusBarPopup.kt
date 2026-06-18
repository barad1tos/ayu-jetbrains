package dev.ayuislands.accent.statusbar

import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import dev.ayuislands.accent.AccentResolutionChain
import dev.ayuislands.accent.AccentResolver
import dev.ayuislands.accent.StepOutcome
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Dimension
import java.awt.FlowLayout
import javax.swing.JPanel

/**
 * Popup showing the full accent resolution chain.
 *
 * Each source is listed in priority order with:
 * - A colored dot (accent color if won, gray otherwise)
 * - The source label
 * - The outcome detail (why it won or lost)
 * - The hex value (if applicable)
 *
 * The winning source is highlighted with a distinct background.
 */
internal object AccentStatusBarPopup {
    fun show(
        invoker: Component,
        chain: AccentResolutionChain,
    ) {
        val panel = buildChainPanel(chain)
        val popup =
            JBPopupFactory
                .getInstance()
                .createComponentPopupBuilder(panel, null)
                .setTitle("Accent Source")
                .setResizable(false)
                .setMovable(true)
                .setRequestFocus(true)
                .createPopup()
        popup.showUnderneathOf(invoker)
    }

    private fun buildChainPanel(chain: AccentResolutionChain): JPanel {
        val panel = JPanel()
        panel.layout = BorderLayout(JBUI.scale(0), JBUI.scale(0))
        panel.border = JBUI.Borders.empty(PADDING)

        // Winner header
        val winner = chain.winner
        val headerPanel = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(H_GAP), 0))
        headerPanel.isOpaque = false
        val winnerDot = AccentDotIcon(winner.hex ?: "#888888", WINNER_DOT_SIZE)
        headerPanel.add(JBLabel(winnerDot))
        val winnerLabel =
            JBLabel(
                "<html><b>${AccentResolver.sourceLabel(winner.source)}</b>" +
                    "${if (winner.hex != null) " — ${winner.hex}" else ""}</html>",
            )
        winnerLabel.font = winnerLabel.font.deriveFont(WINNER_FONT_SIZE.toFloat())
        headerPanel.add(winnerLabel)
        panel.add(headerPanel, BorderLayout.NORTH)

        // Chain steps
        val stepsPanel = JPanel()
        stepsPanel.layout = javax.swing.BoxLayout(stepsPanel, javax.swing.BoxLayout.Y_AXIS)
        stepsPanel.isOpaque = false

        for (step in chain.steps) {
            val rowPanel = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(H_GAP), 0))
            rowPanel.isOpaque = false
            rowPanel.alignmentX = Component.LEFT_ALIGNMENT

            val isWinner = step.outcome == StepOutcome.WON
            val dotColor = if (isWinner) (step.hex ?: "#888888") else "#666666"
            val dot = AccentDotIcon(dotColor, STEP_DOT_SIZE)
            rowPanel.add(JBLabel(dot))

            val outcomeIcon = if (isWinner) "✓" else outcomeSymbol(step.outcome)
            val text =
                buildString {
                    append(outcomeIcon)
                    append(" ")
                    append(AccentResolver.sourceLabel(step.source))
                    append(": ")
                    append(step.detail)
                    if (step.hex != null && !isWinner) {
                        append(" (")
                        append(step.hex)
                        append(")")
                    }
                }
            val label = JBLabel(text)
            label.font = label.font.deriveFont(STEP_FONT_SIZE.toFloat())
            if (!isWinner) {
                label.foreground = JBColor.GRAY
            }
            rowPanel.add(label)
            stepsPanel.add(rowPanel)
        }

        val scrollPane = JBScrollPane(stepsPanel)
        scrollPane.isOpaque = false
        scrollPane.border = null
        scrollPane.preferredSize =
            Dimension(
                JBUI.scale(POPUP_WIDTH),
                JBUI.scale(POPUP_ROW_HEIGHT * minOf(chain.steps.size, MAX_VISIBLE_ROWS) + PADDING * 2),
            )
        panel.add(scrollPane, BorderLayout.CENTER)

        return panel
    }

    private fun outcomeSymbol(outcome: StepOutcome): String =
        when (outcome) {
            StepOutcome.WON -> "✓"
            StepOutcome.LICENSE_BLOCKED -> "🔒"
            StepOutcome.NOT_SET -> "—"
            StepOutcome.NO_MAPPING -> "⚠"
            StepOutcome.NOT_DOMINANT -> "≈"
            StepOutcome.NOT_APPLICABLE -> "○"
            StepOutcome.UNAVAILABLE -> "✗"
        }

    private const val PADDING = 8
    private const val H_GAP = 4
    private const val WINNER_DOT_SIZE = 12
    private const val STEP_DOT_SIZE = 8
    private const val WINNER_FONT_SIZE = 13
    private const val STEP_FONT_SIZE = 11
    private const val POPUP_WIDTH = 420
    private const val POPUP_ROW_HEIGHT = 22
    private const val MAX_VISIBLE_ROWS = 8
}
