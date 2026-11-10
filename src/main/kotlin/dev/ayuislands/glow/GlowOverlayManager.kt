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
import dev.ayuislands.glow.waveform.WaveformEngine
import dev.ayuislands.glow.waveform.WaveformMovement
import dev.ayuislands.licensing.LicenseChecker
import dev.ayuislands.settings.AyuIslandsSettings
import dev.ayuislands.settings.GlowSettings
import java.awt.Color
import java.awt.Component
import java.awt.KeyboardFocusManager
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.HierarchyBoundsAdapter
import java.awt.event.HierarchyEvent
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.JComponent
import javax.swing.JLayeredPane
import javax.swing.SwingUtilities

/** Manages glow overlays for tool windows, editor, tabs, and focus rings. */
@Service(Service.Level.PROJECT)
class GlowOverlayManager(
    internal val project: Project,
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
            settings = { updates.current.settings },
            onFailure = { exception -> log.warn("Chaotic waveform route render failed", exception) },
            asyncUpdatesAllowed = { updates.admit() },
        )

    @Volatile
    private var disposed = false

    private val isUnavailable: Boolean
        get() = disposed || project.isDisposed

    private val focusRingManager = FocusRingManager()
    private val updates =
        GlowUpdateGate(
            isDisposed = { isUnavailable },
            readInput = { AyuIslandsSettings.getInstance().state?.let(::loadGlowRuntimeInput) },
            holdFocus = focusRingManager::holdCallbacks,
        )

    private val projectSubscription = GlowSubscription()
    private val applicationSubscription = GlowSubscription()
    private val focusTracker =
        GlowFocusTracker {
            if (!disposed) updates.queue { refreshActiveGlow() }
        }

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
                        if (!updates.admit()) return@Runnable
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
                if (SwingUtilities.isEventDispatchThread()) update.run() else updates.queue(action = update::run)
            }
        }

    // Continuous glow animation (Pulse/Breathe/Reactive)
    private var animator: GlowAnimator? = null

    companion object {
        internal const val EDITOR_ID = "Editor"
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
                if (project.isDisposed) continue
                val manager =
                    try {
                        getInstance(project)
                    } catch (exception: RuntimeException) {
                        logger<GlowOverlayManager>().warn(
                            "Failed to resolve Power Save glow for ${project.name}",
                            exception,
                        )
                        continue
                    }
                val update =
                    Runnable {
                        if (project.isDisposed) return@Runnable
                        try {
                            manager.input.onPowerSaveChanged(enabled)
                        } catch (exception: RuntimeException) {
                            logger<GlowOverlayManager>().warn(
                                "Failed to sync Power Save glow for project ${project.name}",
                                exception,
                            )
                        }
                    }
                if (SwingUtilities.isEventDispatchThread()) {
                    update.run()
                } else {
                    manager.updates.queue(action = update::run)
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

    internal data class OverlayEntry(
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
                if (!updates.isOpen) return@keystroke
                val current = updates.current
                val settings = current.settings
                if (!settings.enabled) return@keystroke
                if (routeController.handle(RouteEvent.Keystroke(System.currentTimeMillis()))) {
                    return@keystroke
                }
                val glassPane = activeGlowId?.let { overlays[it]?.glassPane } ?: return@keystroke
                if (glassPane.isWaveform) {
                    glassPane.onWaveformKeystroke()
                } else {
                    val animation = settings.animation
                    if (animator == null && animation == GlowAnimation.REACTIVE) {
                        startAnimationIfConfigured(glassPane)
                    }
                    animator?.onKeystroke()
                }
            },
            powerSaveChange = powerSave@{ enabled ->
                if (!updates.admit()) return@powerSave
                if (!routeController.handle(RouteEvent.PowerSaveChanged(enabled))) {
                    activeGlowId?.let { overlays[it]?.glassPane }?.changeWaveformPowerSave(enabled)
                }
            },
        )

    internal fun holdUpdates(): GlowUpdateGate.Hold = updates.hold()

    internal fun capturePlayback(hold: GlowUpdateGate.Hold): GlowPlaybackPause {
        check(updates.isHeldBy(hold)) { "Glow playback requires its manager's active hold" }
        return captureGlowPlayback(
            hold,
            overlays,
            routeController,
            readAnimator = { animator },
            clearAnimator = { animator = null },
        )
    }

    internal fun preparePlayback(
        hold: GlowUpdateGate.Hold,
        paused: GlowPlaybackPause,
        request: GlowPlaybackRequest = GlowPlaybackRequest.Restore,
        preparedEngines: Map<GlowGlassPane, WaveformEngine> = emptyMap(),
    ): GlowPlaybackRestart {
        check(updates.isHeldBy(hold)) { "Glow playback requires its manager's active hold" }
        val plan =
            when (request) {
                GlowPlaybackRequest.Restore -> {
                    paused.planRestart(hold)
                }

                is GlowPlaybackRequest.Activate -> {
                    paused.checkStopped(hold)
                    val targets = GlowOverlaySnapshot(overlays, hold, request.targets.map { it.overlay.id }.toSet())
                    checkPlaybackIntent(request, targets.selected, preparedEngines)
                    GlowPlaybackPlan(targets, request.animation, request.routeConfig, request.environment)
                }
            }
        return GlowPlaybackRestart(
            plan,
            hold,
            routeController,
            owner = this,
            readAnimator = { animator },
            writeAnimator = { animator = it },
            selectFocus = { activeGlowId = it },
        )
    }

    internal fun prepareOverlay(
        target: GlowHost,
        candidate: GlowRuntimeInput,
        context: AccentContext? = AccentContext.detect(),
        hold: GlowUpdateGate.Hold? = null,
    ): GlowOverlayAttachment? {
        check(SwingUtilities.isEventDispatchThread()) { "Glow attachments must run on EDT" }
        val (id, host, isEditorOverlay) = target
        hold?.let { check(updates.isHeldBy(it)) { "Glow attachment hold is unavailable" } }
        val settings = candidate.settings
        if (disposed || !settings.enabled || !LicenseChecker.isLicensedOrGrace()) return null
        if (overlays.containsKey(id)) return null
        if (host.width == 0 || host.height == 0) return null

        val rootPane = SwingUtilities.getRootPane(host) ?: return null
        val layeredPane = rootPane.layeredPane

        // Pattern A — log-once gate. attachOverlay fires from message-bus callbacks
        // (tool-window state change, editor selection); a silent skip when no accent
        // context is active leaves the overlay un-attached without any trace in idea.log.
        if (context == null) {
            log.debug("No accent context in attachOverlay($id), skipping overlay attach")
            return null
        }
        if (isExternalGlowBlocked(context, candidate, "attachOverlay($id)")) return null
        val accentHex = resolveGlowAccent(project, candidate, context) ?: return null
        val glassPane = createGlowPane(host, isEditorOverlay, settings, safeDecodeColor(accentHex))
        glassPane.configureRouteMode(enabled = routeController.isActive)

        val refreshBounds: (Boolean) -> Unit = { reportFallback ->
            refreshOverlayBounds(id, glassPane, host, layeredPane, reportFallback)
        }
        val componentListener =
            object : ComponentAdapter() {
                override fun componentResized(e: ComponentEvent) = refreshBounds(true)

                override fun componentMoved(e: ComponentEvent) = refreshBounds(true)
            }
        val boundsListener =
            object : HierarchyBoundsAdapter() {
                override fun ancestorMoved(e: HierarchyEvent) = refreshBounds(true)

                override fun ancestorResized(e: HierarchyEvent) = refreshBounds(true)
            }
        val entry = OverlayEntry(glassPane, host, layeredPane, componentListener, boundsListener)
        return GlowOverlayAttachment(
            id,
            entry,
            overlays,
            isAvailable = { if (hold == null) !isUnavailable else updates.isHeldBy(hold) },
            refreshBounds = { installed ->
                updateOverlayBounds(installed.glassPane, installed.host, installed.layeredPane, false)
            },
            detach = ::detachOverlayEntry,
            afterAttach = {
                updates.queue {
                    refreshBounds(false)
                    if (isEditorOverlay) updates.queue { refreshBounds(true) }
                }
                routeController.scheduleGraphRefresh()
                log.info(
                    "Glow overlay attached: $id (host: ${host.javaClass.simpleName}, " +
                        "chain: ${ComponentHierarchyUtils.describeAncestry(host)})",
                )
            },
        )
    }

    fun initialize() {
        if (!updates.admit()) return
        if (!LicenseChecker.isLicensedOrGrace()) return

        val current = updates.current
        if (!current.settings.enabled) {
            log.info("Glow disabled, skipping overlay initialization")
            return
        }

        val context = AccentContext.detect()
        if (context == null) {
            log.info("No accent context detected, skipping glow initialization")
            return
        }
        if (context == AccentContext.External && !current.externalAllowed) {
            log.info("External glow disabled, skipping overlay initialization")
            return
        }

        if (messageBusConnected) {
            updates.queue {
                focusTracker.register()
                attachVisibleToolWindowOverlays()
                attachEditorOverlayIfNeeded()
                refreshActiveGlow()
            }
            log.info("GlowOverlayManager re-initialized for project: ${project.name}")
            return
        }
        registerCallbacks()
        try {
            KeystrokeHub.getInstance().initialize()
        } catch (exception: RuntimeException) {
            if (hubFailureLogged.compareAndSet(false, true)) {
                log.warn("Glow keystroke hub initialization failed", exception)
            }
        }

        updates.queue {
            attachVisibleToolWindowOverlays()
            attachEditorOverlayIfNeeded()
            if (!disposed) focusTracker.register()
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

    /** Register callbacks under the exact held manager without applying native appearance. */
    internal fun registerCallbacks(hold: GlowUpdateGate.Hold? = null) {
        check(SwingUtilities.isEventDispatchThread()) { "Glow callback registration must run on EDT" }
        check(!isUnavailable) { "Glow manager is unavailable" }
        if (hold != null) check(updates.isHeldBy(hold)) { "Glow callback hold is unavailable" }
        projectSubscription.register(project.messageBus, this) { connection ->
            connection.subscribe(
                ToolWindowManagerListener.TOPIC,
                object : ToolWindowManagerListener {
                    override fun stateChanged(
                        toolWindowManager: ToolWindowManager,
                        changeType: ToolWindowManagerListener.ToolWindowManagerEventType,
                    ) {
                        updates.queue {
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
                        updates.queue {
                            attachEditorOverlayIfNeeded()
                            routeController.scheduleGraphRefresh()
                        }
                    }
                },
            )

            connection.subscribe(
                AccentChangedTopic.TOPIC,
                AccentChangeListener { changedProject, hex, _ ->
                    if (disposed || changedProject !== project) return@AccentChangeListener
                    if (SwingUtilities.isEventDispatchThread()) {
                        updateGlow(hex)
                    } else {
                        updates.queue(hex) { updateGlow(hex) }
                    }
                },
            )
        }
        applicationSubscription.register(ApplicationManager.getApplication().messageBus, this) { connection ->
            connection.subscribe(ApplicationActivationListener.TOPIC, activationListener)
        }
        messageBusConnected = true
        if (hold != null) {
            KeystrokeHub.getInstance().initialize()
            focusTracker.register()
            check(updates.isHeldBy(hold)) { "Glow callback hold became unavailable" }
        }
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
        val current = updates.current
        val settings = current.settings
        if (!settings.focusRing) return

        // Pattern A — log-once gate. AccentContext.detect() returns null only when
        // no Ayu Islands accent context is active; without the diagnostic, a user reporting
        // "focus ring glow stopped working after theme tweak" hands us logs
        // with no breadcrumb of which guard fired.
        val context =
            AccentContext.detect() ?: run {
                log.debug("AccentContext.detect() returned null in initializeFocusRingGlow, skipping focus-ring glow")
                return
            }
        if (isExternalGlowBlocked(context, current, "initializeFocusRingGlow")) return
        val accentHex = resolveGlowAccent(project, current, context) ?: return
        val style = settings.style
        val accent = safeDecodeColor(accentHex)
        val intensity = settings.intensity.getValue(style)

        focusRingManager.initializeFocusRingGlow(accent, style, intensity)
    }

    private fun updateOverlayBounds(
        glassPane: GlowGlassPane,
        host: JComponent,
        layeredPane: JLayeredPane,
        shouldReportFallback: Boolean = true,
    ) {
        if (!host.isShowing) return
        try {
            val canReportFallback =
                !glassPane.isEditorOverlay || FileEditorManager.getInstance(project).selectedEditor != null
            updateGlowBounds(glassPane, host, layeredPane, shouldReportFallback && canReportFallback)
        } catch (exception: RuntimeException) {
            log.debug("Component hierarchy changed during overlay bounds update", exception)
        }
        routeController.scheduleGraphRefresh()
    }

    private fun refreshOverlayBounds(
        id: String,
        glassPane: GlowGlassPane,
        host: JComponent,
        layeredPane: JLayeredPane,
        reportFallback: Boolean,
    ) {
        if (!updates.admit()) return
        val retained = overlays[id] ?: return
        if (retained.glassPane !== glassPane || retained.host !== host || retained.layeredPane !== layeredPane) return
        if (SwingUtilities.getRootPane(host)?.layeredPane !== layeredPane) return
        updateOverlayBounds(glassPane, host, layeredPane, reportFallback)
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
        val entry = overlays[id] ?: return
        if (activeGlowId == id) {
            val previousAnimator = animator
            previousAnimator?.let(Disposer::dispose)
            if (animator === previousAnimator) animator = null
        }
        detachOverlayEntry(entry)
        if (overlays[id] === entry) {
            overlays.remove(id)
            if (activeGlowId == id && animator == null) activeGlowId = null
        }
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

        val current = updates.current
        val host = GlowHosts.findToolWindow(toolWindow, current.settings) ?: return
        prepareOverlay(GlowHost(id, host, isEditor = false), current)?.installLegacy()
    }

    private fun attachEditorOverlayIfNeeded() {
        overlays[EDITOR_ID]?.let { entry ->
            updateOverlayBounds(entry.glassPane, entry.host, entry.layeredPane)
            entry.glassPane.repaint()
            return
        }

        val current = updates.current
        val host = GlowHosts.findEditor(project, current.settings) ?: return
        prepareOverlay(GlowHost(EDITOR_ID, host, isEditor = true), current)?.installLegacy()
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
        val current = updates.current
        val settings = current.settings
        val animation = settings.animation
        animator?.let { Disposer.dispose(it) }
        animator = null
        if (animation == GlowAnimation.NONE) {
            glassPane.animationAlpha = 1.0f
            return
        }

        val createdAnimator = GlowAnimator()
        animator = createdAnimator
        Disposer.register(this, createdAnimator)
        createdAnimator.start(
            animation,
            isVisible = { glassPane.isShowing },
        ) { alpha ->
            glassPane.animationAlpha = alpha
        }
    }

    fun updateGlow(appliedAccent: AccentHex? = null) {
        if (!updates.admit(appliedAccent)) return
        if (!LicenseChecker.isLicensedOrGrace()) {
            removeAllOverlays()
            return
        }
        val context = AccentContext.detect()
        if (context == null) {
            removeAllOverlays()
            return
        }

        val current = updates.current
        val settings = current.settings
        if (!settings.enabled) {
            removeAllOverlays()
            return
        }
        if (context == AccentContext.External && !current.externalAllowed) {
            removeAllOverlays()
            return
        }
        val accentHex = appliedAccent?.value ?: resolveGlowAccent(project, current, context) ?: return
        val removedTargets = overlays.keys.filterNot(settings::isIslandEnabled)
        removedTargets.forEach(::removeOverlay)
        if (!messageBusConnected) {
            initialize()
        }
        updates.queue {
            val previousTargets = overlays.keys.toSet()
            attachVisibleToolWindowOverlays()
            attachEditorOverlayIfNeeded()
            if (removedTargets.isNotEmpty() || previousTargets != overlays.keys) refreshActiveGlow()
        }

        val accent = safeDecodeColor(accentHex)
        val style = settings.style

        updateOverlayStyles(settings, accent, style)
        repaintProjectTabs(project)

        val intensity = settings.intensity.getValue(style)
        focusRingManager.updateFocusRingGlow(accent, style, intensity, settings.focusRing)

        val config = resolveWaveformConfig(settings)
        updateRouteLifecycle(settings, config)

        log.info("Glow overlays updated: style=$style, accent=$accentHex")
    }

    private fun updateRouteLifecycle(
        settings: GlowSettings,
        config: WaveformConfig,
    ) {
        if (settings.shape == GlowShape.WAVEFORM &&
            config.movement == WaveformMovement.CHAOTIC
        ) {
            attachVisibleToolWindowOverlays()
            attachEditorOverlayIfNeeded()
            if (!routeController.isActive && activeGlowId == null) {
                refreshActiveGlow()
            }
            overlays.values.forEach { entry ->
                entry.glassPane.animationAlpha = 1.0f
                entry.glassPane.configureRouteMode(enabled = true)
            }
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
        if (!updates.admit()) return
        val current = updates.current
        val settings = current.settings
        for ((glassPane) in overlays.values) {
            val isEditor = glassPane.isEditorOverlay
            glassPane.glowPlacement =
                (if (isEditor) editorPlacement else toolWindowPlacement)
                    ?: resolveGlowPlacement(isEditorOverlay = isEditor, settings = settings)
            glassPane.repaint()
        }
    }

    private fun updateOverlayStyles(
        settings: GlowSettings,
        accent: Color,
        style: GlowStyle,
    ) {
        for ((glassPane, host, layeredPane) in overlays.values) {
            glassPane.glowColor = accent
            glassPane.glowStyle = style
            glassPane.glowIntensity = settings.intensity.getValue(style)
            glassPane.glowWidth = settings.width.getValue(style)
            glassPane.glowPlacement =
                resolveGlowPlacement(isEditorOverlay = glassPane.isEditorOverlay, settings = settings)
            glassPane.configureWaveform(settings.shape, resolveWaveformConfig(settings))
            updateOverlayBounds(glassPane, host, layeredPane)
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

        focusTracker.dispose()

        SwingUtilities.invokeLater {
            removeAllOverlays()
            focusRingManager.dispose()
        }
    }
}

internal fun findOverlayId(
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

private fun isExternalGlowBlocked(
    context: AccentContext,
    current: GlowRuntimeInput,
    callSite: String,
): Boolean {
    if (context != AccentContext.External || current.externalAllowed) return false
    logger<GlowOverlayManager>().debug("External glow disabled, skipping $callSite")
    return true
}

private fun resolveGlowAccent(
    project: Project,
    current: GlowRuntimeInput,
    context: AccentContext,
): String? = current.accent?.value ?: AccentResolver.resolve(project, context)

internal fun resolveGlowPlacement(
    isEditorOverlay: Boolean,
    settings: GlowSettings,
): GlowPlacement =
    if (isEditorOverlay) {
        settings.editorPlacement
    } else {
        settings.toolWindowPlacement
    }
