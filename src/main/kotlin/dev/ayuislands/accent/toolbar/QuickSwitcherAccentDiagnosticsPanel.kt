package dev.ayuislands.accent.toolbar

import com.intellij.ui.JBColor
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import dev.ayuislands.accent.AccentResolutionChain
import dev.ayuislands.accent.AccentResolutionStep
import dev.ayuislands.accent.AccentResolver
import dev.ayuislands.accent.StepOutcome
import java.awt.BasicStroke
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.RenderingHints
import javax.swing.JComponent
import javax.swing.JPanel

internal class QuickSwitcherAccentDiagnosticsPanel(
    initialChain: AccentResolutionChain,
) : JPanel(BorderLayout()) {
    private var chain = initialChain
    private var isExpanded = false

    init {
        isOpaque = false
        updateFromChain(initialChain)
    }

    fun updateFromChain(nextChain: AccentResolutionChain) {
        chain = nextChain
        rebuild()
    }

    private fun rebuild() {
        removeAll()

        val rows = JPanel(GridBagLayout()).apply { isOpaque = false }
        val supportingSteps = supportingSteps()
        val hasSupportingSteps = supportingSteps.isNotEmpty()
        val hasExpandedChain = isExpanded && hasSupportingSteps
        val summaryTimelinePosition =
            if (hasExpandedChain) TimelinePosition.FIRST else TimelinePosition.ONLY
        addRow(
            panel = rows,
            row = 0,
            diagnosticsRow =
                DiagnosticsRow(
                    markerColor = markerColor(chain.winner),
                    markerSize = SUMMARY_MARKER_SIZE,
                    timelinePosition = summaryTimelinePosition,
                    content = buildSummaryPanel(hasSupportingSteps),
                    isLastRow = !hasExpandedChain,
                ),
        )
        if (hasExpandedChain) {
            supportingSteps.forEachIndexed { index, step ->
                val stepTimelinePosition =
                    if (index == supportingSteps.lastIndex) {
                        TimelinePosition.LAST
                    } else {
                        TimelinePosition.MIDDLE
                    }
                addRow(
                    panel = rows,
                    row = index + 1,
                    diagnosticsRow =
                        DiagnosticsRow(
                            markerColor = markerColor(step),
                            markerSize = STEP_MARKER_SIZE,
                            timelinePosition = stepTimelinePosition,
                            content = buildStepPanel(step),
                            isLastRow = index == supportingSteps.lastIndex,
                        ),
                )
            }
        }

        add(rows, BorderLayout.CENTER)
        revalidate()
        repaint()
    }

    private fun supportingSteps(): List<AccentResolutionStep> {
        val winnerIndex = chain.steps.indexOfLast { step -> step == chain.winner }
        return chain.steps.filterIndexed { index, _ -> index != winnerIndex }
    }

    private fun addRow(
        panel: JPanel,
        row: Int,
        diagnosticsRow: DiagnosticsRow,
    ) {
        val bottomInset = if (diagnosticsRow.isLastRow) 0 else JBUI.scale(ROW_GAP)
        panel.add(
            TimelineMarkerCell(
                diagnosticsRow.markerColor,
                diagnosticsRow.markerSize,
                diagnosticsRow.timelinePosition,
            ),
            GridBagConstraints().apply {
                gridx = 0
                gridy = row
                weightx = 0.0
                weighty = 0.0
                anchor = GridBagConstraints.NORTH
                fill = GridBagConstraints.BOTH
                insets = JBUI.insets(0, 0, bottomInset, TEXT_GAP)
            },
        )
        panel.add(
            diagnosticsRow.content,
            GridBagConstraints().apply {
                gridx = 1
                gridy = row
                weightx = 1.0
                weighty = 0.0
                anchor = GridBagConstraints.WEST
                fill = GridBagConstraints.HORIZONTAL
                insets = JBUI.insetsBottom(bottomInset)
            },
        )
    }

    private fun buildSummaryPanel(hasSupportingSteps: Boolean): JComponent {
        val winner = chain.winner
        val panel =
            JPanel(
                BorderLayout(
                    JBUI.scale(TEXT_GAP),
                    JBUI.scale(SUMMARY_VERTICAL_GAP),
                ),
            )
        panel.isOpaque = false

        val summaryRow = JPanel(BorderLayout(JBUI.scale(TEXT_GAP), 0))
        summaryRow.isOpaque = false
        val sourceLabel = AccentResolver.sourceLabel(winner.source)
        val summaryDetail = summaryDetail(winner)
        val summaryText = "$sourceLabel · ${summaryDetail.primary}"
        val fullSummaryText = "$sourceLabel · ${winner.detail}"
        summaryRow.add(
            diagnosticLabel(
                text = summaryText,
                fullText = fullSummaryText,
                fontStyle = Font.BOLD,
                fontSize = SUMMARY_FONT_SIZE,
            ),
            BorderLayout.CENTER,
        )
        winner.hex?.let { hex ->
            summaryRow.add(
                JBLabel(hex).apply {
                    foreground = SECONDARY_TEXT
                    font = font.deriveFont(JBUI.scale(DETAIL_FONT_SIZE).toFloat())
                },
                BorderLayout.EAST,
            )
        }
        panel.add(summaryRow, BorderLayout.NORTH)
        if (isExpanded && summaryDetail.secondary != null) {
            panel.add(
                diagnosticLabel(
                    text = summaryDetail.secondary,
                    fullText = winner.detail,
                    foregroundColor = SECONDARY_TEXT,
                    fontSize = DETAIL_FONT_SIZE,
                ),
                BorderLayout.CENTER,
            )
        }

        if (!hasSupportingSteps) {
            return panel
        }

        val toggleText = if (isExpanded) COLLAPSE_TEXT else EXPAND_TEXT
        panel.add(
            ActionLink(toggleText) {
                isExpanded = !isExpanded
                rebuild()
            }.apply {
                alignmentX = LEFT_ALIGNMENT
                accessibleContext.accessibleName = toggleText
            },
            BorderLayout.SOUTH,
        )

        return panel
    }

    private fun buildStepPanel(step: AccentResolutionStep): JComponent {
        val panel = JPanel(BorderLayout(JBUI.scale(TEXT_GAP), 0))
        panel.isOpaque = false
        val stepText = "${AccentResolver.sourceLabel(step.source)} · ${step.detail}"
        panel.add(
            diagnosticLabel(
                text = stepText,
                fullText = stepText,
                foregroundColor =
                    if (step.outcome == StepOutcome.WON) {
                        PRIMARY_TEXT
                    } else {
                        SECONDARY_TEXT
                    },
                fontSize = DETAIL_FONT_SIZE,
            ),
            BorderLayout.CENTER,
        )
        step.hex?.takeIf { step.outcome != StepOutcome.WON }?.let { hex ->
            panel.add(
                JBLabel(hex).apply {
                    foreground = SECONDARY_TEXT
                    font = font.deriveFont(JBUI.scale(DETAIL_FONT_SIZE).toFloat())
                },
                BorderLayout.EAST,
            )
        }
        return panel
    }

    private fun summaryDetail(step: AccentResolutionStep): SummaryDetail {
        if (step.source != AccentResolver.Source.LANGUAGE_OVERRIDE || step.outcome != StepOutcome.WON) {
            return SummaryDetail(primary = step.detail, secondary = null)
        }
        val match =
            DETECTED_LANGUAGE_PERCENTAGE_DETAIL.matchEntire(step.detail)
                ?: return SummaryDetail(primary = step.detail, secondary = null)
        val languageName = match.groupValues[DETECTED_LANGUAGE_NAME_GROUP]
        val proportions = match.groupValues[DETECTED_LANGUAGE_PROPORTIONS_GROUP]
        return SummaryDetail(
            primary = "Detected $languageName",
            secondary = "$languageName $proportions",
        )
    }

    private fun diagnosticLabel(
        text: String,
        fullText: String,
        fontStyle: Int = Font.PLAIN,
        fontSize: Int,
        foregroundColor: Color? = null,
    ): JBLabel =
        BoundedDiagnosticLabel(text).apply {
            toolTipText = fullText
            foregroundColor?.let { foreground = it }
            font = font.deriveFont(fontStyle, JBUI.scale(fontSize).toFloat())
        }

    private fun markerColor(step: AccentResolutionStep): Color =
        when (step.outcome) {
            StepOutcome.WON -> decodeColor(step.hex) ?: WINNER_FALLBACK
            StepOutcome.UNAVAILABLE -> UNAVAILABLE_DOT
            StepOutcome.LICENSE_BLOCKED -> BLOCKED_DOT
            else -> MUTED_DOT
        }

    private fun decodeColor(hex: String?): Color? =
        hex?.let {
            try {
                JBColor.decode(it)
            } catch (_: NumberFormatException) {
                null
            }
        }

    private class BoundedDiagnosticLabel(
        text: String,
    ) : JBLabel(text) {
        override fun getPreferredSize(): Dimension {
            val size = super.getPreferredSize()
            return Dimension(
                size.width.coerceAtMost(JBUI.scale(DIAGNOSTIC_TEXT_WIDTH_LIMIT)),
                size.height,
            )
        }

        override fun getMinimumSize(): Dimension = Dimension(0, preferredSize.height)
    }

    private data class SummaryDetail(
        val primary: String,
        val secondary: String?,
    )

    private data class DiagnosticsRow(
        val markerColor: Color,
        val markerSize: Int,
        val timelinePosition: TimelinePosition,
        val content: JComponent,
        val isLastRow: Boolean,
    )

    private enum class TimelinePosition {
        ONLY,
        FIRST,
        MIDDLE,
        LAST,
    }

    private class TimelineMarkerCell(
        private val markerColor: Color,
        private val markerSize: Int,
        private val timelinePosition: TimelinePosition,
    ) : JComponent() {
        init {
            name = MARKER_COLUMN_NAME
            preferredSize =
                Dimension(
                    scaledMarkerColumnWidth(),
                    JBUI.scale(ROW_HEIGHT),
                )
            minimumSize = preferredSize
            isOpaque = false
        }

        override fun paintComponent(g: Graphics) {
            val graphics = g as? Graphics2D ?: return
            val centerX = width / 2
            val centerY = height / 2 + JBUI.scale(MARKER_CENTER_Y_OFFSET)
            graphics.setRenderingHint(
                RenderingHints.KEY_ANTIALIASING,
                RenderingHints.VALUE_ANTIALIAS_ON,
            )

            if (timelinePosition != TimelinePosition.ONLY) {
                val railStart =
                    if (timelinePosition == TimelinePosition.FIRST) {
                        centerY
                    } else {
                        0
                    }
                val railEnd =
                    if (timelinePosition == TimelinePosition.LAST) {
                        centerY
                    } else {
                        height
                    }
                graphics.color = RAIL_COLOR
                graphics.stroke = BasicStroke(JBUI.scale(1).toFloat())
                graphics.drawLine(centerX, railStart, centerX, railEnd)
            }

            val size = JBUI.scale(markerSize)
            graphics.color = markerColor
            graphics.fillOval(centerX - size / 2, centerY - size / 2, size, size)
        }
    }

    companion object {
        internal const val MARKER_COLUMN_NAME = "accent-diagnostics-marker-column"
        private const val MARKER_COLUMN_WIDTH = 18
        private const val MARKER_CENTER_Y_OFFSET = -2
        private const val ROW_HEIGHT = 22
        private const val ROW_GAP = 2
        private const val TEXT_GAP = 6
        private const val SUMMARY_VERTICAL_GAP = 1
        private const val SUMMARY_MARKER_SIZE = 7
        private const val STEP_MARKER_SIZE = 5
        private const val SUMMARY_FONT_SIZE = 11
        private const val DETAIL_FONT_SIZE = 11
        private const val DIAGNOSTIC_TEXT_WIDTH_LIMIT = 230
        private const val DETECTED_LANGUAGE_NAME_GROUP = 1
        private const val DETECTED_LANGUAGE_PROPORTIONS_GROUP = 2
        private const val EXPAND_TEXT = "Show resolution chain..."
        private const val COLLAPSE_TEXT = "Collapse"
        private val DETECTED_LANGUAGE_PERCENTAGE_DETAIL =
            Regex("""^Detected (.+?) (\d+%(?:, .+? \d+%)*)$""")
        private val PRIMARY_TEXT: Color = JBColor.foreground()
        private val SECONDARY_TEXT: Color = JBColor.GRAY
        private val RAIL_COLOR: Color =
            JBColor(
                Color(82, 168, 214, 110),
                Color(82, 168, 214, 100),
            )
        private val MUTED_DOT: Color =
            JBColor(
                Color(128, 139, 154),
                Color(128, 139, 154),
            )
        private val BLOCKED_DOT: Color =
            JBColor(
                Color(173, 142, 255),
                Color(173, 142, 255),
            )
        private val UNAVAILABLE_DOT: Color =
            JBColor(
                Color(238, 111, 111),
                Color(238, 111, 111),
            )
        private val WINNER_FALLBACK: Color =
            JBColor(
                Color(82, 168, 214),
                Color(82, 168, 214),
            )

        internal fun scaledMarkerColumnWidth(): Int = JBUI.scale(MARKER_COLUMN_WIDTH)
    }
}
