package dev.ayuislands.glow

import com.intellij.ide.PowerSaveMode
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.openapi.wm.ToolWindowType
import com.intellij.util.ui.JBUI
import dev.ayuislands.glow.waveform.CrossWindowBridge
import dev.ayuislands.glow.waveform.RouteConnector
import dev.ayuislands.glow.waveform.RouteConnectorId
import dev.ayuislands.glow.waveform.RouteCoordinator
import dev.ayuislands.glow.waveform.RouteEvent
import dev.ayuislands.glow.waveform.RouteFrame
import dev.ayuislands.glow.waveform.RouteGraph
import dev.ayuislands.glow.waveform.RouteGraphBuilder
import dev.ayuislands.glow.waveform.RouteLayerStyle
import dev.ayuislands.glow.waveform.RoutePaintTarget
import dev.ayuislands.glow.waveform.RouteRootId
import dev.ayuislands.glow.waveform.RouteSlice
import dev.ayuislands.glow.waveform.RouteSurface
import dev.ayuislands.glow.waveform.RouteUpdate
import dev.ayuislands.glow.waveform.RouteWindowKind
import dev.ayuislands.glow.waveform.TimerDirective
import dev.ayuislands.glow.waveform.WaveformConfig
import dev.ayuislands.glow.waveform.WaveformRouteLayer
import dev.ayuislands.glow.waveform.WindowBridgeCapability
import dev.ayuislands.settings.GlowSettings
import java.awt.Point
import java.awt.Rectangle
import java.awt.Window
import java.util.IdentityHashMap
import javax.swing.JComponent
import javax.swing.JLayeredPane
import javax.swing.SwingUtilities
import javax.swing.Timer
import javax.swing.UIManager

private data class RouteRoot(
    val layeredPane: JLayeredPane,
    val window: Window,
    val screenOrigin: Point,
)

private data class BridgeTarget(
    val connector: RouteConnector,
    val owner: Window,
)

internal data class RouteOverlay(
    val id: String,
    val pane: GlowGlassPane,
    val host: JComponent,
    val layeredPane: JLayeredPane,
)

