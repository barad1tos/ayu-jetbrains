package dev.ayuislands.accent.statusbar

import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.CustomStatusBarWidget
import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import dev.ayuislands.accent.AccentChangeListener
import dev.ayuislands.accent.AccentChangedTopic
import dev.ayuislands.accent.AccentContext
import dev.ayuislands.accent.AccentHex
import dev.ayuislands.accent.AccentResolutionChain
import dev.ayuislands.accent.AccentResolver
import dev.ayuislands.accent.ProjectLanguageDetectionListener
import dev.ayuislands.accent.StepOutcome
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Cursor
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.Icon
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel

/**
 * Status-bar widget showing the active accent source and a color indicator.
 *
 * Displays a small accent-colored dot followed by the source label.
 * Click opens [AccentStatusBarPopup] with the full resolution chain.
 *
 * Lifecycle: subscribes to [AccentChangedTopic] and
 * [ProjectLanguageDetectionListener.TOPIC] on install; disconnects on dispose.
 * Hidden entirely when the license gate fails (premium feature) via
 * [AccentStatusBarWidgetFactory.isAvailable].
 */
internal class AccentStatusBarWidget(
    private val project: Project,
) : CustomStatusBarWidget {
    private var connection: com.intellij.util.messages.MessageBusConnection? = null
    private val panel: AccentStatusBarPanel =
        AccentStatusBarPanel { chain: AccentResolutionChain ->
            AccentStatusBarPopup.show(this.panel, chain)
        }

    override fun ID(): String = WIDGET_ID

    override fun getComponent(): JComponent = panel

    override fun install(statusBar: com.intellij.openapi.wm.StatusBar) {
        subscribeToEvents()
        refreshFromProject()
    }

    override fun dispose() {
        connection?.disconnect()
        connection = null
    }

    private fun subscribeToEvents() {
        val parent =
            com.intellij.openapi.util.Disposer
                .newDisposable("AccentStatusBarWidget.connection")
        val conn = project.messageBus.connect(parent)
        connection = conn

        conn.subscribe(
            AccentChangedTopic.TOPIC,
            AccentChangeListener { _, _, _ ->
                com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater {
                    refreshFromProject()
                }
            },
        )

        conn.subscribe(
            ProjectLanguageDetectionListener.TOPIC,
            ProjectLanguageDetectionListener { _ ->
                com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater {
                    refreshFromProject()
                }
            },
        )
    }

    fun refreshFromProject() {
        if (project.isDisposed) return
        val context = AccentContext.detect() ?: return
        val chain = AccentResolver.resolveChain(project, context)
        panel.updateFromChain(chain)
    }

    companion object {
        const val WIDGET_ID = "AyuIslands.AccentStatusBar"
    }
}

/**
 * Panel rendering the accent indicator dot and source label.
 * Clicking opens the resolution-chain popup.
 *
 * Shows a hand cursor and hover background to signal clickability.
 */
internal class AccentStatusBarPanel(
    private val onClick: (AccentResolutionChain) -> Unit,
) : JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(H_GAP), 0)) {
    private val dotLabel = JLabel()
    private val sourceLabel = JLabel()

    private var currentChain: AccentResolutionChain? = null
    private var hovering = false

    init {
        isOpaque = false
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        dotLabel.preferredSize = Dimension(JBUI.scale(DOT_SIZE), JBUI.scale(DOT_SIZE))
        sourceLabel.font = sourceLabel.font.deriveFont(JBUI.scale(FONT_SIZE).toFloat())
        add(dotLabel, BorderLayout.WEST)
        add(sourceLabel, BorderLayout.CENTER)

        addMouseListener(
            object : MouseAdapter() {
                override fun mousePressed(e: MouseEvent) {
                    val chain = currentChain ?: return
                    onClick(chain)
                }

                override fun mouseEntered(e: MouseEvent) {
                    hovering = true
                    repaint()
                }

                override fun mouseExited(e: MouseEvent) {
                    hovering = false
                    repaint()
                }
            },
        )
    }

    override fun paintComponent(g: Graphics) {
        if (hovering) {
            val g2 = g as Graphics2D
            g2.color = HOVER_BG
            g2.fillRect(0, 0, width, height)
        }
        super.paintComponent(g)
    }

    fun updateFromChain(chain: AccentResolutionChain) {
        currentChain = chain
        val winner = chain.winner
        val hex = winner.hex ?: AccentHex.unsafeOf("#FFB454").value
        dotLabel.icon = AccentDotIcon(hex, DOT_SIZE)
        sourceLabel.text = buildStatusText(chain)
        toolTipText = buildToolTip(chain)
        revalidate()
        repaint()
    }

    private fun buildStatusText(chain: AccentResolutionChain): String {
        val winner = chain.winner
        return "${AccentResolver.sourceLabel(winner.source)} · ${winner.detail}"
    }

    private fun buildToolTip(chain: AccentResolutionChain): String =
        buildString {
            val winner = chain.winner
            append("<html>")
            append(escapeHtml("${winner.hex ?: "—"} — ${AccentResolver.sourceLabel(winner.source)}"))
            append("<br><br>")
            appendChainRows(chain)
            append("<br>")
            append(escapeHtml("Click to see full resolution chain"))
            append("</html>")
        }

    private fun StringBuilder.appendChainRows(chain: AccentResolutionChain) {
        for (step in chain.steps) {
            val marker = if (step.outcome == StepOutcome.WON) "✓" else " "
            append(escapeHtml("$marker ${AccentResolver.sourceLabel(step.source)}: ${step.detail}"))
            append("<br>")
        }
    }

    private fun escapeHtml(text: String): String =
        text
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")

    companion object {
        internal const val DOT_SIZE = 10
        private const val H_GAP = 4
        private const val FONT_SIZE = 11
        private val HOVER_BG: Color =
            JBColor(Color(255, 255, 255, 30), Color(255, 255, 255, 20))
    }
}

/**
 * Small colored circle icon for the accent indicator.
 * Supports configurable size for different contexts.
 */
internal class AccentDotIcon(
    hex: String,
    private val size: Int,
) : Icon {
    private val color: Color =
        try {
            JBColor.decode(hex)
        } catch (_: NumberFormatException) {
            JBColor.GRAY
        }

    override fun paintIcon(
        c: Component?,
        g: Graphics?,
        x: Int,
        y: Int,
    ) {
        val g2 = g as? Graphics2D ?: return
        g2.color = color
        g2.fillOval(x, y, iconWidth, iconHeight)
    }

    override fun getIconWidth(): Int = JBUI.scale(size)

    override fun getIconHeight(): Int = JBUI.scale(size)
}
