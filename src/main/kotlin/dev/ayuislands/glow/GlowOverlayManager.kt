package dev.ayuislands.glow

import com.intellij.openapi.Disposable
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerEvent
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.openapi.wm.ex.ToolWindowManagerListener
import dev.ayuislands.accent.AccentApplicator
import dev.ayuislands.accent.AyuVariant
import dev.ayuislands.licensing.LicenseChecker
import dev.ayuislands.settings.AyuIslandsSettings
import dev.ayuislands.settings.AyuIslandsState
import java.awt.Color
import java.awt.Component
import java.awt.Container
import java.awt.KeyboardFocusManager
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.FocusListener
import java.awt.event.HierarchyBoundsAdapter
import java.awt.event.HierarchyEvent
import java.beans.PropertyChangeListener
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JLayeredPane
import javax.swing.JTextField
import javax.swing.SwingUtilities
import javax.swing.UIManager

/**
 * Manages glow overlays for tool windows, editor, tabs, and focus rings.
 *
 * Glow rendering uses three approaches:
 * - GlowGlassPane: overlay positioned in JLayeredPane for an island glow (tool windows, editor)
 * - UIManager keys: EditorTabs underline/background colors for tab accent modes
 * - GlowFocusBorder: transient border swap on focus events for the text input glow
 *
 * GlowIslandBorder (border-based island glow) was evaluated and removed: the GlassPane approach
 * is more robust (independent of component border chains, no layout interference).
 * GlowPanel (standalone glow JPanel) was removed: preview uses GlowRenderer directly.
 * GlowPreset (named configurations) was removed: deferred feature, flat state properties suffice.
 */
