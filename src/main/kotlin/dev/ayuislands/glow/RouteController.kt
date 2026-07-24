package dev.ayuislands.glow

import com.intellij.ide.PowerSaveMode
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.openapi.wm.ToolWindowType
import com.intellij.util.ui.JBUI
import dev.ayuislands.glow.waveform.CrossWindowBridge
import dev.ayuislands.glow.waveform.RouteConnector
import dev.ayuislands.glow.waveform.RouteConnectorId
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
import dev.ayuislands.glow.waveform.WaveformBaseline
import dev.ayuislands.glow.waveform.WaveformConfig
import dev.ayuislands.glow.waveform.WaveformMovement
import dev.ayuislands.glow.waveform.WaveformRouteCoordinator
import dev.ayuislands.glow.waveform.WaveformRouteLayer
import dev.ayuislands.glow.waveform.WindowBridgeCapability
import dev.ayuislands.settings.AyuIslandsState
import dev.ayuislands.settings.effectiveLoopSeconds
import dev.ayuislands.settings.effectiveTraceDensity
import dev.ayuislands.settings.effectiveTraceLength
import java.awt.Point
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
    private val state: () -> AyuIslandsState,
    private val onFailure: (RuntimeException) -> Unit,
) {
    private var coordinator: WaveformRouteCoordinator? = null
    private var timer: Timer? = null
    private var layoutTimer: Timer? = null
    private var bridge: CrossWindowBridge? = null
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
    private var isFailureLogged = false

    val isActive: Boolean
        get() = coordinator != null

    fun configure(config: WaveformConfig) {
        if (coordinator == null) {
            coordinator = WaveformRouteCoordinator(config)
            isFailureLogged = false
        } else {
            dispatch(RouteEvent.Configure(config))
        }
        rebuildGraph()
    }

    fun handle(event: RouteEvent): Boolean = dispatch(event)

    private fun dispatch(event: RouteEvent): Boolean {
        val current = coordinator ?: return false
        try {
            applyUpdate(current.handle(event))
        } catch (exception: RuntimeException) {
            fail(exception)
        }
        return true
    }

    fun scheduleGraphRefresh() {
        if (!isActive) return
        val current =
            layoutTimer
                ?: Timer(LAYOUT_DEBOUNCE_MS) {
                    rebuildGraph()
                }.also {
                    it.isRepeats = false
                    layoutTimer = it
                }
        current.restart()
    }

    fun dispose() {
        layoutTimer?.stop()
        timer?.stop()
        layers.values.forEach { layer ->
            layer.clearFrame()
            (layer.parent as? JLayeredPane)?.remove(layer)
        }
        layers.clear()
        bridge?.dispose()
        roots.clear()
        rootIds.clear()
        bridgeTargets.clear()
        currentBridgeTargets = emptyMap()
        failedConnectorIds.clear()
        overlays().forEach { overlay -> overlay.pane.configureRouteMode(enabled = false) }
        routeOverlays = emptyList()
        layoutTimer = null
        timer = null
        coordinator = null
        bridge = null
        nextRootId = 1
        graph = null
        lastFrame = null
        isFailureLogged = false
    }

    private fun rebuildGraph() {
        if (!isActive) return
        layoutTimer?.stop()
        val previousGraph = graph
        val newGraph =
            try {
                buildGraph()
            } catch (exception: RuntimeException) {
                fail(exception)
                return
            }
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
            if (overlay.id in newGraph.surfaces) {
                overlay.pane.startFadeIn()
            } else {
                overlay.pane.startFadeOut()
            }
        }
        dispatch(event)
    }

    private fun buildGraph(): RouteGraph {
        val currentState = state()
        val currentOverlays = overlays()
        routeOverlays = currentOverlays
        val surfaces =
            currentOverlays.mapNotNull { overlay ->
                if (!currentState.isIslandEnabled(overlay.id)) return@mapNotNull null
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
                    timer =
                        Timer(FRAME_INTERVAL_MS) {
                            dispatch(RouteEvent.Tick(System.currentTimeMillis()))
                        }.also { routeTimer ->
                            routeTimer.isCoalesce = true
                            routeTimer.start()
                        }
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
            val style = currentStyle(currentOverlays)
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
                    ?: WaveformRouteLayer(rootId, ::fail).also { created ->
                        root.layeredPane.add(created)
                        root.layeredPane.setLayer(created, JLayeredPane.PALETTE_LAYER + 1)
                        layers[rootId] = created
                    }
            layer.setBounds(0, 0, root.layeredPane.width, root.layeredPane.height)
            layer.updateStyle(style)
            layer.showFrame(frame, slices.map { slice -> slice.relativeTo(root.screenOrigin) })
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

    private fun renderBridge(
        frame: RouteFrame,
        style: RouteLayerStyle,
    ) {
        val slice =
            frame.slices.singleOrNull { routeSlice ->
                routeSlice.target is RoutePaintTarget.WindowBridge
            }
        if (slice == null) {
            bridge?.hide()
            return
        }
        val connectorId = (slice.target as RoutePaintTarget.WindowBridge).connectorId
        val target =
            bridgeTargets[connectorId]
                ?.minByOrNull { candidate -> candidate.distanceTo(slice) }
        if (target == null) {
            bridge?.hide()
            return
        }
        if (target in currentBridgeTargets[connectorId].orEmpty()) {
            bridgeTargets[connectorId] =
                currentBridgeTargets.getValue(connectorId).toMutableList()
        }
        val currentBridge =
            bridge
                ?: CrossWindowBridge(onFailure = { failedConnectorId ->
                    recordBridgeFailure(failedConnectorId)
                }).also { bridge = it }
        currentBridge.show(target.owner, target.connector, frame, slice, style)
    }

    private fun currentStyle(currentOverlays: List<RouteOverlay>): RouteLayerStyle? {
        val pane = currentOverlays.firstOrNull()?.pane ?: return null
        return RouteLayerStyle(
            accent = pane.glowColor,
            glowStyle = pane.glowStyle,
            intensity = pane.glowIntensity,
            width = pane.glowWidth,
            arcWidth =
                UIManager
                    .getInt(ISLAND_ARC_KEY)
                    .let { if (it > 0) it else DEFAULT_ARC_FALLBACK },
            config = resolveWaveformConfig(state()),
        )
    }

    private fun fail(exception: RuntimeException) {
        layoutTimer?.stop()
        timer?.stop()
        coordinator = null
        layoutTimer = null
        timer = null
        graph = null
        lastFrame = null
        layers.values.forEach { layer ->
            layer.clearFrame()
            (layer.parent as? JLayeredPane)?.remove(layer)
        }
        layers.clear()
        bridge?.dispose()
        bridge = null
        roots.clear()
        rootIds.clear()
        bridgeTargets.clear()
        currentBridgeTargets = emptyMap()
        failedConnectorIds.clear()
        nextRootId = 1
        val focusedId = focusedSurfaceId()
        overlays().forEach { overlay ->
            overlay.pane.failRouteWaveform(exception)
            overlay.pane.configureRouteMode(enabled = false)
            if (overlay.id == focusedId) {
                overlay.pane.startFadeIn()
            } else {
                overlay.pane.startFadeOut()
            }
        }
        routeOverlays = emptyList()
        if (!isFailureLogged) {
            isFailureLogged = true
            onFailure(exception)
        }
    }

    private fun cacheBridgeTargets(routeGraph: RouteGraph) {
        val activeSlice =
            lastFrame?.slices?.singleOrNull { slice ->
                slice.target is RoutePaintTarget.WindowBridge
            }
        val activeConnectorId =
            (activeSlice?.target as? RoutePaintTarget.WindowBridge)?.connectorId
        val preservedTarget =
            if (activeSlice != null && activeConnectorId != null) {
                bridgeTargets[activeConnectorId]
                    ?.minByOrNull { candidate -> candidate.distanceTo(activeSlice) }
            } else {
                null
            }
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
        if (activeConnectorId != null && preservedTarget != null) {
            val retainedTargets = bridgeTargets.getOrPut(activeConnectorId) { mutableListOf() }
            if (preservedTarget !in retainedTargets) retainedTargets += preservedTarget
        }
    }

    private fun recordBridgeFailure(connectorId: RouteConnectorId) {
        failedConnectorIds += connectorId
        graph = graph?.without(connectorId)
        bridgeTargets.remove(connectorId)
        currentBridgeTargets = currentBridgeTargets - connectorId
        bridge?.hide()
        handle(RouteEvent.BridgeFailed(connectorId))
    }

    private fun clearRendering() {
        lastFrame = null
        layers.values.forEach { layer ->
            layer.clearFrame()
            (layer.parent as? JLayeredPane)?.remove(layer)
        }
        layers.clear()
        bridge?.hide()
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
    }

    private companion object {
        private const val EDITOR_ID = "Editor"
        private const val FRAME_INTERVAL_MS = 16
        private const val LAYOUT_DEBOUNCE_MS = 80
        private const val MAXIMUM_GAP = 24
    }
}

internal fun resolveWaveformConfig(state: AyuIslandsState): WaveformConfig =
    WaveformConfig(
        movement = WaveformMovement.fromName(state.waveformDirection),
        baseline = WaveformBaseline.fromName(state.waveformBaseline),
        traceDensity = state.effectiveTraceDensity(),
        traceLength = state.effectiveTraceLength(),
        amplitude = state.effectiveWaveformAmplitude(),
        intensity = state.effectiveWaveformIntensity(),
        loopSeconds = state.effectiveLoopSeconds(),
    )

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
