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
import java.awt.Dimension
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JLabel
import javax.swing.SwingUtilities

/**
 * Main-toolbar chip that reflects the focused project's resolved accent. The
 * chip is a `JLabel` sized to the standard MainToolbar icon cell (`16 × 16`,
 * JBUI-scaled) with a `ColorIcon` that fills the full bounds (no inner disc
 * inset), so the icon does not appear small against the platform's pressed /
 * hover highlight that surrounds the cell when the popup is open.
 *
 * The popup-attached visual feedback is delegated to the platform — IntelliJ
 * paints its standard MainToolbar action-button hover / pressed background
 * around the chip cell while the popup it anchors is open. No custom focused
 * ring is drawn here; an earlier Wave-7 attempt at a 2-px overlay misaligned
 * with the platform highlight and was removed.
 *
 * Subscribes to [AccentChangedTopic] (Wave 1) AND [ApplicationActivationListener.TOPIC]
 * (Wave 2) via a single per-instance [Disposable] parent — Pattern E. Mouse routes
 * LMB to the popup (with `this` passed so the popup can wire a per-popup
 * [com.intellij.openapi.ui.popup.JBPopupListener] that toggles popup-attached
 * state for any future consumers that depend on it) and RMB to the right-click
 * context menu (Wave 3).
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
        icon = ColorIcon(JBUI.scale(CHIP_BOX_PX), JBColor.GRAY, true)
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
     * the chip is the anchor of an opened/closed popup. Repaints the chip so any
     * future consumer of `isPopupAttached` (a custom ring, a tinted icon, etc.)
     * updates in-place. Today the only visual response is the platform's own
     * MainToolbar hover/pressed highlight, which the platform manages — this
     * setter is kept for the Wave-6 lifecycle test contract and any later
     * iteration that wants chip-level pressed feedback.
     */
    internal fun setPopupAttached(active: Boolean) {
        if (isPopupAttached == active) return
        isPopupAttached = active
        repaint()
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
        icon = ColorIcon(JBUI.scale(CHIP_BOX_PX), color, true)
        val source = AccentResolver.source(project)
        toolTipText = "$hex — ${AccentResolver.sourceLabel(source)}"
        repaint()
    }

    companion object {
        // WIDGET-02 closure — final dimensions: a 16 × 16 JBUI-scaled cell whose
        // ColorIcon fills the full bounds (no inner-disc inset), so the icon
        // does not look small against the platform's pressed/hover highlight
        // that paints around the cell.
        internal const val CHIP_BOX_PX = 16

        // Action place ID for the right-click context menu (Plan 48-03 D-14b).
        private const val CONTEXT_MENU_PLACE = "AyuQuickSwitcher.ContextMenu"

        private val LOG = logger<QuickSwitcherChipComponent>()
    }
}