@Suppress("TooManyFunctions") // Overlay lifecycle requires many small helpers
class GlowOverlayManager(
    private val project: Project,
) : Disposable {
    private val log = logger<GlowOverlayManager>()
    private val overlays = mutableMapOf<String, OverlayEntry>()
    private var activeGlowId: String? = null

    @Volatile
    private var disposed = false

    // Focus-ring glow
    private val focusListeners = mutableMapOf<JComponent, FocusListener>()

    // Global focus listener
    private var focusChangeListener: PropertyChangeListener? = null

    // Continuous glow animation (Pulse/Breathe/Reactive)
    private var animator: GlowAnimator? = null

    companion object {
        private const val EDITOR_ID = "Editor"
        private const val HOST_SEARCH_MAX_DEPTH = 6
        private const val DEFAULT_ACCENT_HEX = "#FFCC66"
        private const val TAB_ACCENT_BG_ALPHA = 50
        private const val KEY_TAB_UNDERLINE = "EditorTabs.underlinedBorderColor"
        private const val KEY_TAB_BACKGROUND = "EditorTabs.underlinedTabBackground"

        fun getInstance(project: Project): GlowOverlayManager = project.getService(GlowOverlayManager::class.java)

        private fun safeDecodeColor(hex: String): Color =
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

        if (AyuVariant.detect() == null) {
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

        val variant = AyuVariant.detect()
        val settings = AyuIslandsSettings.getInstance()
        val accentHex = if (variant != null) settings.getAccentForVariant(variant) else DEFAULT_ACCENT_HEX
        val style = GlowStyle.fromName(state.glowStyle ?: GlowStyle.SOFT.name)
        val accent = safeDecodeColor(accentHex)
        val intensity = state.getIntensityForStyle(style)

        for (window in java.awt.Window.getWindows()) {
            installFocusListenersRecursively(window, accent, style, intensity)
        }

        log.info("Focus-ring glow initialized")
    }

    private fun isTextInputComponent(component: Component): Boolean =
        component is JTextField ||
            component is JComboBox<*> ||
            (component is JComponent && component.javaClass.simpleName.contains("SearchTextField"))

    private fun installFocusListenersRecursively(
        component: Component,
        accent: Color,
        style: GlowStyle,
        intensity: Int,
    ) {
        if (isTextInputComponent(component) && component is JComponent && !focusListeners.containsKey(component)) {
            val listener = GlowFocusBorder.createFocusListener(accent, style, intensity)
            component.addFocusListener(listener)
            focusListeners[component] = listener

            // Auto-remove focus listener when the component becomes undisplayable
            component.addHierarchyListener { event ->
                val displayabilityChanged =
                    (event.changeFlags and HierarchyEvent.DISPLAYABILITY_CHANGED.toLong()) != 0L
                if (displayabilityChanged && !component.isDisplayable) {
                    component.removeFocusListener(listener)
                    focusListeners.remove(component)
                }
            }
        }
        if (component is Container) {
            for (child in component.components) {
                installFocusListenersRecursively(child, accent, style, intensity)
            }
        }
    }

    private fun findGlowHost(component: JComponent): JComponent {
        var current: Component? = component.parent
        var depth = 0
        while (current != null && depth < HOST_SEARCH_MAX_DEPTH) {
            val name = current.javaClass.name
            if (name.contains("InternalDecoratorImpl")) {
                return current as JComponent
            }
            current = current.parent
            depth++
        }
        current = component.parent
        depth = 0
        while (current != null && depth < HOST_SEARCH_MAX_DEPTH) {
            val name = current.javaClass.name
            if (name.contains("IslandHolder")) {
                return current as JComponent
            }
            current = current.parent
            depth++
        }
        return component
    }

    private fun findEditorHost(): JComponent? {
        val fileEditorManager = FileEditorManager.getInstance(project)
        val editorComponent = fileEditorManager.selectedEditor?.component ?: return null
        if (!editorComponent.isDisplayable) return null

        // Walk up to find EditorsSplitters or the editor area island container
        var current: Component? = editorComponent
        while (current != null) {
            val name = current.javaClass.name
            if (name.contains("EditorsSplitters")) {
                return current as JComponent
            }
            current = current.parent
        }
        return null
    }

    private fun findTabBarHeight(host: JComponent): Int {
        // EditorsSplitters has one child: EditorTabs (full JBTabsImpl tabbed pane).
        // EditorTabs.height = entire editor area — NOT the tab strip.
        // Walk into EditorTabs and find a TabLabel — its (y + height) is the strip height.
        val editorTabs =
            host.components.firstOrNull {
                it.javaClass.name.contains("EditorTabs")
            } as? Container ?: return 0

        for (child in editorTabs.components) {
            if (child.javaClass.name.contains("TabLabel")) {
                return child.y + child.height
            }
        }
        return 0
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
                val tabHeight = findTabBarHeight(host)
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
        val settings = AyuIslandsSettings.getInstance()
        val variant = AyuVariant.detect()
        val accentHex = if (variant != null) settings.getAccentForVariant(variant) else DEFAULT_ACCENT_HEX
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

    private fun removeOverlay(id: String) {
        val entry = overlays.remove(id) ?: return
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
        if (activeGlowId == id) activeGlowId = null
        log.info("Glow overlay removed: $id")
    }

    private fun reattachToolWindowOverlayIfNeeded(toolWindow: ToolWindow) {
        val id = toolWindow.id
        val existing = overlays[id] ?: return

        val rootPane = SwingUtilities.getRootPane(existing.host)
        if (rootPane == null || rootPane.layeredPane !== existing.layeredPane) {
            // Host moved to a different window (dock↔float transition)
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

        val host = findGlowHost(component)
        attachOverlay(id, host)
    }

    private fun attachEditorOverlayIfNeeded() {
        if (overlays.containsKey(EDITOR_ID)) return

        val state = AyuIslandsSettings.getInstance().state
        if (!state.isIslandEnabled(EDITOR_ID)) return

        val host = findEditorHost() ?: return
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

        // Dispose any previous animator (not just stop — unregisters from Disposer)
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

        val settings = AyuIslandsSettings.getInstance()
        val state = settings.state
        if (!state.glowEnabled) {
            removeAllOverlays()
            return
        }

        val variant = AyuVariant.detect()
        val accentHex = if (variant != null) settings.getAccentForVariant(variant) else DEFAULT_ACCENT_HEX
        val accent = safeDecodeColor(accentHex)
        val style = GlowStyle.fromName(state.glowStyle ?: GlowStyle.SOFT.name)

        updateOverlayStyles(state, accent, style)
        updateTabGlow(state, accent)
        updateFocusRingGlow(state, accent, style)

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

    private fun updateTabGlow(
        state: AyuIslandsState,
        accent: Color,
    ) {
        val tabMode = GlowTabMode.fromName(state.glowTabMode ?: "MINIMAL")

        when (tabMode) {
            GlowTabMode.MINIMAL -> {
                // Accent underline, no background tint
                UIManager.put(KEY_TAB_UNDERLINE, accent)
                UIManager.put(KEY_TAB_BACKGROUND, Color(0, 0, 0, 0))
            }
            GlowTabMode.FULL -> {
                // Accent underline + accent background tint
                UIManager.put(KEY_TAB_UNDERLINE, accent)
                UIManager.put(
                    KEY_TAB_BACKGROUND,
                    Color(accent.red, accent.green, accent.blue, TAB_ACCENT_BG_ALPHA),
                )
            }
            GlowTabMode.OFF -> {
                // Neutral underline, no background
                val variant = AyuVariant.detect()
                if (variant != null) {
                    UIManager.put(KEY_TAB_UNDERLINE, Color.decode(variant.neutralGray))
                }
                UIManager.put(KEY_TAB_BACKGROUND, Color(0, 0, 0, 0))
            }
        }

        // Sync underline height (ensures glow width changes propagate when sync is on)
        UIManager.put("EditorTabs.underlineHeight", AccentApplicator.resolveUnderlineHeight(state))

        repaintEditorTabs()
    }

    private fun repaintEditorTabs() {
        try {
            val fileEditorManager = FileEditorManager.getInstance(project)
            val editorComponent = fileEditorManager.selectedEditor?.component ?: return
            var current: Component? = editorComponent.parent
            while (current != null) {
                val name = current.javaClass.name
                if (name.contains("EditorTabs") || name.contains("JBEditorTabs")) {
                    current.repaint()
                    return
                }
                current = current.parent
            }
        } catch (exception: RuntimeException) {
            log.debug("Could not repaint editor tabs", exception)
        }
    }

    private fun updateFocusRingGlow(
        state: AyuIslandsState,
        accent: Color,
        style: GlowStyle,
    ) {
        removeFocusListeners()
        if (state.glowFocusRing) {
            val intensity = state.getIntensityForStyle(style)
            for (window in java.awt.Window.getWindows()) {
                installFocusListenersRecursively(window, accent, style, intensity)
            }
        }
    }

    private fun removeAllOverlays() {
        for ((_, entry) in overlays) {
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
        overlays.clear()

        // Tab accent keys are now managed by AccentApplicator (always-on, not glow-gated)
        removeFocusListeners()

        log.info("All glow overlays removed")
    }

    private fun removeFocusListeners() {
        for ((component, listener) in focusListeners) {
            component.removeFocusListener(listener)
        }
        focusListeners.clear()
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
        }
    }
}