internal class RouteController(
    private val project: Project,
    private val overlays: () -> List<RouteOverlay>,
    private val focusedSurfaceId: () -> String?,
    private val settings: () -> GlowSettings,
    private val onFailure: (RuntimeException) -> Unit,
    private val asyncUpdatesAllowed: () -> Boolean = { true },
    private val createTimer: (Int, () -> Unit) -> Timer = { delay, update -> Timer(delay) { update() } },
) {
    private var coordinator: RouteCoordinator? = null
    private var timer: Timer? = null
    private var layoutTimer: Timer? = null
    private val bridges = mutableMapOf<RouteConnector, CrossWindowBridge>()
    private val layers = mutableMapOf<RouteRootId, WaveformRouteLayer>()
    private val rootIds = IdentityHashMap<JLayeredPane, RouteRootId>()
    private val roots = mutableMapOf<RouteRootId, RouteRoot>()
    private val bridgeTargets = mutableMapOf<RouteConnectorId, MutableList<BridgeTarget>>()
    private var currentBridgeTargets = emptyMap<RouteConnectorId, List<BridgeTarget>>()
    private var routeOverlays = emptyList<RouteOverlay>()
    private val failedConnectorIds = mutableSetOf<RouteConnectorId>()
    private var nextRootId = 1
    private var graph: RouteGraph? = null
    private var lastFrame: RouteFrame? = null
    private var lastFailure: RuntimeException? = null
    private var bridgeRenderVersion = 0L
    private var playback: RoutePlayback? = null
    private var fadeRecorder: ((RouteOverlay, Timer) -> Unit)? = null

    val isActive: Boolean
        get() = coordinator != null

    val currentConfig: WaveformConfig?
        get() = coordinator?.config

    val currentPlayback: RoutePlayback?
        get() = playback

    fun configure(
        config: WaveformConfig,
        requireCompletion: Boolean = false,
    ) = configureRoute(config, requireCompletion, null)

    fun configure(
        selection: RoutePlayback,
        checkPrepared: ((RouteOverlay) -> Unit)? = null,
        onFadeCreated: ((RouteOverlay, Timer) -> Unit)? = null,
    ) = configureRoute(selection.config, true, selection, checkPrepared, onFadeCreated)

    private fun configureRoute(
        config: WaveformConfig,
        requireCompletion: Boolean,
        selected: RoutePlayback?,
        checkPrepared: ((RouteOverlay) -> Unit)? = null,
        onFadeCreated: ((RouteOverlay, Timer) -> Unit)? = null,
    ) {
        selected?.let { captureRouteAppearance(it, overlays()) }
        playback = selected
        fadeRecorder = onFadeCreated
        if (coordinator == null) {
            coordinator = RouteCoordinator(config)
            lastFailure = null
        } else {
            handle(RouteEvent.Configure(config))
        }
        if (requireCompletion) lastFailure?.let { throw it }
        val expected = coordinator
        selected?.targets?.forEach { target ->
            checkPrepared?.invoke(target)
            check(expected != null && coordinator === expected && playback === selected) {
                "Glow route owner changed before pane activation"
            }
            target.pane.configureRouteMode(enabled = true)
            check(coordinator === expected && playback === selected) {
                "Glow route owner changed during pane activation"
            }
        }
        rebuildGraph()
        if (requireCompletion) {
            lastFailure?.let { throw it }
            check(expected != null && coordinator === expected) { "Glow route owner changed during configuration" }
            check(failedConnectorIds.isEmpty() && bridges.values.none { it.hasPendingCleanup }) {
                "Glow route contains unresolved native resources"
            }
        }
    }

    fun handle(
        event: RouteEvent,
        requireCompletion: Boolean = false,
    ): Boolean {
        val current = coordinator
        val selected = playback
        val isCurrent = { coordinator === current && playback === selected }
        if (current == null) {
            check(!requireCompletion) { "Glow route is inactive" }
            return false
        }
        val handledEvent =
            if (event is RouteEvent.Tick) {
                event.copy(isWindowActive = roots.values.any { root -> root.window.isActive })
            } else {
                event
            }
        try {
            val update = current.handle(handledEvent)
            check(isCurrent()) { "Glow route owner changed during reconciliation" }
            if (handledEvent !is RouteEvent.Tick || handledEvent.isWindowActive) applyUpdate(update)
        } catch (exception: RuntimeException) {
            val isOwnerCurrent = isCurrent()
            if (isOwnerCurrent) fail(exception)
            if (requireCompletion || !isOwnerCurrent) throw exception
        }
        if (requireCompletion) {
            lastFailure?.let { throw it }
            check(isCurrent()) { "Glow route owner changed during reconciliation" }
            check(failedConnectorIds.isEmpty() && bridges.values.none { it.hasPendingCleanup }) {
                "Glow route contains unresolved native resources"
            }
        }
        return true
    }

    fun scheduleGraphRefresh() {
        if (!isActive || !asyncUpdatesAllowed()) return
        val current =
            layoutTimer
                ?: createRouteTimer(LAYOUT_DEBOUNCE_MS) {
                    rebuildGraph()
                }.also {
                    it.isRepeats = false
                    layoutTimer = it
                }
        current.restart()
    }

    /** Durable acceptance returns discovery to the canonical runtime without replaying native effects. */
    fun release(selection: RoutePlayback) {
        check(SwingUtilities.isEventDispatchThread()) { "Glow route handoff must run on EDT" }
        check(playback === selection && coordinator != null) { "Glow route handoff lost its owner" }
        playback = null
        fadeRecorder = null
    }

    private fun createRouteTimer(
        delay: Int,
        update: () -> Unit,
    ): Timer {
        val owner = coordinator
        return createTimer(delay) {
            if (coordinator === owner && asyncUpdatesAllowed()) update()
        }
    }

    fun dispose(requireCompletion: Boolean = false) {
        val owner = coordinator
        val selected = playback
        val checkCurrent = {
            check(coordinator === owner && playback === selected) { "Glow route owner changed during native cleanup" }
        }
        layoutTimer?.stop()
        checkCurrent()
        timer?.stop()
        checkCurrent()
        resetRouteResources(checkCurrent)
        val targets = playback?.targets ?: overlays()
        checkCurrent()
        targets.forEach { overlay ->
            overlay.pane.configureRouteMode(enabled = false)
            checkCurrent()
        }
        routeOverlays = emptyList()
        layoutTimer = null
        timer = null
        coordinator = null
        graph = null
        lastFrame = null
        lastFailure = null
        if (requireCompletion) {
            check(bridges.isEmpty() && layers.isEmpty()) { "Glow route cleanup is incomplete" }
        }
        playback = null
        fadeRecorder = null
    }

    private fun rebuildGraph() {
        if (!isActive) return
        val owner = coordinator
        val selected = playback
        val recorder = fadeRecorder
        val checkCurrent = {
            check(coordinator === owner && playback === selected) { "Glow route owner changed during graph refresh" }
        }
        layoutTimer?.stop()
        checkCurrent()
        val previousGraph = graph
        val newGraph =
            try {
                buildGraph()
            } catch (exception: RuntimeException) {
                fail(exception)
                return
            }
        checkCurrent()
        val event =
            if (previousGraph == null) {
                RouteEvent.Activate(
                    graph = newGraph,
                    focusedSurfaceId = focusedSurfaceId(),
                    powerSaveEnabled = PowerSaveMode.isEnabled(),
                )
            } else {
                RouteEvent.GraphChanged(newGraph)
            }
        graph = newGraph
        routeOverlays.forEach { overlay ->
            fadeRouteOverlay(overlay, overlay.id in newGraph.surfaces, recorder, checkCurrent)
        }
        handle(event)
    }

    private fun buildGraph(): RouteGraph {
        val currentSettings = if (playback == null) settings() else null
        val currentOverlays = observeOverlays()
        routeOverlays = currentOverlays
        val surfaces =
            currentOverlays.mapNotNull { overlay ->
                if (currentSettings?.isIslandEnabled(overlay.id) == false) return@mapNotNull null
                if (!overlay.host.isShowing || !overlay.host.isDisplayable) return@mapNotNull null
                if (overlay.pane.width <= 0 || overlay.pane.height <= 0) return@mapNotNull null
                val window = SwingUtilities.getWindowAncestor(overlay.host) ?: return@mapNotNull null
                val surfaceOrigin = Point(0, 0)
                SwingUtilities.convertPointToScreen(surfaceOrigin, overlay.pane)
                val rootOrigin = Point(0, 0)
                SwingUtilities.convertPointToScreen(rootOrigin, overlay.layeredPane)
                val rootId =
                    rootIds[overlay.layeredPane]
                        ?: RouteRootId(nextRootId++).also { created ->
                            rootIds[overlay.layeredPane] = created
                        }
                roots[rootId] = RouteRoot(overlay.layeredPane, window, Point(rootOrigin))
                RouteSurface(
                    id = overlay.id,
                    rootId = rootId,
                    track =
                        overlay.pane
                            .routeTrackSnapshot()
                            .translated(surfaceOrigin.x.toFloat(), surfaceOrigin.y.toFloat()),
                    isEditor = overlay.id == EDITOR_ID,
                    windowKind = windowKind(overlay.id),
                    inwardEdges = overlay.pane.waveformInwardEdges.toSet(),
                )
            }
        val builtGraph =
            RouteGraphBuilder(
                maximumGap = JBUI.scale(MAXIMUM_GAP).toFloat(),
                canBridge = { source, target ->
                    if (source == target) {
                        true
                    } else {
                        val sourceWindow = roots[source]?.window
                        val targetWindow = roots[target]?.window
                        sourceWindow != null &&
                            targetWindow != null &&
                            WindowBridgeCapability.isSupported(sourceWindow, targetWindow)
                    }
                },
            ).build(surfaces)
        val availableGraph =
            failedConnectorIds.fold(builtGraph) { current, connectorId ->
                current.without(connectorId)
            }
        cacheBridgeTargets(availableGraph)
        return availableGraph
    }

    private fun windowKind(surfaceId: String): RouteWindowKind {
        if (surfaceId == EDITOR_ID) return RouteWindowKind.MAIN
        val toolWindow =
            ToolWindowManager
                .getInstance(project)
                .getToolWindow(surfaceId)
                ?: return RouteWindowKind.OTHER
        return when (toolWindow.type) {
            ToolWindowType.FLOATING -> RouteWindowKind.FLOATING_TOOL_WINDOW
            ToolWindowType.WINDOWED -> RouteWindowKind.WINDOWED_TOOL_WINDOW
            ToolWindowType.DOCKED,
            ToolWindowType.SLIDING,
            -> RouteWindowKind.DOCKED_TOOL_WINDOW
        }
    }

    private fun applyUpdate(update: RouteUpdate) {
        val currentOverlays = routeOverlays
        when (update.timerDirective) {
            TimerDirective.KEEP -> Unit
            TimerDirective.START -> {
                if (timer == null) {
                    val createdTimer =
                        createRouteTimer(FRAME_INTERVAL_MS) {
                            handle(RouteEvent.Tick(System.currentTimeMillis()))
                        }
                    timer = createdTimer
                    createdTimer.isCoalesce = true
                    createdTimer.start()
                }
            }

            TimerDirective.STOP -> {
                timer?.stop()
                timer = null
            }
        }
        val frame = update.frame ?: lastFrame?.takeIf { update.timerDirective == TimerDirective.KEEP }
        if (frame != null) {
            lastFrame = frame
            val style = captureRouteStyle(playback, currentOverlays, overlays, frame.signal.config)
            if (style == null) {
                clearRendering()
            } else {
                renderFrame(frame, style)
            }
        } else if (update.timerDirective == TimerDirective.STOP) {
            clearRendering()
        }
    }

    private fun renderFrame(
        frame: RouteFrame,
        style: RouteLayerStyle,
    ) {
        val rootSlices = mutableMapOf<RouteRootId, MutableList<RouteSlice>>()
        frame.slices.forEach { slice ->
            val rootId = (slice.target as? RoutePaintTarget.Root)?.rootId ?: return@forEach
            rootSlices.getOrPut(rootId) { mutableListOf() } += slice
        }
        for ((rootId, slices) in rootSlices) {
            val root = roots[rootId] ?: continue
            val layer =
                layers[rootId]
                    ?: createLayer(rootId).also { created ->
                        root.layeredPane.add(created)
                        root.layeredPane.setLayer(created, JLayeredPane.PALETTE_LAYER + 1)
                        layers[rootId] = created
                    }
            layer.setBounds(0, 0, root.layeredPane.width, root.layeredPane.height)
            layer.updateStyle(style)
            val surfaceBounds =
                routeOverlays
                    .asSequence()
                    .filter { overlay -> overlay.layeredPane === root.layeredPane }
                    .associate { overlay -> overlay.id to Rectangle(overlay.pane.bounds) }
            layer.showFrame(
                frame = frame,
                slices = slices.map { slice -> slice.relativeTo(root.screenOrigin) },
                surfaceBounds = surfaceBounds,
            )
            if (!isActive) return
        }
        layers
            .filterKeys { rootId -> rootId !in rootSlices }
            .values
            .forEach(WaveformRouteLayer::clearFrame)
        renderBridge(frame, style)
        if (!isActive) return
        pruneRendering(frame)
    }

    private fun createLayer(rootId: RouteRootId): WaveformRouteLayer {
        val owner = coordinator
        lateinit var layer: WaveformRouteLayer
        layer =
            WaveformRouteLayer(rootId) { exception ->
                if (coordinator === owner && layers[rootId] === layer) {
                    fail(exception)
                } else {
                    onFailure(exception)
                }
            }
        return layer
    }

    private fun renderBridge(
        frame: RouteFrame,
        style: RouteLayerStyle,
    ) {
        val renderVersion = ++bridgeRenderVersion
        val bridgeSlices =
            frame.slices.mapNotNull { slice ->
                val target = slice.target as? RoutePaintTarget.WindowBridge ?: return@mapNotNull null
                target.connectorId to slice
            }
        val batches = linkedMapOf<BridgeTarget, MutableList<RouteSlice>>()
        bridgeSlices.forEach { (connectorId, slice) ->
            val target =
                bridgeTargets[connectorId]
                    ?.minByOrNull { candidate -> candidate.distanceTo(slice) }
                    ?: return@forEach
            batches.getOrPut(target) { mutableListOf() } += slice
        }
        batches.keys
            .groupBy { target -> target.connector.id }
            .forEach { (connectorId, selectedTargets) ->
                bridgeTargets[connectorId] =
                    (currentBridgeTargets[connectorId].orEmpty() + selectedTargets)
                        .distinct()
                        .toMutableList()
            }
        val visibleConnectors = batches.keys.map { target -> target.connector }.toSet()
        batches.forEach { (target, slices) ->
            val currentBridge = bridgeFor(target.connector) ?: return@forEach
            if (renderVersion != bridgeRenderVersion) return
            currentBridge.show(target.owner, target.connector, frame, slices, style)
            if (renderVersion != bridgeRenderVersion) return
        }
        val cachedConnectors =
            bridgeTargets.values
                .flatten()
                .map { target -> target.connector }
                .toSet()
        bridges
            .filterKeys { connector -> connector !in visibleConnectors }
            .forEach { (connector, bridge) ->
                if (connector in cachedConnectors) {
                    bridge.hide()
                } else {
                    disposeBridge(connector, bridge)
                }
                if (renderVersion != bridgeRenderVersion) return
            }
    }

    private fun bridgeFor(connector: RouteConnector): CrossWindowBridge? {
        bridges
            .filter { (candidate, bridge) -> candidate.id == connector.id && bridge.hasPendingCleanup }
            .forEach(::disposeBridge)
        if (connector.id in failedConnectorIds) return null
        if (bridges.any { (candidate, bridge) -> candidate.id == connector.id && bridge.hasPendingCleanup }) return null
        return bridges.getOrPut(connector) {
            val owner = coordinator
            lateinit var bridge: CrossWindowBridge
            bridge =
                CrossWindowBridge(onFailure = { failedConnectorId ->
                    if (coordinator === owner && bridges[connector] === bridge) recordBridgeFailure(failedConnectorId)
                })
            bridge
        }
    }

    private fun disposeBridge(
        connector: RouteConnector,
        bridge: CrossWindowBridge,
    ) {
        bridge.dispose()
        if (!bridge.hasPendingCleanup && bridges[connector] === bridge) bridges.remove(connector)
    }

    private fun observeOverlays(): List<RouteOverlay> {
        val available = overlays()
        val selected = playback ?: return available
        captureRouteAppearance(selected, available)
        return selected.targets
    }

    private fun fail(exception: RuntimeException) {
        val owner = coordinator
        val selected = playback
        val recorder = fadeRecorder
        val targets = selected?.targets ?: overlays()
        check(coordinator === owner && playback === selected) { "Glow route owner changed before failure cleanup" }
        val shouldReport = lastFailure == null
        lastFailure = exception
        layoutTimer?.stop()
        check(coordinator === owner && playback === selected) { "Glow route owner changed during failure cleanup" }
        timer?.stop()
        check(coordinator === owner && playback === selected) { "Glow route owner changed during failure cleanup" }
        coordinator = null
        layoutTimer = null
        timer = null
        graph = null
        lastFrame = null
        val checkRetired = {
            check(coordinator == null && playback === selected) { "Glow route owner changed during failure cleanup" }
        }
        resetRouteResources(checkRetired)
        val focusedId = focusedSurfaceId()
        checkRetired()
        targets.forEach { overlay ->
            overlay.pane.failRouteWaveform(exception, selected)
            checkRetired()
            overlay.pane.configureRouteMode(enabled = false)
            checkRetired()
            fadeRouteOverlay(overlay, overlay.id == focusedId, recorder, checkRetired)
        }
        routeOverlays = emptyList()
        if (shouldReport) {
            onFailure(exception)
        }
    }

    private fun cacheBridgeTargets(routeGraph: RouteGraph) {
        val preservedTargets =
            lastFrame
                ?.slices
                .orEmpty()
                .mapNotNull { slice ->
                    val connectorId =
                        (slice.target as? RoutePaintTarget.WindowBridge)?.connectorId
                            ?: return@mapNotNull null
                    bridgeTargets[connectorId]
                        ?.minByOrNull { candidate -> candidate.distanceTo(slice) }
                        ?.let { target -> connectorId to target }
                }.distinct()
        val targets =
            routeGraph.connectors.values
                .asSequence()
                .flatten()
                .filter(RouteConnector::requiresWindowBridge)
                .mapNotNull { connector ->
                    val rootId = routeGraph.surfaces[connector.sourceId]?.rootId ?: return@mapNotNull null
                    val owner = roots[rootId]?.window ?: return@mapNotNull null
                    connector.id to BridgeTarget(connector, owner)
                }.groupBy(
                    keySelector = Pair<RouteConnectorId, BridgeTarget>::first,
                    valueTransform = Pair<RouteConnectorId, BridgeTarget>::second,
                ).mapValues { (_, targetsForConnector) -> targetsForConnector.distinct() }
        currentBridgeTargets = targets
        bridgeTargets.clear()
        targets.forEach { (connectorId, currentTargets) ->
            bridgeTargets[connectorId] = currentTargets.toMutableList()
        }
        preservedTargets.forEach { (connectorId, target) ->
            val retainedTargets = bridgeTargets.getOrPut(connectorId) { mutableListOf() }
            if (target !in retainedTargets) retainedTargets += target
        }
    }

    private fun recordBridgeFailure(connectorId: RouteConnectorId) {
        val owner = coordinator
        failedConnectorIds += connectorId
        graph = graph?.without(connectorId)
        bridgeTargets.remove(connectorId)
        currentBridgeTargets = currentBridgeTargets - connectorId
        bridges
            .filterKeys { connector -> connector.id == connectorId }
            .forEach(::disposeBridge)
        if (coordinator === owner && asyncUpdatesAllowed()) handle(RouteEvent.BridgeFailed(connectorId))
    }

    private fun resetRouteResources(checkCurrent: () -> Unit = {}) {
        checkCurrent()
        bridgeRenderVersion++
        layers.toMap().forEach { (rootId, layer) ->
            checkCurrent()
            layer.clearFrame()
            checkCurrent()
            (layer.parent as? JLayeredPane)?.remove(layer)
            checkCurrent()
            if (layers[rootId] === layer) layers.remove(rootId)
        }
        bridges.toMap().forEach { (connector, bridge) ->
            checkCurrent()
            disposeBridge(connector, bridge)
            checkCurrent()
        }
        roots.clear()
        rootIds.clear()
        bridgeTargets.clear()
        currentBridgeTargets = emptyMap()
        failedConnectorIds.clear()
        nextRootId = 1
    }

    private fun clearRendering() {
        lastFrame = null
        layers.values.forEach { layer ->
            layer.clearFrame()
            (layer.parent as? JLayeredPane)?.remove(layer)
        }
        layers.clear()
        bridges.values.toList().forEach(CrossWindowBridge::hide)
        pruneRendering(frame = null)
    }

    private fun pruneRendering(frame: RouteFrame?) {
        val retainedRoots =
            graph
                ?.surfaces
                ?.values
                ?.mapTo(mutableSetOf(), RouteSurface::rootId)
                ?: mutableSetOf()
        frame
            ?.slices
            ?.mapNotNullTo(retainedRoots) { slice ->
                (slice.target as? RoutePaintTarget.Root)?.rootId
            }
        layers.keys
            .filter { rootId -> rootId !in retainedRoots }
            .forEach { rootId ->
                layers.remove(rootId)?.let { layer ->
                    layer.clearFrame()
                    (layer.parent as? JLayeredPane)?.remove(layer)
                }
            }
        roots.keys.retainAll(retainedRoots)
        rootIds.entries.removeIf { entry -> entry.value !in retainedRoots }

        val retainedConnectors =
            graph
                ?.connectors
                ?.values
                ?.asSequence()
                ?.flatten()
                ?.mapTo(mutableSetOf(), RouteConnector::id)
                ?: mutableSetOf()
        frame
            ?.slices
            ?.mapNotNullTo(retainedConnectors) { slice ->
                (slice.target as? RoutePaintTarget.WindowBridge)?.connectorId
            }
        bridgeTargets.keys.retainAll(retainedConnectors)
        currentBridgeTargets = currentBridgeTargets.filterKeys(retainedConnectors::contains)
        bridges
            .filterKeys { connector -> connector.id !in retainedConnectors }
            .forEach(::disposeBridge)
    }

    private companion object {
        private const val EDITOR_ID = "Editor"
        private const val FRAME_INTERVAL_MS = 16
        private const val LAYOUT_DEBOUNCE_MS = 80
        private const val MAXIMUM_GAP = 24
    }
}

