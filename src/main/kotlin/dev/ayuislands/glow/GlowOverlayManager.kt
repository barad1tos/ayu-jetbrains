package dev.ayuislands.glow

import com.intellij.openapi.Disposable
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.openapi.wm.ex.ToolWindowManagerListener
import com.intellij.openapi.wm.ToolWindowType
import dev.ayuislands.accent.AyuVariant
import dev.ayuislands.settings.AyuIslandsSettings
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Container
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Rectangle
import java.awt.event.FocusListener
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JLayer
import javax.swing.JTextField
import javax.swing.SwingUtilities
import javax.swing.plaf.LayerUI

class GlowOverlayManager(private val project: Project) : Disposable {

    private val log = logger<GlowOverlayManager>()
    private val overlays = mutableMapOf<String, OverlayEntry>()
    private var activeToolWindowId: String? = null
    private var disposed = false

    // Tab glow
    private var tabPainter: GlowTabPainter? = null
    private var tabGlowLayer: JLayer<JComponent>? = null

    // Focus-ring glow
    private val focusListeners = mutableMapOf<JComponent, FocusListener>()

    private data class OverlayEntry(
        val layerUI: GlowLayerUI,
        val layer: JLayer<JComponent>?,
        val originalParent: Container?,
    )

    fun initialize() {
        if (disposed) return

        val settings = AyuIslandsSettings.getInstance()
        if (!settings.state.glowEnabled) {
            log.info("Glow disabled, skipping overlay initialization")
            return
        }

        // Glow requires an accent color -- skip if no Ayu variant is active
        val variant = AyuVariant.detect()
        if (variant == null) {
            log.info("No Ayu variant detected, skipping glow initialization")
            return
        }

        // Subscribe to tool window events via message bus
        val connection = project.messageBus.connect(this)
        connection.subscribe(
            ToolWindowManagerListener.TOPIC,
            object : ToolWindowManagerListener {
                override fun toolWindowShown(id: String, toolWindow: ToolWindow) {
                    SwingUtilities.invokeLater {
                        attachOverlayIfNeeded(toolWindow)
                    }
                }

                override fun stateChanged(
                    toolWindowManager: ToolWindowManager,
                    changeType: ToolWindowManagerListener.ToolWindowManagerEventType,
                ) {
                    SwingUtilities.invokeLater {
                        val newActiveId = toolWindowManager.activeToolWindowId
                        if (newActiveId != activeToolWindowId) {
                            onFocusChanged(activeToolWindowId, newActiveId)
                            activeToolWindowId = newActiveId
                        }
                    }
                }
            },
        )

        // Attach to already-visible tool windows
        SwingUtilities.invokeLater {
            val manager = ToolWindowManager.getInstance(project)
            for (id in manager.toolWindowIdSet) {
                val toolWindow = manager.getToolWindow(id) ?: continue
                if (toolWindow.isVisible) {
                    attachOverlayIfNeeded(toolWindow)
                }
            }
            activeToolWindowId = manager.activeToolWindowId
            // Activate glow on the currently active tool window
            activeToolWindowId?.let { activateGlow(it) }
        }

        // Initialize tab glow and focus-ring glow
        SwingUtilities.invokeLater {
            initializeTabGlow()
            initializeFocusRingGlow()
        }

        log.info("GlowOverlayManager initialized for project: ${project.name}")
    }

    private fun findAncestorByClassName(component: Component, className: String): Component? {
        var current: Component? = component.parent
        while (current != null) {
            if (current.javaClass.name.contains(className)) return current
            current = current.parent
        }
        return null
    }

