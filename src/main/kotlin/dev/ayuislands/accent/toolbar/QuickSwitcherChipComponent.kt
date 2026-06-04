package dev.ayuislands.accent.toolbar

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.application.ApplicationActivationListener
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.wm.IdeFrame
import com.intellij.util.messages.MessageBusConnection
import com.intellij.util.ui.JBUI
import dev.ayuislands.accent.AccentApplicator
import dev.ayuislands.accent.AccentChangeListener
import dev.ayuislands.accent.AccentChangedTopic
import dev.ayuislands.accent.AccentContext
import dev.ayuislands.accent.AccentHex
import dev.ayuislands.accent.AccentResolver
import dev.ayuislands.accent.toolbar.actions.QuickSwitcherActionGroup
import dev.ayuislands.licensing.LicenseChecker
import dev.ayuislands.settings.mappings.AccentMappingsSettings
import dev.ayuislands.settings.mappings.AccentMappingsState
import dev.ayuislands.settings.mappings.ProjectAccentSwapService
import java.awt.Dimension
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JLabel
import javax.swing.SwingUtilities

/**
 * Main-toolbar chip that reflects the focused project's resolved accent. The
 * chip is a `JLabel` sized to match the neighbouring MainToolbar icon cells
 * (`13 × 13`, JBUI-scaled) with a `ColorIcon` that fills the full bounds (no
 * inner disc inset), so the icon does not appear small against the platform's
 * pressed / hover highlight that surrounds the cell when the popup is open.
 *
 * The popup-attached visual feedback is delegated to the platform — IntelliJ
 * paints its standard MainToolbar action-button hover / pressed background
 * around the chip cell while the popup it anchors is open. No custom focused
 * ring is drawn here; an earlier attempt at a 2-px overlay misaligned with
 * the platform highlight and was removed.
 *
 * **Lifecycle.** The chip is a pure Swing component: `addNotify` opens the
 * [com.intellij.openapi.application.ApplicationManager]-scoped
 * [MessageBusConnection] (Pattern E parent = `this` JLabel, which is also a
 * platform `Disposable` via the JBComponent contract — but the chip does NOT
 * implement [com.intellij.openapi.Disposable] explicitly, because nobody
 * registers it with [com.intellij.openapi.util.Disposer]. An earlier draft
 * exposed an `override fun dispose()` that was never invoked by the platform;
 * removing it removes the misleading API surface). `removeNotify` disconnects.
 * The 10-cycle subscribe/disconnect contract locked by
 * `QuickSwitcherWidgetLifecycleTest` runs purely against `addNotify` /
 * `removeNotify`.
 *
 * Subscribes to [AccentChangedTopic] AND [ApplicationActivationListener.TOPIC]
 * via a single per-instance [MessageBusConnection]. Mouse routes LMB to the
 * popup (with `this` passed so the popup can wire a per-popup
 * [com.intellij.openapi.ui.popup.JBPopupListener] that toggles popup-attached
 * state for any future consumers that depend on it) and RMB to the right-click
 * context menu.
 */
internal class QuickSwitcherChipComponent : JLabel() {
    private var connection: MessageBusConnection? = null

    /**
     * Owned [com.intellij.openapi.Disposable] handed to
     * [com.intellij.util.messages.MessageBus.connect] as the subscription's
     * lifetime parent. Disposed inside [removeNotify] to tear down the
     * connection's parent contract cleanly — the chip itself does NOT need to
     * be a [com.intellij.openapi.Disposable]; pulling the lifetime out into a
     * dedicated holder lets the chip stay a pure Swing component while still
     * giving the message bus a proper parent (no Application-scoped lifetime
     * leak across plugin reloads — Pattern E discipline).
     */
    private var connectionParent: com.intellij.openapi.Disposable? = null

    internal var isPopupAttached: Boolean = false
        private set

