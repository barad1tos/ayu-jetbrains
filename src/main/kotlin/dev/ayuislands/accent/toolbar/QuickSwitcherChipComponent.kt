package dev.ayuislands.accent.toolbar

import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
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
import dev.ayuislands.accent.toolbar.actions.QuickSwitcherActionGroup
import java.awt.BasicStroke
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JLabel
import javax.swing.SwingUtilities

/**
 * Main-toolbar chip that reflects the focused project's resolved accent. Wave-7
 * bumps the dimensions to a `16 × 16` outer box / `12 × 12` inner disc (WIDGET-02
 * closure) and turns the `ColorIcon` border on so the disc reads as a deliberate
 * UI element next to neighbouring widgets.
 *
 * Subscribes to [AccentChangedTopic] (Wave 1) AND [ApplicationActivationListener.TOPIC]
 * (Wave 2) via a single per-instance [Disposable] parent — Pattern E. Mouse routes
 * LMB to the popup (with `this` passed so the popup can wire a per-popup
 * [com.intellij.openapi.ui.popup.JBPopupListener] that toggles popup-attached
 * state) and RMB to the right-click context menu (Wave 3).
 *
 * Popup-attached state — a Boolean flipped by the popup's [com.intellij.openapi.ui.popup.JBPopupListener]
 * via `setPopupAttached(true|false)` — is painted as a 2-px focused ring in
 * `JBUI.CurrentTheme.ActionButton.focusedBorder()` so the chip reads as the
 * popup's anchor while it is open. The listener lives on the POPUP (auto-disposes
 * on popup close) — never on the chip's `MessageBusConnection`, which would
 * inflate the Wave-6 lifecycle test's subscription count beyond the locked 2.
 */
internal class QuickSwitcherChipComponent :
    JLabel(),
    Disposable {
    private var connection: MessageBusConnection? = null

    internal var isPopupAttached: Boolean = false
        private set

    init {
        val box = JBUI.scale(CHIP_BOX_PX)
        preferredSize = Dimension(box, box)
        icon = ColorIcon(JBUI.scale(CHIP_SWATCH_PX), JBColor.GRAY, true)
        toolTipText = ""
        addMouseListener(
            object : MouseAdapter() {
                override fun mousePressed(event: MouseEvent) {
                    if (!AyuVariant.isAyuActive()) return
                    when {
                        SwingUtilities.isRightMouseButton(event) -> showContextMenu(event.x, event.y)
                        SwingUtilities.isLeftMouseButton(event) ->
                            QuickSwitcherPopup.show(this@QuickSwitcherChipComponent, this@QuickSwitcherChipComponent)
                    }
                }
            },
        )
    }

    /**
     * Toggled by the popup's [com.intellij.openapi.ui.popup.JBPopupListener] when
     * the chip is the anchor of an opened/closed popup. Repaints the chip so the
     * 2-px focused ring overlay updates in-place. Pattern E discipline: the
     * listener is registered on the popup, NEVER on the chip's MessageBus.
     */
    internal fun setPopupAttached(active: Boolean) {
        if (isPopupAttached == active) return
        isPopupAttached = active
        repaint()
    }

    override fun paintComponent(g: Graphics) {
        super.paintComponent(g)
        if (!isPopupAttached) return
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.color = JBUI.CurrentTheme.ActionButton.focusedBorder()
            g2.stroke = BasicStroke(JBUI.scale(RING_THICKNESS_PX).toFloat())
            val inset = RING_INSET_PX
            g2.drawRoundRect(
                inset,
                inset,
                width - inset * 2,
                height - inset * 2,
                JBUI.scale(RING_ARC_PX),
                JBUI.scale(RING_ARC_PX),
            )
        } finally {
            g2.dispose()
        }
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
     * Build and show the right-click context menu via [ActionManager.createActionPopupMenu]
     * with the canonical [QuickSwitcherActionGroup]. Pattern J gating lives inside each
     * action's `update` — the menu surface is always built; individual items
     * hide themselves when LAF is non-Ayu or license is invalid.
     */
    private fun showContextMenu(
        x: Int,
        y: Int,
    ) {
        val menu =
            ActionManager
                .getInstance()
                .createActionPopupMenu(CONTEXT_MENU_PLACE, QuickSwitcherActionGroup.build())
        menu.component.show(this, x, y)
    }

    /**
     * Re-resolve the focused project's accent and repaint. Runs on EDT — every
     * caller is on EDT already. Pattern B catches transient [RuntimeException]
     * so the chip stays paintable with its previous icon.
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
        icon = ColorIcon(JBUI.scale(CHIP_SWATCH_PX), color, true)
        val source = AccentResolver.source(project)
        toolTipText = "$hex — ${AccentResolver.sourceLabel(source)}"
        repaint()
    }

    companion object {
        // WIDGET-02 closure (Wave 7) — final dimensions per 48-REDESIGN-SPEC §3.1.
        // Was: CHIP_BOX_PX = 12, CHIP_SWATCH_PX = 10 (Wave 2 starting point).
        internal const val CHIP_BOX_PX = 16
        internal const val CHIP_SWATCH_PX = 12

        // Action place ID for the right-click context menu (Plan 48-03 D-14b).
        private const val CONTEXT_MENU_PLACE = "AyuQuickSwitcher.ContextMenu"

        // Popup-attached focused ring geometry.
        private const val RING_THICKNESS_PX = 2
        private const val RING_INSET_PX = 1
        private const val RING_ARC_PX = 4

        private val LOG = logger<QuickSwitcherChipComponent>()
    }
}