    private fun initializeTabGlow() {
        val state = AyuIslandsSettings.getInstance().state
        val tabMode = GlowTabMode.fromName(state.glowTabMode ?: "UNDERLINE")
        if (tabMode == GlowTabMode.OFF) return

        val variant = AyuVariant.detect()
        val settings = AyuIslandsSettings.getInstance()
        val accentHex = if (variant != null) settings.getAccentForVariant(variant) else "#FFCC66"
        val style = GlowStyle.fromName(state.glowStyle ?: GlowStyle.SOFT.name)

        tabPainter = GlowTabPainter().apply {
            glowColor = Color.decode(accentHex)
            glowStyle = style
            this.tabMode = tabMode
            baseIntensity = state.getIntensityForStyle(style)
        }

        // Hook tab painter into editor tabs via JLayer wrapping.
        // Find the JBEditorTabs component from FileEditorManager's selected editor,
        // then wrap it with a JLayer whose LayerUI delegates to paintTabGlow().
        try {
            val fileEditorManager = FileEditorManager.getInstance(project)
            val editorComponent = fileEditorManager.selectedEditor?.component ?: run {
                log.info("No selected editor, tab glow deferred")
                return
            }

            // Walk up from editor to find the JBEditorTabs component
            val tabsComponent = findAncestorByClassName(editorComponent, "JBEditorTabs")
            if (tabsComponent == null) {
                log.info("JBEditorTabs not found in component hierarchy, tab glow skipped")
                return
            }

            val painter = tabPainter ?: return
            val tabLayerUI = object : LayerUI<JComponent>() {
                override fun paint(graphics: Graphics, component: JComponent) {
                    super.paint(graphics, component)
                    val tabs = (component as? JLayer<*>)?.view ?: return
                    // Find selected tab bounds from the JBEditorTabs component via reflection
                    try {
                        val infoMethod = tabs.javaClass.getMethod("getSelectedInfo")
                        val tabInfo = infoMethod.invoke(tabs) ?: return
                        val labelMethod = tabInfo.javaClass.getMethod("getTabLabel")
                        val label = labelMethod.invoke(tabInfo) as? JComponent ?: return
                        val tabBounds = label.bounds
                        val g2 = graphics.create() as Graphics2D
                        try {
                            g2.translate(tabBounds.x, tabBounds.y)
                            painter.paintTabGlow(g2, Rectangle(0, 0, tabBounds.width, tabBounds.height))
                        } finally {
                            g2.dispose()
                        }
                    } catch (exception: Exception) {
                        // Reflection may fail across IDE versions -- log once and degrade gracefully
                        log.warn("Failed to paint tab glow: ${exception.message}")
                    }
                }
            }

            val jcTabs = tabsComponent as JComponent
            val layer = JLayer(jcTabs, tabLayerUI)
            val parent = tabsComponent.parent
            if (parent != null) {
                val constraints = (parent.layout as? BorderLayout)?.getConstraints(tabsComponent)
                parent.remove(tabsComponent)
                parent.add(layer, constraints ?: BorderLayout.CENTER)
                parent.revalidate()
                parent.repaint()
                tabGlowLayer = layer
            }
        } catch (exception: Exception) {
            log.warn("Tab glow hookup failed: ${exception.message}")
        }

        log.info("Tab glow initialized: mode=$tabMode")
    }

    private fun initializeFocusRingGlow() {
        val state = AyuIslandsSettings.getInstance().state
        if (!state.glowFocusRing) return

        val variant = AyuVariant.detect()
        val settings = AyuIslandsSettings.getInstance()
        val accentHex = if (variant != null) settings.getAccentForVariant(variant) else "#FFCC66"
        val style = GlowStyle.fromName(state.glowStyle ?: GlowStyle.SOFT.name)
        val accent = Color.decode(accentHex)
        val intensity = state.getIntensityForStyle(style)

        // Install focus listeners on text fields and combo boxes in visible IDE windows
        for (window in java.awt.Window.getWindows()) {
            installFocusListenersRecursively(window, accent, style, intensity)
        }

        log.info("Focus-ring glow initialized")
    }

    private fun installFocusListenersRecursively(
        component: Component,
        accent: Color,
        style: GlowStyle,
        intensity: Int,
    ) {
        if (component is JTextField ||
            component is JComboBox<*> ||
            (component is JComponent && component.javaClass.simpleName.contains("SearchTextField"))
        ) {
            val jComponent = component as JComponent
            if (!focusListeners.containsKey(jComponent)) {
                val listener = GlowFocusBorder.createFocusListener(accent, style, intensity)
                jComponent.addFocusListener(listener)
                focusListeners[jComponent] = listener
            }
        }
        if (component is Container) {
            for (child in component.components) {
                installFocusListenersRecursively(child, accent, style, intensity)
            }
        }
    }

    private fun attachOverlayIfNeeded(toolWindow: ToolWindow) {
        val id = toolWindow.id
        if (overlays.containsKey(id)) return

        val state = AyuIslandsSettings.getInstance().state
        if (!state.isIslandEnabled(id)) return

        // Skip floating (undocked) tool windows unless user opted in
        if (!state.glowFloatingPanels && toolWindow.type == ToolWindowType.FLOATING) return

        val component = toolWindow.component
        if (!component.isDisplayable) return

        val layerUI = createConfiguredLayerUI()

        // Try JLayer wrapping. If it causes issues (layout conflicts),
        // the fallback is to skip this tool window and log a warning.
        // A production fallback to AbstractBorder can be added if JLayer
        // proves problematic across IDE versions.
        try {
            val layer = JLayer(component, layerUI)
            val parent = component.parent
            if (parent != null) {
                val constraints = (parent.layout as? BorderLayout)?.getConstraints(component)
                parent.remove(component)
                parent.add(layer, constraints ?: BorderLayout.CENTER)
                parent.revalidate()
                parent.repaint()
                overlays[id] = OverlayEntry(layerUI, layer, parent)
                log.info("Glow overlay attached to tool window: $id")
            } else {
                overlays[id] = OverlayEntry(layerUI, null, null)
                log.info("Glow overlay created (no parent) for tool window: $id")
            }
        } catch (exception: Exception) {
            log.warn("Failed to attach glow overlay to tool window $id: ${exception.message}")
        }
    }