    init {
        val box = JBUI.scale(CHIP_BOX_PX)
        preferredSize = Dimension(box, box)
        // Idle (pre-resolve) icon: a neutral grey layered island so the chip
        // has a real silhouette before the first `refreshFromFocusedProject`
        // call replaces it with the project's accent. Using the layered icon
        // for the idle state too means the chip never flashes a different
        // visual shape between construction and the first refresh.
        icon = LayeredAccentIcon(JBUI.scale(CHIP_BOX_PX), AccentHex.unsafeOf(IDLE_ACCENT_HEX), pinned = false)
        toolTipText = ""
        addMouseListener(
            object : MouseAdapter() {
                override fun mousePressed(event: MouseEvent) {
                    if (!AccentContext.isQuickSwitcherActive()) return
                    when {
                        SwingUtilities.isRightMouseButton(event) -> showContextMenu(event.x, event.y)
                        SwingUtilities.isLeftMouseButton(event) -> handleLeftClick(event.x, event.y)
                    }
                }
            },
        )
    }

    /** Inner-island click → licence-gated pin toggle; outer click → existing popup. */
    private fun handleLeftClick(
        x: Int,
        y: Int,
    ) {
        val size = JBUI.scale(CHIP_BOX_PX)
        val onInner = LayeredAccentIcon.isInsideInnerIslandHitBox(x, y, size)
        if (onInner && LicenseChecker.isLicensedOrGrace()) {
            togglePin()
            return
        }
        QuickSwitcherPopup.show(this, this)
    }

    /**
     * Pin / unpin the focused project's accent and roll back the persisted
     * `projectAccents` mutation if [AccentApplicator.applyFromHexString]
     * rejects the hex or any of the resolve / apply / notify calls throw —
     * keeps the persisted store consistent with the live accent state at
     * all times. Mirrors [dev.ayuislands.accent.toolbar.actions.PinAccentAction]
     * for the write path so all three pin entry points (popup quick-action,
     * right-click context menu, chip inner click) stay consistent.
     *
     * The Pattern B `RuntimeException` catch wraps the entire toggle so
     * a single restore-pin helper handles both the rejection branch
     * (`applied == false`) and the thrown-exception branch the same way.
     */
    private fun togglePin() {
        val context = AccentContext.detectQuickSwitcher()
        if (context !is AccentContext.Ayu) return
        val variant = context.ayuVariant
        val project = AccentApplicator.resolveFocusedProject() ?: return
        val key =
            AccentResolver.projectKey(project) ?: run {
                LOG.warn("Pin toggle: projectKey null for ${project.name}")
                return
            }
        val mappings = AccentMappingsSettings.getInstance().state
        val previousPin: String? = mappings.projectAccents[key]
        try {
            val unpinPath = AccentResolver.source(project) == AccentResolver.Source.PROJECT_OVERRIDE
            if (unpinPath) {
                mappings.projectAccents.remove(key)
            } else {
                mappings.projectAccents[key] = AccentResolver.resolve(project, variant)
            }
            // Resolve target hex AFTER the mutation so the unpin path lands
            // on the (now-uncovered) language pin or global accent and the
            // pin path lands on the just-written override — `AccentResolver`
            // reads `mappings` directly.
            val targetHex = AccentResolver.resolve(project, variant)
            val applied = AccentApplicator.applyFromHexString(targetHex)
            if (applied) {
                ProjectAccentSwapService.getInstance().notifyExternalApply(targetHex)
            } else {
                LOG.warn("Pin toggle: applyFromHexString rejected hex=$targetHex; rolling back")
                restorePin(mappings, key, previousPin)
            }
        } catch (exception: RuntimeException) {
            LOG.warn("Pin toggle failed; rolling back", exception)
            restorePin(mappings, key, previousPin)
        }
    }

    /** Restore [previous] under [key] (or remove the key entry) so the persisted store
     *  matches the runtime accent after a rejected / thrown apply. */
    private fun restorePin(
        mappings: AccentMappingsState,
        key: String,
        previous: String?,
    ) {
        if (previous == null) {
            mappings.projectAccents.remove(key)
        } else {
            mappings.projectAccents[key] = previous
        }
    }

    /**
     * Toggled by the popup's [com.intellij.openapi.ui.popup.JBPopupListener] when
     * the chip is the anchor of an opened/closed popup. Repaints the chip so any
     * future consumer of `isPopupAttached` (a custom ring, a tinted icon, etc.)
     * updates in-place. Today the only visual response is the platform's own
     * MainToolbar hover/pressed highlight, which the platform manages — this
     * setter is kept for the lifecycle test contract and any later iteration
     * that wants chip-level pressed feedback.
     */
    internal fun setPopupAttached(active: Boolean) {
        if (isPopupAttached == active) return
        isPopupAttached = active
        repaint()
    }

