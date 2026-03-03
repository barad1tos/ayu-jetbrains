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
import com.intellij.openapi.wm.ToolWindowType
import com.intellij.openapi.wm.ex.ToolWindowManagerListener
import dev.ayuislands.accent.AccentElementId
import dev.ayuislands.accent.AyuVariant
import dev.ayuislands.settings.AyuIslandsSettings
import java.awt.BorderLayout
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
import javax.swing.JLayer
import javax.swing.JLayeredPane
import javax.swing.JTextField
import javax.swing.SwingUtilities

/**
 * Manages glow overlays for tool windows, editor, tabs, and focus rings.
 *
 * Glow rendering uses three approaches:
 * - GlowGlassPane: overlay positioned in JLayeredPane for island glow (tool windows, editor)
 * - GlowLayerUI: JLayer-based painting for tab glow (via tabPainter) and border glow
 * - GlowFocusBorder: transient border swap on focus events for text input glow
 *
 * GlowIslandBorder (border-based island glow) was evaluated and removed: the GlassPane approach
 * is more robust (independent of component border chains, no layout interference).
 * GlowPanel (standalone glow JPanel) was removed: preview uses GlowRenderer directly.
 * GlowPreset (named configurations) was removed: deferred feature, flat state properties suffice.
 */
class GlowOverlayManager(
    private val project: Project,
) : Disposable {
    private val log = logger<GlowOverlayManager>()
    private val overlays = mutableMapOf<String, OverlayEntry>()
    private var activeGlowId: String? = null
    private var disposed = false

    // Tab glow
    private var tabPainter: GlowTabPainter? = null
    private var tabGlowComponent: JComponent? = null
    private var tabGlowLayer: JLayer<JComponent>? = null

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
        private val instances = mutableMapOf<Project, GlowOverlayManager>()

        fun getInstance(project: Project): GlowOverlayManager =
            instances.getOrPut(project) {
                GlowOverlayManager(project).also {
                    Disposer.register(project, it)
                }
            }
    }

    private data class OverlayEntry(
        val glassPane: GlowGlassPane,
        val host: JComponent,
        val layeredPane: JLayeredPane,
    )

    fun initialize() {
        if (disposed) return

        val settings = AyuIslandsSettings.getInstance()
        if (!settings.state.glowEnabled) {
            log.info("Glow disabled, skipping overlay initialization")
            return
        }

        val variant = AyuVariant.detect()
        if (variant == null) {
            log.info("No Ayu variant detected, skipping glow initialization")
            return
        }

        // Subscribe to tool window events
        val connection = project.messageBus.connect(this)
        connection.subscribe(
            ToolWindowManagerListener.TOPIC,
            object : ToolWindowManagerListener {
                override fun stateChanged(
                    toolWindowManager: ToolWindowManager,
                    changeType: ToolWindowManagerListener.ToolWindowManagerEventType,
                ) {
                    SwingUtilities.invokeLater {
                        // Only process the active tool window instead of scanning all
                        // (startup full scan already catches all visible windows)
                        val activeId = toolWindowManager.activeToolWindowId ?: return@invokeLater
                        val tw = toolWindowManager.getToolWindow(activeId) ?: return@invokeLater
                        if (tw.isVisible) {
                            attachToolWindowOverlay(tw)
                        }
                    }
                }
            },
        )

        // Subscribe to editor selection changes (for an editor glow)
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

        // Attach to already-visible tool windows + editor
        SwingUtilities.invokeLater {
            val manager = ToolWindowManager.getInstance(project)
            for (id in manager.toolWindowIdSet) {
                val toolWindow = manager.getToolWindow(id) ?: continue
                if (toolWindow.isVisible) {
                    attachToolWindowOverlay(toolWindow)
                }
            }
            attachEditorOverlayIfNeeded()

            // Install global focus tracker
            installFocusTracker()

            // Activate a glow on the focused area
            refreshActiveGlow()
        }

        SwingUtilities.invokeLater {
            initializeTabGlow()
            initializeFocusRingGlow()
        }

        log.info("GlowOverlayManager initialized for project: ${project.name}")
    }

    private fun installFocusTracker() {
        focusChangeListener =
            PropertyChangeListener {
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

    private fun findJBEditorTabs(component: Component): Component? {
        var current: Component? = component.parent
        while (current != null) {
            if (current.javaClass.name.contains("JBEditorTabs")) return current
            current = current.parent
        }
        return null
    }

    private fun initializeTabGlow() {
        val state = AyuIslandsSettings.getInstance().state
        val tabMode = GlowTabMode.fromName(state.glowTabMode ?: "UNDERLINE")
        if (tabMode == GlowTabMode.OFF) return
        if (!state.isToggleEnabled(AccentElementId.TAB_UNDERLINES)) return

        val variant = AyuVariant.detect()
        val settings = AyuIslandsSettings.getInstance()
        val accentHex = if (variant != null) settings.getAccentForVariant(variant) else DEFAULT_ACCENT_HEX
        val style = GlowStyle.fromName(state.glowStyle ?: GlowStyle.SOFT.name)

        tabPainter =
            GlowTabPainter().apply {
                glowColor = Color.decode(accentHex)
                glowStyle = style
                this.tabMode = tabMode
                baseIntensity = state.getIntensityForStyle(style)
            }

        try {
            val fileEditorManager = FileEditorManager.getInstance(project)
            val editorComponent =
                fileEditorManager.selectedEditor?.component ?: run {
                    log.info("No selected editor, tab glow deferred")
                    return
                }

            val tabsComponent = findJBEditorTabs(editorComponent)
            if (tabsComponent == null) {
                log.info("JBEditorTabs not found in component hierarchy, tab glow skipped")
                return
            }

            val jcTabs = tabsComponent as JComponent
            tabGlowComponent = jcTabs

            val painter = tabPainter ?: return
            tabGlowLayer = wrapWithGlowLayer(jcTabs, painter)

            log.info("Tab glow initialized: mode=$tabMode")
        } catch (exception: RuntimeException) {
            log.warn("Tab glow hookup failed: ${exception.message}")
        }
    }

    private fun wrapWithGlowLayer(
        component: JComponent,
        painter: GlowTabPainter,
    ): JLayer<JComponent>? {
        val tabLayerUI =
            GlowLayerUI().apply {
                tabPainter = painter
            }
        val parent = component.parent ?: return null
        val constraints = (parent.layout as? BorderLayout)?.getConstraints(component)
        parent.remove(component)
        val layer = JLayer(component, tabLayerUI)
        parent.add(layer, constraints ?: BorderLayout.CENTER)
        parent.revalidate()
        parent.repaint()
        return layer
    }

    private fun initializeFocusRingGlow() {
        val state = AyuIslandsSettings.getInstance().state
        if (!state.glowFocusRing) return

        val variant = AyuVariant.detect()
        val settings = AyuIslandsSettings.getInstance()
        val accentHex = if (variant != null) settings.getAccentForVariant(variant) else DEFAULT_ACCENT_HEX
        val style = GlowStyle.fromName(state.glowStyle ?: GlowStyle.SOFT.name)
        val accent = Color.decode(accentHex)
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

    private fun updateOverlayBounds(
        glassPane: GlowGlassPane,
        host: JComponent,
        layeredPane: JLayeredPane,
    ) {
        if (!host.isShowing) return
        try {
            val point = SwingUtilities.convertPoint(host, 0, 0, layeredPane)
            glassPane.setBounds(point.x, point.y, host.width, host.height)
        } catch (exception: RuntimeException) {
            log.debug("Component hierarchy changed during overlay bounds update", exception)
        }
    }

    private fun attachOverlay(
        id: String,
        host: JComponent,
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
                glowColor = Color.decode(accentHex),
                glowStyle = style,
                glowIntensity = state.getIntensityForStyle(style),
                glowWidth = state.getWidthForStyle(style),
            )

        layeredPane.add(glassPane, JLayeredPane.PALETTE_LAYER)
        updateOverlayBounds(glassPane, host, layeredPane)

        host.addComponentListener(
            object : ComponentAdapter() {
                override fun componentResized(e: ComponentEvent) = updateOverlayBounds(glassPane, host, layeredPane)

                override fun componentMoved(e: ComponentEvent) = updateOverlayBounds(glassPane, host, layeredPane)
            },
        )

        host.addHierarchyBoundsListener(
            object : HierarchyBoundsAdapter() {
                override fun ancestorMoved(e: HierarchyEvent) = updateOverlayBounds(glassPane, host, layeredPane)

                override fun ancestorResized(e: HierarchyEvent) = updateOverlayBounds(glassPane, host, layeredPane)
            },
        )

        SwingUtilities.invokeLater {
            updateOverlayBounds(glassPane, host, layeredPane)
        }

        overlays[id] = OverlayEntry(glassPane, host, layeredPane)
        log.info("Glow overlay attached: $id (host: ${host.javaClass.simpleName})")
    }

    private fun attachToolWindowOverlay(toolWindow: ToolWindow) {
        val id = toolWindow.id
        if (overlays.containsKey(id)) return

        val state = AyuIslandsSettings.getInstance().state
        if (!state.isIslandEnabled(id)) return
        if (!state.glowFloatingPanels && toolWindow.type == ToolWindowType.FLOATING) return

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
        attachOverlay(EDITOR_ID, host)
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

        // Stop any previous animator
        animator?.stop()
        animator =
            GlowAnimator().also { anim ->
                anim.start(animation) { alpha ->
                    glassPane.animationAlpha = alpha
                }
            }
    }

    private fun stopAnimation(glassPane: GlowGlassPane) {
        animator?.stop()
        animator = null
        glassPane.animationAlpha = 1.0f
    }

    fun updateGlow() {
        if (disposed) return

        val settings = AyuIslandsSettings.getInstance()
        val state = settings.state
        val variant = AyuVariant.detect()
        val accentHex = if (variant != null) settings.getAccentForVariant(variant) else DEFAULT_ACCENT_HEX
        val accent = Color.decode(accentHex)
        val style = GlowStyle.fromName(state.glowStyle ?: GlowStyle.SOFT.name)

        if (!state.glowEnabled) {
            removeAllOverlays()
            return
        }

        for ((_, entry) in overlays) {
            entry.glassPane.glowColor = accent
            entry.glassPane.glowStyle = style
            entry.glassPane.glowIntensity = state.getIntensityForStyle(style)
            entry.glassPane.glowWidth = state.getWidthForStyle(style)
            entry.glassPane.invalidateRendererCache()
            entry.glassPane.repaint()
        }

        // Update tab painter
        val tabMode = GlowTabMode.fromName(state.glowTabMode ?: "UNDERLINE")
        val tabToggleEnabled = state.isToggleEnabled(AccentElementId.TAB_UNDERLINES)
        if (tabMode != GlowTabMode.OFF && tabToggleEnabled) {
            tabPainter?.apply {
                glowColor = accent
                glowStyle = style
                this.tabMode = tabMode
                baseIntensity = state.getIntensityForStyle(style)
                invalidateCache()
            }
        } else {
            tabPainter = null
        }
        tabGlowLayer?.repaint()

        if (!state.glowFocusRing) {
            removeFocusListeners()
        }

        // Restart animation on the active overlay
        val activeEntry = activeGlowId?.let { overlays[it] }
        if (activeEntry != null) {
            startAnimationIfConfigured(activeEntry.glassPane)
        }

        log.info("Glow overlays updated: style=$style, accent=$accentHex")
    }

    private fun removeAllOverlays() {
        for ((_, entry) in overlays) {
            entry.glassPane.stopAnimation()
            entry.layeredPane.remove(entry.glassPane)
            entry.layeredPane.repaint(
                entry.glassPane.x,
                entry.glassPane.y,
                entry.glassPane.width,
                entry.glassPane.height,
            )
        }
        overlays.clear()

        tabGlowLayer?.let { layer ->
            val view = layer.view
            val parent = layer.parent
            if (parent != null && view != null) {
                try {
                    val constraints = (parent.layout as? BorderLayout)?.getConstraints(layer)
                    parent.remove(layer)
                    parent.add(view, constraints ?: BorderLayout.CENTER)
                    parent.revalidate()
                    parent.repaint()
                } catch (exception: RuntimeException) {
                    log.warn("Failed to remove tab glow layer: ${exception.message}")
                }
            }
        }
        tabGlowLayer = null
        tabGlowComponent = null
        tabPainter = null

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

        animator?.stop()
        animator = null

        focusChangeListener?.let {
            KeyboardFocusManager
                .getCurrentKeyboardFocusManager()
                .removePropertyChangeListener("permanentFocusOwner", it)
        }
        focusChangeListener = null

        removeAllOverlays()
        instances.remove(project)
    }
}