    private fun createConfiguredLayerUI(): GlowLayerUI {
        val settings = AyuIslandsSettings.getInstance()
        val state = settings.state
        val variant = AyuVariant.detect()
        val accentHex = if (variant != null) settings.getAccentForVariant(variant) else "#FFCC66"

        val style = GlowStyle.fromName(state.glowStyle ?: GlowStyle.SOFT.name)

        return GlowLayerUI().apply {
            glowColor = Color.decode(accentHex)
            glowStyle = style
            glowIntensity = state.getIntensityForStyle(style)
            glowWidth = state.getWidthForStyle(style)
            isActive = false
        }
    }

    private fun onFocusChanged(previousId: String?, newId: String?) {
        previousId?.let { deactivateGlow(it) }
        newId?.let { activateGlow(it) }
    }

    private fun activateGlow(toolWindowId: String) {
        val entry = overlays[toolWindowId] ?: return
        entry.layerUI.isActive = true
        entry.layerUI.startFadeIn()
    }

    private fun deactivateGlow(toolWindowId: String) {
        val entry = overlays[toolWindowId] ?: return
        entry.layerUI.isActive = false
        entry.layerUI.startFadeOut()
    }

    fun updateGlow() {
        if (disposed) return

        val settings = AyuIslandsSettings.getInstance()
        val state = settings.state
        val variant = AyuVariant.detect()
        val accentHex = if (variant != null) settings.getAccentForVariant(variant) else "#FFCC66"
        val accent = Color.decode(accentHex)
        val style = GlowStyle.fromName(state.glowStyle ?: GlowStyle.SOFT.name)

        if (!state.glowEnabled) {
            removeAllOverlays()
            return
        }

        // Update existing overlays with new settings
        for ((_, entry) in overlays) {
            entry.layerUI.glowColor = accent
            entry.layerUI.glowStyle = style
            entry.layerUI.glowIntensity = state.getIntensityForStyle(style)
            entry.layerUI.glowWidth = state.getWidthForStyle(style)
        }

        // Invalidate renderer caches so next paint picks up new settings
        for ((_, entry) in overlays) {
            entry.layer?.repaint()
        }

        // Update tab painter
        val tabMode = GlowTabMode.fromName(state.glowTabMode ?: "UNDERLINE")
        if (state.glowEnabled && tabMode != GlowTabMode.OFF) {
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

        // Update focus-ring state
        if (!state.glowEnabled || !state.glowFocusRing) {
            removeFocusListeners()
        }

        log.info("Glow overlays updated: style=$style, accent=$accentHex")
    }

    private fun removeAllOverlays() {
        for ((id, entry) in overlays) {
            entry.layerUI.stopAnimation()
            // Restore original component hierarchy if possible
            if (entry.layer != null && entry.originalParent != null) {
                try {
                    val component = entry.layer.view
                    val constraints = (entry.originalParent.layout as? BorderLayout)
                        ?.getConstraints(entry.layer)
                    entry.originalParent.remove(entry.layer)
                    entry.originalParent.add(component, constraints ?: BorderLayout.CENTER)
                    entry.originalParent.revalidate()
                    entry.originalParent.repaint()
                } catch (exception: Exception) {
                    log.warn("Failed to restore tool window $id: ${exception.message}")
                }
            }
        }
        overlays.clear()

        // Remove tab glow JLayer -- restore original component hierarchy
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
                } catch (exception: Exception) {
                    log.warn("Failed to remove tab glow layer: ${exception.message}")
                }
            }
        }
        tabGlowLayer = null

        // Clean up tab painter
        tabPainter = null

        // Clean up focus listeners
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
        removeAllOverlays()
        instances.remove(project)
    }

    companion object {
        private val instances = mutableMapOf<Project, GlowOverlayManager>()

        fun getInstance(project: Project): GlowOverlayManager {
            return instances.getOrPut(project) {
                GlowOverlayManager(project).also {
                    Disposer.register(project, it)
                }
            }
        }

        fun removeInstance(project: Project) {
            instances.remove(project)
        }
    }
}
