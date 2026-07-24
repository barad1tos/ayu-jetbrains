package dev.ayuislands.glow

import com.intellij.ide.PowerSaveMode
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationActivationListener
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerEvent
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.IdeFrame
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.openapi.wm.ex.ToolWindowManagerListener
import dev.ayuislands.accent.AccentChangeListener
import dev.ayuislands.accent.AccentChangedTopic
import dev.ayuislands.accent.AccentContext
import dev.ayuislands.accent.AccentHex
import dev.ayuislands.accent.AccentResolver
import dev.ayuislands.glow.waveform.RouteEvent
import dev.ayuislands.glow.waveform.WaveformConfig
import dev.ayuislands.glow.waveform.WaveformEdge
import dev.ayuislands.glow.waveform.WaveformMovement
import dev.ayuislands.licensing.LicenseChecker
import dev.ayuislands.settings.AyuIslandsSettings
import dev.ayuislands.settings.AyuIslandsState
import java.awt.Color
import java.awt.Component
import java.awt.KeyboardFocusManager
import java.awt.Rectangle
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.HierarchyBoundsAdapter
import java.awt.event.HierarchyEvent
import java.beans.PropertyChangeListener
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.JComponent
import javax.swing.JLayeredPane
import javax.swing.SwingUtilities

/** Manages glow overlays for tool windows, editor, tabs, and focus rings. */
@Service(Service.Level.PROJECT)
class GlowOverlayManager(
    private val project: Project,
) : Disposable {
    private val log = logger<GlowOverlayManager>()
    private val overlays = mutableMapOf<String, OverlayEntry>()
    private var activeGlowId: String? = null
    private val routeController =
        RouteController(
            project = project,
            overlays = {
                overlays.map { (id, entry) ->
                    RouteOverlay(id, entry.glassPane, entry.host, entry.layeredPane)
                }
            },
            focusedSurfaceId = { activeGlowId },
            state = { AyuIslandsSettings.getInstance().state },
            onFailure = { exception -> log.warn("Chaotic waveform route render failed", exception) },
        )

    @Volatile
    private var disposed = false

    private val focusRingManager = FocusRingManager()

    // Global focus listener
    private var focusChangeListener: PropertyChangeListener? = null

    private val activationListener =
        object : ApplicationActivationListener {
            override fun applicationActivated(ideFrame: IdeFrame) {
                syncWaveform(isActive = true)
            }

            override fun applicationDeactivated(ideFrame: IdeFrame) {
                syncWaveform(isActive = false)
            }

            private fun syncWaveform(isActive: Boolean) {
                val update =
                    Runnable {
                        if (disposed) return@Runnable
                        if (routeController.handle(RouteEvent.ApplicationActiveChanged(isActive))) {
                            return@Runnable
                        }
                        val glassPane = activeGlowId?.let { overlays[it]?.glassPane } ?: return@Runnable
                        if (!glassPane.isWaveform) return@Runnable

                        if (isActive) {
                            glassPane.activateWaveform(PowerSaveMode.isEnabled())
                        } else {
                            glassPane.deactivateWaveform()
                        }
                    }
                if (SwingUtilities.isEventDispatchThread()) update.run() else SwingUtilities.invokeLater(update)
            }
        }

    // Continuous glow animation (Pulse/Breathe/Reactive)
    private var animator: GlowAnimator? = null

    companion object {
        private const val EDITOR_ID = "Editor"
        private const val DEFAULT_ACCENT_HEX = "#FFCC66"
        private val hubFailureLogged = AtomicBoolean(false)

        fun getInstance(project: Project): GlowOverlayManager = project.getService(GlowOverlayManager::class.java)

        fun syncGlowForAllProjects() {
            try {
                KeystrokeHub.getInstance().invalidateLicenseGate()
            } catch (exception: RuntimeException) {
                logger<GlowOverlayManager>().warn("Failed to refresh glow keystroke license gate", exception)
            }
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

        fun broadcastPowerSave(enabled: Boolean) {
            for (project in ProjectManager.getInstance().openProjects) {
                val update =
                    Runnable {
                        try {
                            getInstance(project).input.onPowerSaveChanged(enabled)
                        } catch (exception: RuntimeException) {
                            logger<GlowOverlayManager>().warn(
                                "Failed to sync Power Save glow for project ${project.name}",
                                exception,
                            )
                        }
                    }
                if (SwingUtilities.isEventDispatchThread()) update.run() else SwingUtilities.invokeLater(update)
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
    internal val input =
        GlowInputSink(
            keystroke = keystroke@{
                if (disposed) return@keystroke
                val state = AyuIslandsSettings.getInstance().state
                if (!state.glowEnabled) return@keystroke
                if (routeController.handle(RouteEvent.Keystroke(System.currentTimeMillis()))) {
                    return@keystroke
                }
                val glassPane = activeGlowId?.let { overlays[it]?.glassPane } ?: return@keystroke
                if (glassPane.isWaveform) {
                    glassPane.onWaveformKeystroke()
                } else {
                    val animation = GlowAnimation.fromName(state.glowAnimation ?: GlowAnimation.NONE.name)
                    if (animator == null && animation == GlowAnimation.REACTIVE) {
                        startAnimationIfConfigured(glassPane)
                    }
                    animator?.onKeystroke()
                }
            },
            powerSaveChange = { enabled ->
                if (!routeController.handle(RouteEvent.PowerSaveChanged(enabled))) {
                    activeGlowId?.let { overlays[it]?.glassPane }?.changeWaveformPowerSave(enabled)
                }
            },
        )

    fun initialize() {
        if (disposed) return
        if (!LicenseChecker.isLicensedOrGrace()) return

        val settings = AyuIslandsSettings.getInstance()
        if (!settings.state.glowEnabled) {
            log.info("Glow disabled, skipping overlay initialization")
            return
        }

        val context = AccentContext.detect()
        if (context == null) {
            log.info("No accent context detected, skipping glow initialization")
            return
        }
        if (context == AccentContext.External && !settings.state.isExternalGlowAllowed()) {
            log.info("External glow disabled, skipping overlay initialization")
            return
        }

        if (messageBusConnected) {
            SwingUtilities.invokeLater {
                attachVisibleToolWindowOverlays()
                attachEditorOverlayIfNeeded()
                refreshActiveGlow()
            }
            log.info("GlowOverlayManager re-initialized for project: ${project.name}")
            return
        }
        messageBusConnected = true

        subscribeToMessageBus()
        try {
            KeystrokeHub.getInstance().initialize()
        } catch (exception: RuntimeException) {
            if (hubFailureLogged.compareAndSet(false, true)) {
                log.warn("Glow keystroke hub initialization failed", exception)
            }
        }

        SwingUtilities.invokeLater {
            attachVisibleToolWindowOverlays()
            attachEditorOverlayIfNeeded()
            installFocusTracker()
            refreshActiveGlow()
            initializeFocusRingGlow()
        }

        log.info("GlowOverlayManager initialized for project: ${project.name}")
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
                        val activeId = toolWindowManager.activeToolWindowId
                        val toolWindow = activeId?.let(toolWindowManager::getToolWindow)
                        if (toolWindow?.isVisible == true) {
                            reattachToolWindowOverlayIfNeeded(toolWindow)
                            attachToolWindowOverlay(toolWindow)
                        }
                        routeController.scheduleGraphRefresh()
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
                        routeController.scheduleGraphRefresh()
                    }
                }
            },
        )

        connection.subscribe(
            AccentChangedTopic.TOPIC,
            AccentChangeListener { project, hex, _ ->
                if (disposed) return@AccentChangeListener
                if (project !== this@GlowOverlayManager.project) return@AccentChangeListener

                if (SwingUtilities.isEventDispatchThread()) {
                    updateGlow(hex)
                } else {
                    SwingUtilities.invokeLater {
                        if (!disposed) updateGlow(hex)
                    }
                }
            },
        )

        ApplicationManager
            .getApplication()
            .messageBus
            .connect(this)
            .subscribe(
                ApplicationActivationListener.TOPIC,
                activationListener,
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
        val newActiveId =
            if (focusOwner == null) {
                null
            } else {
                findOverlayId(
                    focusOwner,
                    overlays.asSequence().map { (id, entry) -> id to entry.host },
                )
            }

        if (newActiveId != activeGlowId) {
            if (!routeController.isActive) {
                moveGlowFocus(from = activeGlowId, to = newActiveId)
            }
            activeGlowId = newActiveId
        }
    }

    private fun initializeFocusRingGlow() {
        val state = AyuIslandsSettings.getInstance().state
        if (!state.glowFocusRing) return

        // Pattern A — log-once gate. AccentContext.detect() returns null only when
        // no Ayu Islands accent context is active; without the diagnostic, a user reporting
        // "focus ring glow stopped working after theme tweak" hands us logs
        // with no breadcrumb of which guard fired.
        val context =
            AccentContext.detect() ?: run {
                log.debug("AccentContext.detect() returned null in initializeFocusRingGlow, skipping focus-ring glow")
                return
            }
        if (isExternalGlowBlocked(context, state, "initializeFocusRingGlow")) return
        val accentHex = resolveCurrentGlowAccentHex(project, state, context)
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
            if (glassPane.usesWaveformBounds) {
                val geometry =
                    if (glassPane.isEditorOverlay) {
                        EditorTabGeometry.editorOverlayGeometry(host)
                    } else {
                        EditorOverlayGeometry(
                            contentBounds = Rectangle(0, 0, host.width, host.height),
                            occupiedTopSpans = emptyList(),
                        )
                    }
                val contentBounds = geometry.contentBounds
                glassPane.waveformTopSpans = geometry.occupiedTopSpans
                val margin = glassPane.waveformMargin
                val overlayBounds =
                    Rectangle(
                        point.x + contentBounds.x - margin,
                        point.y + contentBounds.y - margin,
                        contentBounds.width + margin * 2,
                        contentBounds.height + margin * 2,
                    )
                glassPane.bounds = overlayBounds
                glassPane.waveformInwardEdges = clippedWaveformEdges(overlayBounds, layeredPane.visibleRect)
            } else if (glassPane.isEditorOverlay) {
                glassPane.waveformInwardEdges = emptySet()
                val bounds = EditorTabGeometry.calculateEditorOverlayBounds(host)
                glassPane.bounds = Rectangle(point.x + bounds.x, point.y + bounds.y, bounds.width, bounds.height)
            } else {
                glassPane.waveformInwardEdges = emptySet()
                glassPane.setBounds(point.x, point.y, host.width, host.height)
            }
        } catch (exception: RuntimeException) {
            log.debug("Component hierarchy changed during overlay bounds update", exception)
        }
        routeController.scheduleGraphRefresh()
    }

    private fun attachOverlay(
        id: String,
        host: JComponent,
        isEditorOverlay: Boolean = false,
    ) {
        val state = AyuIslandsSettings.getInstance().state
        if (disposed || !state.glowEnabled || !LicenseChecker.isLicensedOrGrace()) return
        if (overlays.containsKey(id)) return
        if (host.width == 0 || host.height == 0) return

        val rootPane = SwingUtilities.getRootPane(host) ?: return
        val layeredPane = rootPane.layeredPane

        // Pattern A — log-once gate. attachOverlay fires from message-bus callbacks
        // (tool-window state change, editor selection); a silent skip when no accent
        // context is active leaves the overlay un-attached without any trace in idea.log.
        val context =
            AccentContext.detect() ?: run {
                log.debug("AccentContext.detect() returned null in attachOverlay($id), skipping overlay attach")
                return
            }
        if (isExternalGlowBlocked(context, state, "attachOverlay($id)")) return
        val accentHex = resolveCurrentGlowAccentHex(project, state, context)
        val style = GlowStyle.fromName(state.glowStyle ?: GlowStyle.SOFT.name)

        val glassPane =
            GlowGlassPane(
                glowColor = safeDecodeColor(accentHex),
                glowStyle = style,
                glowIntensity = state.getIntensityForStyle(style),
                glowWidth = state.getWidthForStyle(style),
                isEditorOverlay = isEditorOverlay,
                glowPlacement = resolveGlowPlacement(isEditorOverlay, state),
            )
        if (isEditorOverlay) {
            glassPane.topSpansProvider = {
                EditorTabGeometry.editorOverlayGeometry(host).occupiedTopSpans
            }
        }
        glassPane.configureWaveform(
            GlowShape.fromName(state.glowShape),
            resolveWaveformConfig(state),
        )
        if (routeController.isActive) {
            glassPane.configureRouteMode(enabled = true)
        }

        layeredPane.setLayer(glassPane, JLayeredPane.PALETTE_LAYER)
        layeredPane.add(glassPane)
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
        routeController.scheduleGraphRefresh()
        log.info(
            "Glow overlay attached: $id (host: ${host.javaClass.simpleName}, " +
                "chain: ${ComponentHierarchyUtils.describeAncestry(host)})",
        )
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
        routeController.scheduleGraphRefresh()
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
        overlays[EDITOR_ID]?.let { entry ->
            updateOverlayBounds(entry.glassPane, entry.host, entry.layeredPane)
            entry.glassPane.repaint()
            return
        }

        val state = AyuIslandsSettings.getInstance().state
        if (!state.isIslandEnabled(EDITOR_ID)) return

        val editorComponent = FileEditorManager.getInstance(project).selectedEditor?.component ?: return
        if (!editorComponent.isDisplayable) return
        val host = ComponentHierarchyUtils.findEditorHost(editorComponent) ?: return
        attachOverlay(EDITOR_ID, host, isEditorOverlay = true)
    }

    private fun moveGlowFocus(
        from: String?,
        to: String?,
    ) {
        if (routeController.isActive) return
        from?.let { overlays[it] }?.let { entry ->
            stopGlowAnimation(entry.glassPane, animator)
            animator = null
            entry.glassPane.startFadeOut()
        }
        to?.let { overlays[it] }?.let { entry ->
            entry.glassPane.startFadeIn()
            startAnimationIfConfigured(entry.glassPane)
            log.info("Glow activated: $to")
        }
    }

    private fun startAnimationIfConfigured(glassPane: GlowGlassPane) {
        if (glassPane.isWaveform) {
            animator?.let { Disposer.dispose(it) }
            animator = null
            glassPane.animationAlpha = 1.0f
            glassPane.activateWaveform(PowerSaveMode.isEnabled())
            return
        }
        glassPane.deactivateWaveform()
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

    fun updateGlow(appliedAccent: AccentHex? = null) {
        if (disposed) return
        if (!LicenseChecker.isLicensedOrGrace()) {
            removeAllOverlays()
            return
        }
        val context = AccentContext.detect()
        if (context == null) {
            removeAllOverlays()
            return
        }

        val settings = AyuIslandsSettings.getInstance()
        val state = settings.state
        if (!state.glowEnabled) {
            removeAllOverlays()
            return
        }
        if (context == AccentContext.External && !state.isExternalGlowAllowed()) {
            removeAllOverlays()
            return
        }
        if (!messageBusConnected) {
            initialize()
            return
        }
        if (overlays.isEmpty()) {
            SwingUtilities.invokeLater {
                attachVisibleToolWindowOverlays()
                attachEditorOverlayIfNeeded()
                refreshActiveGlow()
            }
        }

        val accentHex =
            appliedAccent?.value
                ?: resolveCurrentGlowAccentHex(project, state, context)
        val accent = safeDecodeColor(accentHex)
        val style = GlowStyle.fromName(state.glowStyle ?: GlowStyle.SOFT.name)

        updateOverlayStyles(state, accent, style)
        repaintProjectTabs(project)

        val intensity = state.getIntensityForStyle(style)
        focusRingManager.updateFocusRingGlow(accent, style, intensity, state.glowFocusRing)

        val config = resolveWaveformConfig(state)
        updateRouteLifecycle(state, config)

        log.info("Glow overlays updated: style=$style, accent=$accentHex")
    }

    private fun updateRouteLifecycle(
        state: AyuIslandsState,
        config: WaveformConfig,
    ) {
        if (GlowShape.fromName(state.glowShape) == GlowShape.WAVEFORM &&
            config.movement == WaveformMovement.CHAOTIC
        ) {
            attachVisibleToolWindowOverlays()
            attachEditorOverlayIfNeeded()
            overlays.values.forEach { entry -> entry.glassPane.configureRouteMode(enabled = true) }
            animator?.let { Disposer.dispose(it) }
            animator = null
            routeController.configure(config)
            routeController.handle(
                RouteEvent.ApplicationActiveChanged(ApplicationManager.getApplication().isActive),
            )
        } else {
            routeController.dispose()
            overlays.forEach { (id, entry) ->
                entry.glassPane.configureRouteMode(enabled = false)
                if (id == activeGlowId) {
                    entry.glassPane.startFadeIn()
                } else {
                    entry.glassPane.startFadeOut()
                }
            }
            activeGlowId?.let { overlays[it] }?.let { entry ->
                startAnimationIfConfigured(entry.glassPane)
            }
        }
    }

    /**
     * Settings-dialog preview: pushes placements onto live overlays without
     * touching persisted state, exactly like the theme preview in Appearance.
     * Null placements re-read the stored state — the Cancel/reset revert path.
     */
    fun previewPlacements(
        editorPlacement: GlowPlacement?,
        toolWindowPlacement: GlowPlacement?,
    ) {
        val state = AyuIslandsSettings.getInstance().state
        for ((glassPane) in overlays.values) {
            val isEditor = glassPane.isEditorOverlay
            glassPane.glowPlacement =
                (if (isEditor) editorPlacement else toolWindowPlacement)
                    ?: resolveGlowPlacement(isEditorOverlay = isEditor, state = state)
            glassPane.repaint()
        }
    }

    private fun updateOverlayStyles(
        state: AyuIslandsState,
        accent: Color,
        style: GlowStyle,
    ) {
        for ((glassPane, host, layeredPane) in overlays.values) {
            glassPane.glowColor = accent
            glassPane.glowStyle = style
            glassPane.glowIntensity = state.getIntensityForStyle(style)
            glassPane.glowWidth = state.getWidthForStyle(style)
            glassPane.glowPlacement =
                resolveGlowPlacement(isEditorOverlay = glassPane.isEditorOverlay, state = state)
            glassPane.configureWaveform(GlowShape.fromName(state.glowShape), resolveWaveformConfig(state))
            updateOverlayBounds(glassPane, host, layeredPane)
            glassPane.invalidateRendererCache()
            glassPane.repaint()
        }
    }

    private fun removeAllOverlays() {
        routeController.dispose()
        animator?.let { Disposer.dispose(it) }
        animator = null
        for ((_, entry) in overlays) {
            detachOverlayEntry(entry)
        }
        overlays.clear()
        activeGlowId = null
        focusRingManager.removeFocusListeners()
        log.info("All glow overlays removed")
    }

    override fun dispose() {
        disposed = true
        if (SwingUtilities.isEventDispatchThread()) {
            routeController.dispose()
        } else {
            SwingUtilities.invokeAndWait { routeController.dispose() }
        }

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

private fun findOverlayId(
    component: Component,
    overlayHosts: Sequence<Pair<String, JComponent>>,
): String? {
    val hosts = overlayHosts.toList()
    var current: Component? = component
    while (current != null) {
        hosts.firstOrNull { (_, host) -> current === host }?.let { (id) -> return id }
        current = current.parent
    }
    return null
}

private fun stopGlowAnimation(
    glassPane: GlowGlassPane,
    animator: GlowAnimator?,
) {
    animator?.let(Disposer::dispose)
    glassPane.deactivateWaveform()
    glassPane.animationAlpha = 1.0f
}

private fun repaintProjectTabs(project: Project) {
    EditorTabGeometry.repaintEditorTabs(project)
}

private fun clippedWaveformEdges(
    overlayBounds: Rectangle,
    visibleBounds: Rectangle,
): Set<WaveformEdge> =
    buildSet {
        // Redirect before the phosphor halo leaves the Swing root so the complete trace stays visible.
        if (overlayBounds.y < visibleBounds.y) add(WaveformEdge.TOP)
        if (overlayBounds.maxX > visibleBounds.maxX) add(WaveformEdge.RIGHT)
        if (overlayBounds.maxY > visibleBounds.maxY) add(WaveformEdge.BOTTOM)
        if (overlayBounds.x < visibleBounds.x) add(WaveformEdge.LEFT)
    }

private fun isExternalGlowBlocked(
    context: AccentContext,
    state: AyuIslandsState,
    callSite: String,
): Boolean {
    if (context != AccentContext.External || state.isExternalGlowAllowed()) return false
    logger<GlowOverlayManager>().debug("External glow disabled, skipping $callSite")
    return true
}

private fun resolveCurrentGlowAccentHex(
    project: Project,
    state: AyuIslandsState,
    context: AccentContext,
): String = state.trustedCachedAccent()?.value ?: AccentResolver.resolve(project, context)

private fun resolveGlowPlacement(
    isEditorOverlay: Boolean,
    state: AyuIslandsState,
): GlowPlacement =
    if (isEditorOverlay) {
        GlowPlacement.fromName(state.glowEditorPlacement)
    } else {
        GlowPlacement.fromName(state.glowToolWindowPlacement)
    }