    override fun addNotify() {
        super.addNotify()
        if (connection != null) return // re-attach idempotency
        val parent =
            com.intellij.openapi.util.Disposer
                .newDisposable("QuickSwitcherChip.connection")
        connectionParent = parent
        val conn = ApplicationManager.getApplication().messageBus.connect(parent)
        connection = conn
        // Object expression rather than SAM lambda: [AccentChangeListener] is
        // a `fun interface` for the IntelliJ MessageBus listener shape, but
        // Kotlin's SAM conversion of a value-class parameter (`AccentHex`)
        // can fail to bridge, throwing [AbstractMethodError] at fan-out time.
        // See KDoc on the interface.
        conn.subscribe(
            AccentChangedTopic.TOPIC,
            @Suppress("ObjectLiteralToLambda")
            object : AccentChangeListener {
                override fun accentChanged(
                    project: com.intellij.openapi.project.Project,
                    hex: AccentHex,
                    source: AccentResolver.Source,
                ) {
                    SwingUtilities.invokeLater { refreshFromFocusedProject() }
                }
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
        connectionParent?.let {
            com.intellij.openapi.util.Disposer
                .dispose(it)
        }
        connectionParent = null
        super.removeNotify()
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
        // Pattern B — [ActionManager.createActionPopupMenu] can throw
        // [RuntimeException] during IDE shutdown (action-group resolution
        // races) and `JPopupMenu.show` can throw on a disposed peer. Swallow
        // so the chip mouse-handler stays responsive for the next attempt.
        try {
            val menu =
                ActionManager
                    .getInstance()
                    .createActionPopupMenu(CONTEXT_MENU_PLACE, QuickSwitcherActionGroup.build())
            menu.component.show(this, x, y)
        } catch (exception: RuntimeException) {
            LOG.warn("QuickSwitcher chip context menu failed to show", exception)
        }
    }

    /**
     * Re-resolve the focused project's accent and repaint. Runs on EDT — every
     * caller is on EDT already. Pattern B catches transient [RuntimeException]
     * so the chip stays paintable with its previous icon.
     */
    fun refreshFromFocusedProject() {
        val context = AccentContext.detectQuickSwitcher() ?: return
        val project = AccentApplicator.resolveFocusedProject()
        val hex =
            try {
                AccentResolver.resolve(project, context)
            } catch (exception: RuntimeException) {
                LOG.warn("QuickSwitcher chip resolve failed", exception)
                return
            }
        val source = AccentResolver.source(project, context)
        val pinned = source == AccentResolver.Source.PROJECT_OVERRIDE
        // [AccentResolver.resolve] returns a validated `#RRGGBB` (resolver
        // produces either a stored override or `AyuIslandsSettings.getAccentForVariant`,
        // both of which are validated upstream), so `unsafeOf` is safe here.
        icon = LayeredAccentIcon(JBUI.scale(CHIP_BOX_PX), AccentHex.unsafeOf(hex), pinned = pinned)
        toolTipText = "$hex — ${AccentResolver.sourceLabel(source)}"
        repaint()
    }

    companion object {
        // Final chip dimensions: a 13 × 13 JBUI-scaled cell whose icon
        // fills the full bounds (no inner-disc inset). 13 px matches the
        // visual size of neighbouring MainToolbar icons (Add, Search,
        // overflow); larger values (14–16 px) read as oversized against them.
        internal const val CHIP_BOX_PX = 13

        // Idle accent for the pre-resolve placeholder icon. Mirage gold —
        // chosen for visibility against any LAF; first `refreshFromFocusedProject`
        // call (in `addNotify` or via an activation event) replaces it with
        // the resolved per-project accent within milliseconds of attach.
        internal const val IDLE_ACCENT_HEX = "#FFB454"

        // Action place ID for the right-click context menu.
        private const val CONTEXT_MENU_PLACE = "AyuQuickSwitcher.ContextMenu"

        private val LOG = logger<QuickSwitcherChipComponent>()
    }
}
