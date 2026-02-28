package dev.ayuislands.glow

import com.intellij.openapi.Disposable
import com.intellij.openapi.diagnostic.logger
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
import java.awt.Container
import javax.swing.JComponent
import javax.swing.JLayer
import javax.swing.SwingUtilities

class GlowOverlayManager(private val project: Project) : Disposable {

    private val log = logger<GlowOverlayManager>()
    private val overlays = mutableMapOf<String, OverlayEntry>()
    private var activeToolWindowId: String? = null
    private var disposed = false

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

        log.info("GlowOverlayManager initialized for project: ${project.name}")
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
        log.info("All glow overlays removed")
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