/** Platform style observation stays separate from route lifecycle and native resource ownership. */
internal fun captureRouteStyle(
    selected: RoutePlayback?,
    currentOverlays: List<RouteOverlay>,
    available: () -> List<RouteOverlay>,
    config: WaveformConfig,
): RouteLayerStyle? {
    val appearance =
        if (selected == null) {
            currentOverlays.firstOrNull()?.pane?.captureAppearance()
        } else {
            captureRouteAppearance(selected, available())
        } ?: return null
    return RouteLayerStyle(
        accent = appearance.color,
        glowStyle = appearance.style,
        intensity = appearance.intensity,
        width = appearance.width,
        arcWidth = UIManager.getInt(ISLAND_ARC_KEY).let { if (it > 0) it else DEFAULT_ARC_FALLBACK },
        config = config,
    )
}

/** Both graph reconciliation and failure fallback retain the timer before native scheduling. */
private fun fadeRouteOverlay(
    overlay: RouteOverlay,
    isVisible: Boolean,
    recorder: ((RouteOverlay, Timer) -> Unit)?,
    checkCurrent: () -> Unit,
) {
    checkCurrent()
    val record: (Timer) -> Unit = { created ->
        checkCurrent()
        recorder?.invoke(overlay, created)
        checkCurrent()
    }
    if (isVisible) overlay.pane.startFadeIn(record) else overlay.pane.startFadeOut(record)
    checkCurrent()
}

private fun RouteSlice.relativeTo(origin: Point): RouteSlice =
    copy(
        samples =
            samples.map { sample ->
                sample.copy(
                    x = sample.x - origin.x,
                    y = sample.y - origin.y,
                )
            },
    )

private fun BridgeTarget.distanceTo(slice: RouteSlice): Float {
    val sample = slice.samples.firstOrNull() ?: return Float.MAX_VALUE
    val sourceDistance =
        (sample.x - connector.sourcePoint.x) * (sample.x - connector.sourcePoint.x) +
            (sample.y - connector.sourcePoint.y) * (sample.y - connector.sourcePoint.y)
    val targetDistance =
        (sample.x - connector.targetPoint.x) * (sample.x - connector.targetPoint.x) +
            (sample.y - connector.targetPoint.y) * (sample.y - connector.targetPoint.y)
    return minOf(sourceDistance, targetDistance)
}

private const val ISLAND_ARC_KEY = "Island.arc"
private const val DEFAULT_ARC_FALLBACK = 8
