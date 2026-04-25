package dev.ayuislands.glow

import com.intellij.openapi.Disposable
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerEvent
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.openapi.wm.ex.ToolWindowManagerListener
import dev.ayuislands.accent.AccentResolver
import dev.ayuislands.accent.AyuVariant
import dev.ayuislands.licensing.LicenseChecker
import dev.ayuislands.settings.AyuIslandsSettings
import dev.ayuislands.settings.AyuIslandsState
import java.awt.Color
import java.awt.Component
import java.awt.KeyboardFocusManager
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.HierarchyBoundsAdapter
import java.awt.event.HierarchyEvent
import java.beans.PropertyChangeListener
import javax.swing.JComponent
import javax.swing.JLayeredPane
import javax.swing.SwingUtilities

/** Manages glow overlays for tool windows, editor, tabs, and focus rings. */
class GlowOverlayManager(
    private val project: Project,
) : Disposable {
    private val log = logger<GlowOverlayManager>()
    private val overlays = mutableMapOf<String, OverlayEntry>()
    private var activeGlowId: String? = null

    @Volatile
    private var disposed = false

    private val focusRingManager = FocusRingManager()

    // Global focus listener
    private var focusChangeListener: PropertyChangeListener? = null

    // Continuous glow animation (Pulse/Breathe/Reactive)
    private var animator: GlowAnimator? = null

    companion object {
        private const val EDITOR_ID = "Editor"
        private const val DEFAULT_ACCENT_HEX = "#FFCC66"

        fun getInstance(project: Project): GlowOverlayManager = project.getService(GlowOverlayManager::class.java)

        fun syncGlowForAllProjects() {
            for (project in ProjectManager.getInstance().openProjects) {
                try {
                    getInstance(project).updateGlow()
                } catch (exception: RuntimeException) {
                    logger<GlowOverlayManager>().warn(
                        "Failed to sync glow for project ${project.name}",
                        exception,
                    )
                }
            }
        }

        fun safeDecodeColor(hex: String): Color =
            try {
                Color.decode(hex)
            } catch (_: NumberFormatException) {
                Color.decode(DEFAULT_ACCENT_HEX)
            }
    }

    private data class OverlayEntry(
        val glassPane: GlowGlassPane,
        val host: JComponent,
        val layeredPane: JLayeredPane,
        val componentListener: ComponentAdapter? = null,
        val hierarchyBoundsListener: HierarchyBoundsAdapter? = null,
    )

    private var messageBusConnected = false

    fun initialize() {
        if (disposed) return
        if (!LicenseChecker.isLicensedOrGrace()) return

        val settings = AyuIslandsSettings.getInstance()
        if (!settings.state.glowEnabled) {
            log.info("Glow disabled, skipping overlay initialization")
            return
        }

        if (!AyuVariant.isAyuActive()) {
            log.info("No Ayu variant detected, skipping glow initialization")
            return
        }

        if (messageBusConnected) {
            reattachOverlays()
            log.info("GlowOverlayManager re-initialized for project: ${project.name}")
            return
        }
        messageBusConnected = true

        subscribeToMessageBus()

        SwingUtilities.invokeLater {
            attachVisibleToolWindowOverlays()
            attachEditorOverlayIfNeeded()
            installFocusTracker()
            refreshActiveGlow()
            initializeFocusRingGlow()
        }

        log.info("GlowOverlayManager initialized for project: ${project.name}")
    }

    private fun reattachOverlays() {
        SwingUtilities.invokeLater {
            attachVisibleToolWindowOverlays()
            attachEditorOverlayIfNeeded()
            refreshActiveGlow()
        }
    }

    private fun attachVisibleToolWindowOverlays() {
        val manager = ToolWindowManager.getInstance(project)
        for (id in manager.toolWindowIdSet) {
            val toolWindow = manager.getToolWindow(id) ?: continue
            if (toolWindow.isVisible) {
                attachToolWindowOverlay(toolWindow)
            }
        }
    }

    private fun subscribeToMessageBus() {
        val connection = project.messageBus.connect(this)
        connection.subscribe(
            ToolWindowManagerListener.TOPIC,
            object : ToolWindowManagerListener {
                override fun stateChanged(
                    toolWindowManager: ToolWindowManager,
                    changeType: ToolWindowManagerListener.ToolWindowManagerEventType,
                ) {
                    SwingUtilities.invokeLater {
                        val activeId = toolWindowManager.activeToolWindowId ?: return@invokeLater
                        val tw = toolWindowManager.getToolWindow(activeId) ?: return@invokeLater
                        if (tw.isVisible) {
                            reattachToolWindowOverlayIfNeeded(tw)
                            attachToolWindowOverlay(tw)
                        }
                    }
                }
            },
        )

        connection.subscribe(
            FileEditorManagerListener.FILE_EDITOR_MANAGER,
            object : FileEditorManagerListener {
                override fun selectionChanged(event: FileEditorManagerEvent) {
                    SwingUtilities.invokeLater {
                        attachEditorOverlayIfNeeded()
                    }
                }
            },
        )
    }

    private fun installFocusTracker() {
        if (disposed) return
        focusChangeListener =
            PropertyChangeListener {
                if (disposed) return@PropertyChangeListener
                SwingUtilities.invokeLater {
                    if (!disposed) refreshActiveGlow()
                }
            }
        KeyboardFocusManager
            .getCurrentKeyboardFocusManager()
            .addPropertyChangeListener("permanentFocusOwner", focusChangeListener)
    }

    private fun refreshActiveGlow() {
        val focusOwner = KeyboardFocusManager.getCurrentKeyboardFocusManager().permanentFocusOwner
        val newActiveId = if (focusOwner != null) findOverlayForComponent(focusOwner) else null

        if (newActiveId != activeGlowId) {
            activeGlowId?.let { deactivateGlow(it) }
            newActiveId?.let { activateGlow(it) }
            activeGlowId = newActiveId
        }
    }

    private fun findOverlayForComponent(component: Component): String? {
        var current: Component? = component
        while (current != null) {
            for ((id, entry) in overlays) {
                if (current === entry.host) return id
            }
            current = current.parent
        }
        return null
    }

    private fun initializeFocusRingGlow() {
        val state = AyuIslandsSettings.getInstance().state
        if (!state.glowFocusRing) return

        // Pattern A — log-once gate. AyuVariant.detect() returns null only when
        // a non-Ayu LAF is active; without the diagnostic, a user reporting
        // "focus ring glow stopped working after theme tweak" hands us logs
        // with no breadcrumb of which guard fired.
        val variant =
            AyuVariant.detect() ?: run {
                log.debug("AyuVariant.detect() returned null in initializeFocusRingGlow, skipping focus-ring glow")
                return
            }
        val accentHex = AccentResolver.resolve(project, variant)
        val style = GlowStyle.fromName(state.glowStyle ?: GlowStyle.SOFT.name)
        val accent = safeDecodeColor(accentHex)
        val intensity = state.getIntensityForStyle(style)

        focusRingManager.initializeFocusRingGlow(accent, style, intensity)
    }

    private fun updateOverlayBounds(
        glassPane: GlowGlassPane,
        host: JComponent,
        layeredPane: JLayeredPane,
    ) {
        if (!host.isShowing) return
        try {
            val point = SwingUtilities.convertPoint(host, 0, 0, layeredPane)
            if (glassPane.isEditorOverlay) {
                val tabHeight = EditorTabGeometry.calculateTabStripHeight(host)
                glassPane.setBounds(point.x, point.y + tabHeight, host.width, host.height - tabHeight)
            } else {
                glassPane.setBounds(point.x, point.y, host.width, host.height)
            }
        } catch (exception: RuntimeException) {
            log.debug("Component hierarchy changed during overlay bounds update", exception)
        }
    }

    private fun attachOverlay(
        id: String,
        host: JComponent,
        isEditorOverlay: Boolean = false,
    ) {
        if (overlays.containsKey(id)) return
        if (host.width == 0 || host.height == 0) return

        val rootPane = SwingUtilities.getRootPane(host) ?: return
        val layeredPane = rootPane.layeredPane

        val state = AyuIslandsSettings.getInstance().state
        // Pattern A — log-once gate. attachOverlay fires from message-bus
        // callbacks (tool-window state change, editor selection); a silent
        // skip on a non-Ayu LAF leaves the overlay un-attached without any
        // trace in idea.log.
        val variant =
            AyuVariant.detect() ?: run {
                log.debug("AyuVariant.detect() returned null in attachOverlay($id), skipping overlay attach")
                return
            }
        val accentHex = AccentResolver.resolve(project, variant)
        val style = GlowStyle.fromName(state.glowStyle ?: GlowStyle.SOFT.name)

        val glassPane =
            GlowGlassPane(
                glowColor = safeDecodeColor(accentHex),
                glowStyle = style,
                glowIntensity = state.getIntensityForStyle(style),
                glowWidth = state.getWidthForStyle(style),
                isEditorOverlay = isEditorOverlay,
            )

        layeredPane.add(glassPane, JLayeredPane.PALETTE_LAYER)
        updateOverlayBounds(glassPane, host, layeredPane)

        val compListener =
            object : ComponentAdapter() {
                override fun componentResized(e: ComponentEvent) = updateOverlayBounds(glassPane, host, layeredPane)

                override fun componentMoved(e: ComponentEvent) = updateOverlayBounds(glassPane, host, layeredPane)
            }
        host.addComponentListener(compListener)

        val boundsListener =
            object : HierarchyBoundsAdapter() {
                override fun ancestorMoved(e: HierarchyEvent) = updateOverlayBounds(glassPane, host, layeredPane)

                override fun ancestorResized(e: HierarchyEvent) = updateOverlayBounds(glassPane, host, layeredPane)
            }
        host.addHierarchyBoundsListener(boundsListener)

        SwingUtilities.invokeLater {
            updateOverlayBounds(glassPane, host, layeredPane)
        }

        overlays[id] = OverlayEntry(glassPane, host, layeredPane, compListener, boundsListener)
        log.info("Glow overlay attached: $id (host: ${host.javaClass.simpleName})")
    }

    private fun detachOverlayEntry(entry: OverlayEntry) {
        entry.glassPane.stopAnimation()
        entry.componentListener?.let { entry.host.removeComponentListener(it) }
        entry.hierarchyBoundsListener?.let { entry.host.removeHierarchyBoundsListener(it) }
        entry.layeredPane.remove(entry.glassPane)
        entry.layeredPane.repaint(
            entry.glassPane.x,
            entry.glassPane.y,
            entry.glassPane.width,
            entry.glassPane.height,
        )
    }

    private fun removeOverlay(id: String) {
        val entry = overlays.remove(id) ?: return
        detachOverlayEntry(entry)
        if (activeGlowId == id) activeGlowId = null
        log.info("Glow overlay removed: $id")
    }

    private fun reattachToolWindowOverlayIfNeeded(toolWindow: ToolWindow) {
        val id = toolWindow.id
        val existing = overlays[id] ?: return

        val rootPane = SwingUtilities.getRootPane(existing.host)
        if (rootPane == null || rootPane.layeredPane !== existing.layeredPane) {
            removeOverlay(id)
            attachToolWindowOverlay(toolWindow)
        }
    }

    private fun attachToolWindowOverlay(toolWindow: ToolWindow) {
        val id = toolWindow.id
        if (overlays.containsKey(id)) return

        val state = AyuIslandsSettings.getInstance().state
        if (!state.isIslandEnabled(id)) return

        val component = toolWindow.component
        if (!component.isDisplayable) return

        val host = ComponentHierarchyUtils.findGlowHost(component)
        attachOverlay(id, host)
    }

    private fun attachEditorOverlayIfNeeded() {
        if (overlays.containsKey(EDITOR_ID)) return

        val state = AyuIslandsSettings.getInstance().state
        if (!state.isIslandEnabled(EDITOR_ID)) return

        val editorComponent = FileEditorManager.getInstance(project).selectedEditor?.component ?: return
        if (!editorComponent.isDisplayable) return
        val host = ComponentHierarchyUtils.findEditorHost(editorComponent) ?: return
        attachOverlay(EDITOR_ID, host, isEditorOverlay = true)
    }

    private fun activateGlow(id: String) {
        val entry = overlays[id] ?: return
        entry.glassPane.startFadeIn()
        startAnimationIfConfigured(entry.glassPane)
        log.info("Glow activated: $id")
    }

    private fun deactivateGlow(id: String) {
        val entry = overlays[id] ?: return
        stopAnimation(entry.glassPane)
        entry.glassPane.startFadeOut()
    }

    private fun startAnimationIfConfigured(glassPane: GlowGlassPane) {
        val state = AyuIslandsSettings.getInstance().state
        val animation = GlowAnimation.fromName(state.glowAnimation ?: GlowAnimation.NONE.name)
        if (animation == GlowAnimation.NONE) {
            glassPane.animationAlpha = 1.0f
            return
        }

        animator?.let { Disposer.dispose(it) }
        animator =
            GlowAnimator().also { anim ->
                Disposer.register(this, anim)
                anim.start(
                    animation,
                    isVisible = { glassPane.isShowing },
                ) { alpha ->
                    glassPane.animationAlpha = alpha
                }
            }
    }

    private fun stopAnimation(glassPane: GlowGlassPane) {
        animator?.let { Disposer.dispose(it) }
        animator = null
        glassPane.animationAlpha = 1.0f
    }

    fun updateGlow() {
        if (disposed) return
        if (!AyuVariant.isAyuActive()) {
            removeAllOverlays()
            return
        }

        val settings = AyuIslandsSettings.getInstance()
        val state = settings.state
        if (!state.glowEnabled) {
            removeAllOverlays()
            return
        }

        // Pattern A — log-once gate. The isAyuActive() guard above already
        // disposed overlays when the LAF is non-Ayu, so reaching here with a
        // null detect() is the rare race window between guard and detect();
        // surface a DEBUG breadcrumb instead of falling through silently.
        val variant =
            AyuVariant.detect() ?: run {
                log.debug("AyuVariant.detect() returned null in updateGlow after isAyuActive guard, skipping refresh")
                return
            }
        val accentHex = AccentResolver.resolve(project, variant)
        val accent = safeDecodeColor(accentHex)
        val style = GlowStyle.fromName(state.glowStyle ?: GlowStyle.SOFT.name)

        updateOverlayStyles(state, accent, style)
        repaintTabs()

        val intensity = state.getIntensityForStyle(style)
        focusRingManager.updateFocusRingGlow(accent, style, intensity, state.glowFocusRing)

        val activeEntry = activeGlowId?.let { overlays[it] }
        if (activeEntry != null) {
            startAnimationIfConfigured(activeEntry.glassPane)
        }

        log.info("Glow overlays updated: style=$style, accent=$accentHex")
    }

    private fun updateOverlayStyles(
        state: AyuIslandsState,
        accent: Color,
        style: GlowStyle,
    ) {
        for ((_, entry) in overlays) {
            entry.glassPane.glowColor = accent
            entry.glassPane.glowStyle = style
            entry.glassPane.glowIntensity = state.getIntensityForStyle(style)
            entry.glassPane.glowWidth = state.getWidthForStyle(style)
            entry.glassPane.invalidateRendererCache()
            entry.glassPane.repaint()
        }
    }

    /**
     * Repaints editor tabs for THIS project only.
     *
     * Previously this also wrote `EditorTabs.underlinedBorderColor`, `KEY_TAB_BACKGROUND`,
     * and `EditorTabs.underlineHeight` to `UIManager`. That was a subtle race: `UIManager`
     * is a single JVM-wide table, and `syncGlowForAllProjects` iterates every open project,
     * so the last project's accent ended up in `UIManager` regardless of which window the
     * user was actually looking at — clearly wrong when one project has a per-project
     * override and another doesn't (tabs show the loser's color while glow, scoped to each
     * project's overlay, correctly shows the right one).
     *
     * AccentApplicator.apply writes those UIManager keys exactly once, for the focused
     * project's resolved accent, and the focus-swap service re-applies on WINDOW_ACTIVATED.
     * Tab appearance now follows the resolved accent consistently; this method only nudges
     * Swing to repaint the tabs so the glow-overlay bounds recalculate for this project.
     */
    private fun repaintTabs() {
        EditorTabGeometry.repaintEditorTabs(project)
    }

    private fun removeAllOverlays() {
        for ((_, entry) in overlays) {
            detachOverlayEntry(entry)
        }
        overlays.clear()
        focusRingManager.removeFocusListeners()
        log.info("All glow overlays removed")
    }

    override fun dispose() {
        disposed = true

        animator?.let { Disposer.dispose(it) }
        animator = null

        focusChangeListener?.let {
            KeyboardFocusManager
                .getCurrentKeyboardFocusManager()
                .removePropertyChangeListener("permanentFocusOwner", it)
        }
        focusChangeListener = null

        SwingUtilities.invokeLater {
            removeAllOverlays()
            focusRingManager.dispose()
        }
    }
}
