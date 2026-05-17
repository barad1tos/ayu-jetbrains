package dev.ayuislands.accent.toolbar

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationActivationListener
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.wm.IdeFrame
import com.intellij.ui.ColorUtil
import com.intellij.ui.JBColor
import com.intellij.util.messages.MessageBusConnection
import com.intellij.util.ui.ColorIcon
import com.intellij.util.ui.JBUI
import dev.ayuislands.accent.AccentApplicator
import dev.ayuislands.accent.AccentChangeListener
import dev.ayuislands.accent.AccentChangedTopic
import dev.ayuislands.accent.AccentResolver
import dev.ayuislands.accent.AyuVariant
import java.awt.Dimension
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JLabel
import javax.swing.SwingUtilities

/**
 * Main-toolbar chip that reflects the focused project's resolved accent. Subscribes to
 * [AccentChangedTopic] (Wave 1 — Plan 48-01) AND [ApplicationActivationListener.TOPIC]
 * (RESEARCH §2) via a single per-instance [Disposable] parent — Pattern E. Mouse-routes
 * LMB to the popup (this plan) and RMB to the context menu (Wave 3 — Plan 48-03 wires it;
 * left as TODO in this plan).
 *
 * Renders as a [JLabel] with a bundled [ColorIcon] (D-20 simplest path — no
 * `paintComponent` override, no `@TestOnly` paint seam needed).
 */
internal class QuickSwitcherChipComponent :
    JLabel(),
    Disposable {
    private var connection: MessageBusConnection? = null

    init {
        val box = JBUI.scale(CHIP_BOX_PX)
        preferredSize = Dimension(box, box)
        icon = ColorIcon(JBUI.scale(CHIP_SWATCH_PX), JBColor.GRAY)
        toolTipText = ""
        addMouseListener(
            object : MouseAdapter() {
                override fun mousePressed(event: MouseEvent) {
                    if (!AyuVariant.isAyuActive()) return
                    when {
                        SwingUtilities.isRightMouseButton(event) -> {
                            // TODO Plan 48-03 (Wave 3) wires the right-click context menu via
                            // ActionManager.createActionPopupMenu("AyuQuickSwitcher.ContextMenu", group).
                        }
                        SwingUtilities.isLeftMouseButton(event) ->
                            QuickSwitcherPopup.show(this@QuickSwitcherChipComponent)
                    }
                }
            },
        )
    }

    override fun addNotify() {
        super.addNotify()
        if (connection != null) return // re-attach idempotency — see RESEARCH §7
        val conn = ApplicationManager.getApplication().messageBus.connect(this)
        connection = conn
        conn.subscribe(
            AccentChangedTopic.TOPIC,
            AccentChangeListener { _, _, _ ->
                SwingUtilities.invokeLater { refreshFromFocusedProject() }
            },
        )
        conn.subscribe(
            ApplicationActivationListener.TOPIC,
            object : ApplicationActivationListener {
                override fun applicationActivated(ideFrame: IdeFrame) {
                    refreshFromFocusedProject()
                }
            },
        )
        refreshFromFocusedProject()
    }

    override fun removeNotify() {
        connection?.disconnect()
        connection = null
        super.removeNotify()
    }

    override fun dispose() {
        connection?.disconnect()
        connection = null
    }

    /**
     * Re-resolve the focused project's accent and repaint. Runs on EDT — every caller is on
     * EDT already (`BGT.updateCustomComponent` hops via [SwingUtilities.invokeLater],
     * [ApplicationActivationListener] is EDT-bound per platform contract,
     * [AccentChangedTopic] handler wraps with [SwingUtilities.invokeLater]).
     *
     * Pattern B: a transient [RuntimeException] from [AccentResolver.resolve]
     * (variant detection mid-LAF-swap, per-project state mid-dispose) is caught
     * so the chip stays paintable with its previous icon — the next subscription
     * event refreshes it again. `Throwable` is intentionally NOT caught so an
     * OOM/Error reaches the JVM uncaught handler instead of degrading silently.
     */
    fun refreshFromFocusedProject() {
        val variant = AyuVariant.detect() ?: return
        val project = AccentApplicator.resolveFocusedProject()
        val hex =
            try {
                AccentResolver.resolve(project, variant)
            } catch (exception: RuntimeException) {
                LOG.warn("QuickSwitcher chip resolve failed", exception)
                return
            }
        val color = ColorUtil.fromHex(hex)
        icon = ColorIcon(JBUI.scale(CHIP_SWATCH_PX), color)
        val source = AccentResolver.source(project)
        toolTipText = "$hex — ${AccentResolver.sourceLabel(source)}"
        repaint()
    }

    companion object {
        // D-05 starting point — runIde checkpoint in Plan 48-06 tunes the exact values.
        internal const val CHIP_BOX_PX = 12
        internal const val CHIP_SWATCH_PX = 10
        private val LOG = logger<QuickSwitcherChipComponent>()
    }
}
