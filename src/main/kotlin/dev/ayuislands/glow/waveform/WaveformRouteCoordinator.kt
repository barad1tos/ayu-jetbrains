package dev.ayuislands.glow.waveform

import com.intellij.util.concurrency.annotations.RequiresEdt
import java.util.ArrayDeque
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.random.Random

private const val ROUTE_EPSILON = 0.001f
private const val ROUTE_SAMPLE_STEP = 2f
private const val RECOVERY_DURATION_MS = 160f
private const val HEAD_LEAD = 1f
private const val MAX_TICK_TRANSITIONS = 10_000
private const val ROUTE_MILLIS_PER_SECOND = 1_000f

internal sealed interface RouteEvent {
    data class Activate(
        val graph: RouteGraph,
        val focusedSurfaceId: String?,
        val powerSaveEnabled: Boolean,
    ) : RouteEvent

    data class Tick(
        val nowMs: Long,
    ) : RouteEvent

    data class Keystroke(
        val nowMs: Long,
    ) : RouteEvent

    data class Configure(
        val config: WaveformConfig,
    ) : RouteEvent

    data class GraphChanged(
        val graph: RouteGraph,
    ) : RouteEvent

    data class ApplicationActiveChanged(
        val active: Boolean,
    ) : RouteEvent

    data class PowerSaveChanged(
        val enabled: Boolean,
    ) : RouteEvent

    data class BridgeFailed(
        val connectorId: RouteConnectorId,
    ) : RouteEvent

    data object Deactivate : RouteEvent
}

internal sealed interface RoutePaintTarget {
    data class Root(
        val rootId: RouteRootId,
    ) : RoutePaintTarget

    data class WindowBridge(
        val connectorId: RouteConnectorId,
    ) : RoutePaintTarget
}

internal data class RouteSlice(
    val target: RoutePaintTarget,
    val surfaceId: String?,
    val samples: List<WaveformSample>,
    val distanceOffset: Float,
    val inwardEdges: Set<WaveformEdge>,
)

internal data class RouteFrame(
    val signal: WaveformFrame,
    val centerDistance: Float,
    val signalSpan: Float,
    val currentSurfaceId: String,
    val visibleSurfaceIds: Set<String>,
    val slices: List<RouteSlice>,
    val alpha: Float = 1f,
)

internal data class RouteUpdate(
    val timerDirective: TimerDirective = TimerDirective.KEEP,
    val frame: RouteFrame? = null,
)

internal data class RouteSnapshot(
    val currentSurfaceId: String?,
    val distanceOnLeg: Float,
    val direction: TravelDirection?,
    val plannedTargetId: String?,
)

private sealed interface LifecycleState {
    data object Dormant : LifecycleState

    data class Empty(
        val fallbackSurfaceId: String?,
    ) : LifecycleState,
        LiveState

    data class Routing(
        val leg: RouteLeg,
        val distanceOnLeg: Float,
    ) : LifecycleState,
        LiveState

    data class Recovering(
        val elapsedMs: Float,
        val fallbackGraph: RouteGraph,
        val frame: RouteFrame,
    ) : LifecycleState,
        LiveState

    data class Suspended(
        val reasons: Set<SuspensionReason>,
        override val resumeState: LiveState,
        override val stableFrame: RouteFrame?,
    ) : SavedState {
        init {
            require(reasons.isNotEmpty()) { "Suspended route requires at least one reason" }
        }
    }

    data class Resuming(
        override val resumeState: LiveState,
        override val stableFrame: RouteFrame,
    ) : SavedState
}

private sealed interface LiveState : LifecycleState

private sealed interface SavedState : LifecycleState {
    val resumeState: LiveState
    val stableFrame: RouteFrame?
}

private sealed interface RouteLeg {
    val direction: TravelDirection
    val length: Float
    val samples: List<WaveformSample>
    val target: RoutePaintTarget
    val inwardEdges: Set<WaveformEdge>
    val currentSurfaceId: String
    val visibleSurfaceIds: Set<String>
    val sliceSurfaceId: String?
    val plannedTargetId: String?

    data class Perimeter(
        val surfaceId: String,
        val entryDistance: Float,
        override val direction: TravelDirection,
        val connector: RouteConnector?,
        val lapDistance: Float,
        override val length: Float,
        override val samples: List<WaveformSample>,
        override val target: RoutePaintTarget,
        override val inwardEdges: Set<WaveformEdge>,
        val signalSpan: Float,
    ) : RouteLeg {
        override val currentSurfaceId: String = surfaceId
        override val visibleSurfaceIds: Set<String> = setOf(surfaceId)
        override val sliceSurfaceId: String = surfaceId
        override val plannedTargetId: String? = connector?.targetId
    }

    data class Connector(
        val connector: RouteConnector,
        override val direction: TravelDirection,
        override val samples: List<WaveformSample>,
        override val target: RoutePaintTarget,
        val sourceSpeed: Float,
        val targetSpeed: Float,
        val sourceSpan: Float,
        val targetSpan: Float,
    ) : RouteLeg {
        override val length: Float = connector.length
        override val inwardEdges: Set<WaveformEdge> = emptySet()
        override val currentSurfaceId: String = connector.sourceId
        override val visibleSurfaceIds: Set<String> = setOf(connector.sourceId, connector.targetId)
        override val sliceSurfaceId: String? = null
        override val plannedTargetId: String = connector.targetId
    }
}

private enum class SuspensionReason {
    APPLICATION_INACTIVE,
    POWER_SAVE,
}

private sealed interface TargetPolicy {
    data object Select : TargetPolicy

    data class Preserve(
        val connector: RouteConnector?,
    ) : TargetPolicy
}

private data class PerimeterEntry(
    val distance: Float? = null,
    val direction: TravelDirection? = null,
    val targetPolicy: TargetPolicy = TargetPolicy.Select,
)

private data class Transition(
    val state: LifecycleState,
    val update: RouteUpdate,
)

private data class AdvanceResult(
    val state: LiveState,
    val remainingMs: Float,
)

private data class RouteSegment(
    val target: RoutePaintTarget,
    val surfaceId: String?,
    val samples: List<WaveformSample>,
    val distanceOffset: Float,
    val inwardEdges: Set<WaveformEdge>,
    val length: Float,
) {
    fun toSlice(): RouteSlice = RouteSlice(target, surfaceId, samples, distanceOffset, inwardEdges)
}

private val EMPTY_ROUTE_SNAPSHOT = RouteSnapshot(null, 0f, null, null)

private val CONNECTOR_ROUTE_ORDER =
    compareBy(
        RouteConnector::length,
        { connector -> connector.sourceSide.ordinal },
        { connector -> connector.targetSide.ordinal },
    )

internal class WaveformRouteCoordinator(
    initialConfig: WaveformConfig,
    random: Random = Random.Default,
) {
    private var config = initialConfig
    private val engine = WaveformEngine(initialConfig, random)
    private val planner = RoutePlanner(random)
    private val trail = RouteTrail()
    private val clock = RouteClock()
    private var graph = RouteGraph(emptyMap(), emptyMap())
    private var pendingGraph: RouteGraph? = null
    private var previousSurfaceId: String? = null
    private var lastFrame: RouteFrame? = null
    private var state: LifecycleState = LifecycleState.Dormant

    init {
        requireChaotic(initialConfig)
    }

    internal val snapshot: RouteSnapshot
        get() = snapshotOf(state)

    @RequiresEdt
    fun handle(event: RouteEvent): RouteUpdate {
        val transition =
            when (val current = state) {
                LifecycleState.Dormant -> handleDormant(event)
                is LifecycleState.Empty -> handleEmpty(current, event)
                is LifecycleState.Routing -> handleRouting(current, event)
                is LifecycleState.Recovering -> handleRecovery(current, event)
                is LifecycleState.Suspended -> handleSuspended(current, event)
                is LifecycleState.Resuming -> handleResuming(current, event)
            }
        state = transition.state
        transition.update.frame?.let { frame -> lastFrame = frame }
        return transition.update
    }

    private fun handleDormant(event: RouteEvent): Transition =
        when (event) {
            is RouteEvent.Activate -> activate(event)
            is RouteEvent.Configure -> configure(LifecycleState.Dormant, event.config)
            is RouteEvent.Tick,
            is RouteEvent.Keystroke,
            is RouteEvent.GraphChanged,
            is RouteEvent.ApplicationActiveChanged,
            is RouteEvent.PowerSaveChanged,
            is RouteEvent.BridgeFailed,
            RouteEvent.Deactivate,
            -> ignore(LifecycleState.Dormant)
        }

    private fun handleEmpty(
        current: LifecycleState.Empty,
        event: RouteEvent,
    ): Transition =
        when (event) {
            is RouteEvent.Configure -> configure(current, event.config)
            is RouteEvent.GraphChanged -> changeEmptyGraph(current, event.graph)
            is RouteEvent.ApplicationActiveChanged ->
                if (event.active) ignore(current) else suspend(current, SuspensionReason.APPLICATION_INACTIVE)

            is RouteEvent.PowerSaveChanged ->
                if (event.enabled) suspend(current, SuspensionReason.POWER_SAVE) else ignore(current)

            is RouteEvent.BridgeFailed -> {
                graph = graph.without(event.connectorId)
                ignore(current)
            }

            RouteEvent.Deactivate -> deactivate()
            is RouteEvent.Activate,
            is RouteEvent.Tick,
            is RouteEvent.Keystroke,
            -> ignore(current)
        }

    private fun handleRouting(
        current: LifecycleState.Routing,
        event: RouteEvent,
    ): Transition =
        when (event) {
            is RouteEvent.Tick -> tick(current, event.nowMs)
            is RouteEvent.Keystroke -> {
                engine.handle(WaveformEvent.Keystroke(clock.eventTime(event.nowMs)))
                ignore(current, lastFrame)
            }

            is RouteEvent.Configure -> configure(current, event.config)
            is RouteEvent.GraphChanged -> changeRoutingGraph(current, event.graph)
            is RouteEvent.ApplicationActiveChanged ->
                if (event.active) ignore(current) else suspend(current, SuspensionReason.APPLICATION_INACTIVE)

            is RouteEvent.PowerSaveChanged ->
                if (event.enabled) suspend(current, SuspensionReason.POWER_SAVE) else ignore(current)

            is RouteEvent.BridgeFailed -> failBridge(current, event.connectorId)
            RouteEvent.Deactivate -> deactivate()
            is RouteEvent.Activate -> ignore(current)
        }

    private fun handleRecovery(
        current: LifecycleState.Recovering,
        event: RouteEvent,
    ): Transition =
        when (event) {
            is RouteEvent.Tick -> tick(current, event.nowMs)
            is RouteEvent.Keystroke -> {
                engine.handle(WaveformEvent.Keystroke(clock.eventTime(event.nowMs)))
                ignore(current, lastFrame)
            }

            is RouteEvent.Configure -> configure(current, event.config)
            is RouteEvent.GraphChanged -> {
                if (event.graph.surfaces.isEmpty()) {
                    becomeEmpty(current.frame.currentSurfaceId)
                } else {
                    graph = event.graph
                    pendingGraph = null
                    Transition(current.copy(fallbackGraph = event.graph), RouteUpdate(frame = current.frame))
                }
            }
            is RouteEvent.ApplicationActiveChanged ->
                if (event.active) ignore(current) else suspend(current, SuspensionReason.APPLICATION_INACTIVE)

            is RouteEvent.PowerSaveChanged ->
                if (event.enabled) suspend(current, SuspensionReason.POWER_SAVE) else ignore(current)

            is RouteEvent.BridgeFailed -> {
                graph = graph.without(event.connectorId)
                Transition(
                    current.copy(fallbackGraph = current.fallbackGraph.without(event.connectorId)),
                    RouteUpdate(frame = current.frame),
                )
            }

            RouteEvent.Deactivate -> deactivate()
            is RouteEvent.Activate -> ignore(current)
        }

    private fun handleSuspended(
        current: LifecycleState.Suspended,
        event: RouteEvent,
    ): Transition =
        when (event) {
            is RouteEvent.ApplicationActiveChanged ->
                changeSuspension(current, SuspensionReason.APPLICATION_INACTIVE, isEnabled = !event.active)

            is RouteEvent.PowerSaveChanged ->
                changeSuspension(current, SuspensionReason.POWER_SAVE, isEnabled = event.enabled)

            is RouteEvent.Configure -> configure(current, event.config, current.stableFrame)
            is RouteEvent.GraphChanged -> updateSavedState(current, RouteEvent.GraphChanged(event.graph))
            is RouteEvent.BridgeFailed -> updateSavedState(current, RouteEvent.BridgeFailed(event.connectorId))
            RouteEvent.Deactivate -> deactivate()
            is RouteEvent.Tick -> ignore(current, current.stableFrame)
            is RouteEvent.Activate,
            is RouteEvent.Keystroke,
            -> ignore(current, current.stableFrame)
        }

    private fun handleResuming(
        current: LifecycleState.Resuming,
        event: RouteEvent,
    ): Transition =
        when (event) {
            is RouteEvent.Tick -> {
                clock.elapsed(event.nowMs)
                Transition(current.resumeState, RouteUpdate(frame = current.stableFrame))
            }

            is RouteEvent.Keystroke -> {
                engine.handle(WaveformEvent.Keystroke(clock.eventTime(event.nowMs)))
                ignore(current, current.stableFrame)
            }

            is RouteEvent.Configure -> configure(current, event.config, current.stableFrame)
            is RouteEvent.GraphChanged -> updateSavedState(current, RouteEvent.GraphChanged(event.graph))
            is RouteEvent.BridgeFailed -> updateSavedState(current, RouteEvent.BridgeFailed(event.connectorId))
            is RouteEvent.ApplicationActiveChanged -> {
                if (event.active) {
                    ignore(current, current.stableFrame)
                } else {
                    suspend(current.resumeState, SuspensionReason.APPLICATION_INACTIVE)
                }
            }

            is RouteEvent.PowerSaveChanged -> {
                if (event.enabled) {
                    suspend(current.resumeState, SuspensionReason.POWER_SAVE)
                } else {
                    ignore(current, current.stableFrame)
                }
            }

            RouteEvent.Deactivate -> deactivate()
            is RouteEvent.Activate -> ignore(current, current.stableFrame)
        }

    private fun activate(event: RouteEvent.Activate): Transition {
        graph = event.graph
        pendingGraph = null
        previousSurfaceId = null
        clock.reset()
        trail.reset()
        val selectedId = planner.selectInitialSurface(event.graph, event.focusedSurfaceId)
        if (selectedId == null) {
            engine.handle(WaveformEvent.Activate(powerSaveEnabled = false))
            val empty = LifecycleState.Empty(null)
            val nextState =
                if (event.powerSaveEnabled) {
                    LifecycleState.Suspended(setOf(SuspensionReason.POWER_SAVE), empty, null)
                } else {
                    empty
                }
            return Transition(nextState, RouteUpdate(TimerDirective.STOP))
        }

        val routing =
            LifecycleState.Routing(
                planner.createPerimeter(graph, previousSurfaceId, selectedId),
                0f,
            )
        val engineFrame = requireNotNull(engine.handle(WaveformEvent.Activate(powerSaveEnabled = false)).frame)
        val frame = trail.routeFrame(routing, engineFrame)
        return if (event.powerSaveEnabled) {
            Transition(
                LifecycleState.Suspended(setOf(SuspensionReason.POWER_SAVE), routing, frame),
                RouteUpdate(TimerDirective.STOP, frame),
            )
        } else {
            Transition(routing, RouteUpdate(TimerDirective.START, frame))
        }
    }

    private fun tick(
        current: LiveState,
        nowMs: Long,
    ): Transition {
        val elapsedMs = clock.elapsed(nowMs)
        val advanced = advance(current, elapsedMs.toFloat())
        val signal = engine.signalFrame(clock.engineTimeMs)
        val frame = trail.render(advanced, signal)
        val directive = if (advanced is LifecycleState.Empty) TimerDirective.STOP else TimerDirective.KEEP
        return Transition(advanced, RouteUpdate(directive, frame))
    }

    private fun configure(
        current: LifecycleState,
        updatedConfig: WaveformConfig,
        frame: RouteFrame? = lastFrame,
    ): Transition {
        requireChaotic(updatedConfig)
        config = updatedConfig
        engine.handle(WaveformEvent.Configure(updatedConfig))
        val configured =
            when (current) {
                LifecycleState.Dormant,
                is LifecycleState.Empty,
                is LifecycleState.Recovering,
                -> current

                is LifecycleState.Routing -> RouteMotion.rebaseConnector(current, graph, updatedConfig)
                is LifecycleState.Suspended ->
                    current.copy(resumeState = RouteMotion.rebaseConnector(current.resumeState, graph, updatedConfig))

                is LifecycleState.Resuming ->
                    current.copy(resumeState = RouteMotion.rebaseConnector(current.resumeState, graph, updatedConfig))
            }
        return ignore(configured, frame)
    }

    private fun changeEmptyGraph(
        current: LifecycleState.Empty,
        replacement: RouteGraph,
    ): Transition {
        graph = replacement
        pendingGraph = null
        if (replacement.surfaces.isEmpty()) return ignore(current)

        val selectedId = planner.selectFallbackSurface(replacement, current.fallbackSurfaceId)
        checkNotNull(selectedId) { "Non-empty route graph must provide a fallback surface" }
        val routing =
            LifecycleState.Routing(
                planner.createPerimeter(graph, previousSurfaceId, selectedId),
                0f,
            )
        clock.resetWallTick()
        val signal = engine.signalFrame(clock.engineTimeMs)
        return Transition(routing, RouteUpdate(TimerDirective.START, trail.routeFrame(routing, signal)))
    }

    private fun changeRoutingGraph(
        current: LifecycleState.Routing,
        replacement: RouteGraph,
    ): Transition {
        if (replacement.surfaces.isEmpty()) return becomeEmpty(current.leg.currentSurfaceId)
        if (!hasIdenticalTopology(graph, replacement)) {
            pendingGraph = replacement
            return ignore(current, lastFrame)
        }

        val rebound = rebindGeometry(current, replacement)
        graph = replacement
        pendingGraph = null
        trail.clearSegments()
        val frame = trail.routeFrame(rebound, lastFrame?.signal ?: engine.signalFrame(clock.engineTimeMs))
        return Transition(rebound, RouteUpdate(frame = frame))
    }

    private fun failBridge(
        current: LifecycleState.Routing,
        connectorId: RouteConnectorId,
    ): Transition {
        graph = graph.without(connectorId)
        pendingGraph = pendingGraph?.without(connectorId)
        trail.discardTarget(RoutePaintTarget.WindowBridge(connectorId))
        val leg = current.leg
        if (leg is RouteLeg.Connector && leg.connector.id == connectorId) {
            if (leg.target !is RoutePaintTarget.WindowBridge) {
                trail.recordPrefix(leg, current.distanceOnLeg)
            }
            trail.advance(current.distanceOnLeg)
            val perimeter =
                planner.createPerimeter(
                    graph = graph,
                    previousSurfaceId = previousSurfaceId,
                    surfaceId = leg.connector.sourceId,
                    entry =
                        PerimeterEntry(
                            distance = leg.connector.sourceDistance,
                            direction = leg.direction,
                        ),
                )
            val routing = LifecycleState.Routing(perimeter, 0f)
            val signal = lastFrame?.signal ?: engine.signalFrame(clock.engineTimeMs)
            return Transition(routing, RouteUpdate(frame = trail.routeFrame(routing, signal)))
        }
        if (leg is RouteLeg.Perimeter && leg.connector?.id == connectorId) {
            val center = trail.centerDistance(current)
            val entry = RouteMotion.perimeterPosition(leg, current.distanceOnLeg, graph)
            val perimeter =
                planner.createPerimeter(
                    graph,
                    previousSurfaceId,
                    leg.surfaceId,
                    PerimeterEntry(entry, leg.direction),
                )
            trail.keepCenter(center, 0f)
            trail.clearSegments()
            val routing = LifecycleState.Routing(perimeter, 0f)
            val signal = lastFrame?.signal ?: engine.signalFrame(clock.engineTimeMs)
            return Transition(routing, RouteUpdate(frame = trail.routeFrame(routing, signal)))
        }
        return ignore(current, lastFrame)
    }

    private fun suspend(
        current: LiveState,
        reason: SuspensionReason,
    ): Transition {
        clock.resetWallTick()
        val stableFrame = lastFrame
        return Transition(
            LifecycleState.Suspended(setOf(reason), current, stableFrame),
            RouteUpdate(TimerDirective.STOP, stableFrame),
        )
    }

    private fun changeSuspension(
        current: LifecycleState.Suspended,
        reason: SuspensionReason,
        isEnabled: Boolean,
    ): Transition {
        val reasons = if (isEnabled) current.reasons + reason else current.reasons - reason
        if (reasons == current.reasons) return ignore(current, current.stableFrame)
        if (reasons.isNotEmpty()) {
            val directive = if (isEnabled) TimerDirective.STOP else TimerDirective.KEEP
            return Transition(current.copy(reasons = reasons), RouteUpdate(directive, current.stableFrame))
        }

        clock.resetWallTick()
        if (current.resumeState is LifecycleState.Empty) {
            return Transition(current.resumeState, RouteUpdate(TimerDirective.STOP, current.stableFrame))
        }
        val stableFrame = checkNotNull(current.stableFrame) { "Resuming route requires a stable frame" }
        return Transition(
            LifecycleState.Resuming(current.resumeState, stableFrame),
            RouteUpdate(TimerDirective.START, stableFrame),
        )
    }

    private fun updateSavedState(
        current: SavedState,
        event: RouteEvent,
    ): Transition {
        state = current.resumeState
        val nested =
            when (val resume = current.resumeState) {
                is LifecycleState.Empty -> handleEmpty(resume, event)
                is LifecycleState.Routing -> handleRouting(resume, event)
                is LifecycleState.Recovering -> handleRecovery(resume, event)
            }
        if (current is LifecycleState.Resuming && nested.state is LifecycleState.Empty) return nested
        val resumeState = nested.state as? LiveState ?: error("Suspended update cannot leave the live lifecycle")
        val stableFrame = nested.update.frame ?: current.stableFrame
        val updated =
            when (current) {
                is LifecycleState.Suspended -> current.copy(resumeState = resumeState, stableFrame = stableFrame)
                is LifecycleState.Resuming ->
                    current.copy(
                        resumeState = resumeState,
                        stableFrame = checkNotNull(stableFrame) { "Resuming route requires a stable frame" },
                    )
            }
        val directive = if (current is LifecycleState.Suspended) TimerDirective.STOP else TimerDirective.KEEP
        return Transition(
            updated,
            RouteUpdate(directive, stableFrame),
        )
    }

    private fun deactivate(): Transition {
        engine.handle(WaveformEvent.Deactivate)
        graph = RouteGraph(emptyMap(), emptyMap())
        pendingGraph = null
        previousSurfaceId = null
        clock.resetWallTick()
        lastFrame = null
        trail.reset()
        return Transition(LifecycleState.Dormant, RouteUpdate(TimerDirective.STOP))
    }

    private fun becomeEmpty(fallbackSurfaceId: String?): Transition {
        graph = RouteGraph(emptyMap(), emptyMap())
        pendingGraph = null
        clock.resetWallTick()
        trail.clearSegments()
        lastFrame = null
        return Transition(LifecycleState.Empty(fallbackSurfaceId), RouteUpdate(TimerDirective.STOP))
    }

    private fun advance(
        initial: LiveState,
        elapsedMs: Float,
    ): LiveState {
        var current = initial
        var remainingMs = elapsedMs
        repeat(MAX_TICK_TRANSITIONS) {
            if (current is LifecycleState.Empty) return current
            val isInstantBoundary =
                current is LifecycleState.Routing &&
                    current.leg.length - current.distanceOnLeg <= ROUTE_EPSILON
            if (remainingMs <= ROUTE_EPSILON && !isInstantBoundary) return current
            when (current) {
                is LifecycleState.Routing -> {
                    val result = advanceRouting(current, remainingMs)
                    current = result.state
                    remainingMs = result.remainingMs
                }

                is LifecycleState.Recovering -> {
                    val result = advanceRecovery(current, remainingMs)
                    current = result.state
                    remainingMs = result.remainingMs
                }

                is LifecycleState.Empty -> return current
            }
        }
        error("Route tick exceeded $MAX_TICK_TRANSITIONS state transitions")
    }

    private fun advanceRouting(
        current: LifecycleState.Routing,
        availableMs: Float,
    ): AdvanceResult {
        val leg = current.leg
        val boundaryDistance = RouteMotion.nextBoundary(leg, current.distanceOnLeg)
        val boundaryMs = RouteMotion.boundaryTime(leg, current.distanceOnLeg, boundaryDistance, graph, config)
        if (availableMs + ROUTE_EPSILON < boundaryMs) {
            val distance = RouteMotion.distanceAfter(leg, current.distanceOnLeg, availableMs, graph, config)
            return AdvanceResult(current.copy(distanceOnLeg = distance), 0f)
        }

        val atBoundary = current.copy(distanceOnLeg = boundaryDistance)
        val remainingMs = (availableMs - boundaryMs).coerceAtLeast(0f)
        if (
            leg is RouteLeg.Perimeter &&
            abs(boundaryDistance - leg.lapDistance) <= ROUTE_EPSILON &&
            pendingGraph != null
        ) {
            return AdvanceResult(applyPendingGraph(atBoundary), remainingMs)
        }
        if (boundaryDistance >= leg.length - ROUTE_EPSILON) {
            return AdvanceResult(completeLeg(atBoundary), remainingMs)
        }
        return AdvanceResult(atBoundary, remainingMs)
    }

    private fun advanceRecovery(
        current: LifecycleState.Recovering,
        availableMs: Float,
    ): AdvanceResult {
        val remainingFadeMs = RECOVERY_DURATION_MS - current.elapsedMs
        if (availableMs + ROUTE_EPSILON < remainingFadeMs) {
            return AdvanceResult(current.copy(elapsedMs = current.elapsedMs + availableMs), 0f)
        }

        graph = current.fallbackGraph
        val selectedId =
            planner.selectFallbackSurface(graph, previousSurfaceId)
                ?: return AdvanceResult(
                    LifecycleState.Empty(current.frame.currentSurfaceId),
                    availableMs - remainingFadeMs,
                )
        trail.clearSegments()
        val routing =
            LifecycleState.Routing(
                planner.createPerimeter(graph, previousSurfaceId, selectedId),
                0f,
            )
        return AdvanceResult(routing, (availableMs - remainingFadeMs).coerceAtLeast(0f))
    }

    private fun applyPendingGraph(current: LifecycleState.Routing): LiveState {
        val leg = current.leg as? RouteLeg.Perimeter ?: error("Pending graph requires a perimeter boundary")
        trail.recordPrefix(leg, current.distanceOnLeg)
        trail.advance(current.distanceOnLeg)
        val replacement = checkNotNull(pendingGraph)
        pendingGraph = null
        val oldSurface = graph.surfaces.getValue(leg.surfaceId)
        graph = replacement
        val newSurface = replacement.surfaces[leg.surfaceId]
        if (newSurface == null) {
            val stable = checkNotNull(lastFrame) { "Recovery requires a stable route frame" }
            return LifecycleState.Recovering(
                0f,
                replacement,
                stable.copy(centerDistance = trail.distanceOffset, alpha = 1f),
            )
        }

        val entryRatio = leg.entryDistance / oldSurface.track.length
        val entryDistance = entryRatio * newSurface.track.length
        return LifecycleState.Routing(
            planner.createPerimeter(
                graph,
                previousSurfaceId,
                leg.surfaceId,
                PerimeterEntry(
                    distance = entryDistance,
                    direction = leg.direction,
                    targetPolicy = TargetPolicy.Preserve(leg.connector),
                ),
            ),
            0f,
        )
    }

    private fun completeLeg(current: LifecycleState.Routing): LiveState {
        val leg = current.leg
        trail.recordSegment(leg)
        trail.advance(leg.length)
        return when (leg) {
            is RouteLeg.Perimeter -> {
                val connector = leg.connector
                val isConnectorAvailable =
                    connector != null &&
                        graph.connectorsFrom(leg.surfaceId).any { candidate -> candidate == connector }
                if (!isConnectorAvailable) {
                    val entry = RouteMotion.perimeterPosition(leg, leg.length, graph)
                    LifecycleState.Routing(
                        planner.createPerimeter(
                            graph,
                            previousSurfaceId,
                            leg.surfaceId,
                            PerimeterEntry(entry, leg.direction),
                        ),
                        0f,
                    )
                } else {
                    LifecycleState.Routing(planner.createConnector(graph, config, connector, leg.direction), 0f)
                }
            }

            is RouteLeg.Connector -> {
                previousSurfaceId = leg.connector.sourceId
                if (graph.surfaces.containsKey(leg.connector.targetId)) {
                    LifecycleState.Routing(
                        planner.createPerimeter(
                            graph,
                            previousSurfaceId,
                            leg.connector.targetId,
                            PerimeterEntry(leg.connector.targetDistance, leg.direction.opposite),
                        ),
                        0f,
                    )
                } else {
                    val stable = checkNotNull(lastFrame) { "Recovery requires a stable route frame" }
                    LifecycleState.Recovering(
                        0f,
                        graph,
                        stable.copy(centerDistance = trail.distanceOffset, alpha = 1f),
                    )
                }
            }
        }
    }

    private fun rebindGeometry(
        current: LifecycleState.Routing,
        replacement: RouteGraph,
    ): LifecycleState.Routing {
        val oldCenter = trail.centerDistance(current)
        val oldGraph = graph
        graph = replacement
        val reboundLeg =
            when (val leg = current.leg) {
                is RouteLeg.Perimeter -> {
                    val oldTrack = oldGraph.surfaces.getValue(leg.surfaceId).track
                    val newTrack = replacement.surfaces.getValue(leg.surfaceId).track
                    planner.createPerimeter(
                        graph = replacement,
                        previousSurfaceId = previousSurfaceId,
                        surfaceId = leg.surfaceId,
                        entry =
                            PerimeterEntry(
                                distance = leg.entryDistance / oldTrack.length * newTrack.length,
                                direction = leg.direction,
                                targetPolicy = TargetPolicy.Preserve(leg.connector),
                            ),
                    )
                }

                is RouteLeg.Connector -> {
                    val reboundConnector =
                        replacement
                            .connectorsFrom(leg.connector.sourceId)
                            .first { connector ->
                                connector.id == leg.connector.id &&
                                    connector.targetId == leg.connector.targetId
                            }
                    planner.createConnector(replacement, config, reboundConnector, leg.direction)
                }
            }
        val progress = current.distanceOnLeg / current.leg.length
        val reboundDistance = progress.coerceIn(0f, 1f) * reboundLeg.length
        trail.keepCenter(oldCenter, reboundDistance)
        return LifecycleState.Routing(reboundLeg, reboundDistance)
    }
}

private fun snapshotOf(current: LifecycleState): RouteSnapshot =
    when (current) {
        LifecycleState.Dormant -> EMPTY_ROUTE_SNAPSHOT
        is LifecycleState.Empty -> EMPTY_ROUTE_SNAPSHOT
        is LifecycleState.Routing -> current.snapshot()
        is LifecycleState.Recovering ->
            RouteSnapshot(
                currentSurfaceId = current.frame.currentSurfaceId,
                distanceOnLeg = 0f,
                direction = current.frame.signal.direction,
                plannedTargetId = null,
            )

        is LifecycleState.Suspended -> snapshotOf(current.resumeState)
        is LifecycleState.Resuming -> snapshotOf(current.resumeState)
    }

private fun LifecycleState.Routing.snapshot(): RouteSnapshot =
    RouteSnapshot(
        currentSurfaceId = leg.currentSurfaceId,
        distanceOnLeg = distanceOnLeg,
        direction = leg.direction,
        plannedTargetId = leg.plannedTargetId,
    )

private object RouteMotion {
    fun rebaseConnector(
        current: LiveState,
        graph: RouteGraph,
        config: WaveformConfig,
    ): LiveState {
        val routing = current as? LifecycleState.Routing ?: return current
        val leg = routing.leg as? RouteLeg.Connector ?: return current
        val source = graph.surfaces.getValue(leg.connector.sourceId)
        val target = graph.surfaces.getValue(leg.connector.targetId)
        return routing.copy(
            leg =
                leg.copy(
                    sourceSpeed = perimeterSpeed(source, config),
                    targetSpeed = perimeterSpeed(target, config),
                ),
        )
    }

    fun nextBoundary(
        leg: RouteLeg,
        distance: Float,
    ): Float =
        if (leg is RouteLeg.Perimeter && distance < leg.lapDistance - ROUTE_EPSILON) {
            leg.lapDistance
        } else {
            leg.length
        }

    fun boundaryTime(
        leg: RouteLeg,
        startDistance: Float,
        targetDistance: Float,
        graph: RouteGraph,
        config: WaveformConfig,
    ): Float {
        if (targetDistance - startDistance <= ROUTE_EPSILON) return 0f
        return when (leg) {
            is RouteLeg.Perimeter ->
                (targetDistance - startDistance) /
                    perimeterSpeed(graph.surfaces.getValue(leg.surfaceId), config)

            is RouteLeg.Connector -> connectorTime(leg, startDistance, targetDistance)
        }
    }

    fun distanceAfter(
        leg: RouteLeg,
        startDistance: Float,
        elapsedMs: Float,
        graph: RouteGraph,
        config: WaveformConfig,
    ): Float {
        if (leg.length <= ROUTE_EPSILON) return leg.length
        return when (leg) {
            is RouteLeg.Perimeter ->
                startDistance +
                    perimeterSpeed(graph.surfaces.getValue(leg.surfaceId), config) * elapsedMs

            is RouteLeg.Connector -> connectorDistance(leg, startDistance, elapsedMs)
        }.coerceAtMost(leg.length)
    }

    fun perimeterSpeed(
        surface: RouteSurface,
        config: WaveformConfig,
    ): Float = surface.track.length / (config.loopSeconds.normalizedLoopSeconds() * ROUTE_MILLIS_PER_SECOND)

    fun perimeterPosition(
        leg: RouteLeg.Perimeter,
        distance: Float,
        graph: RouteGraph,
    ): Float =
        wrap(
            leg.entryDistance + distance * leg.direction.travelSign,
            graph.surfaces
                .getValue(leg.surfaceId)
                .track.length,
        )

    fun signalSpan(current: LifecycleState.Routing): Float =
        when (val leg = current.leg) {
            is RouteLeg.Perimeter -> leg.signalSpan
            is RouteLeg.Connector -> {
                val progress =
                    if (leg.length <= ROUTE_EPSILON) {
                        1f
                    } else {
                        (current.distanceOnLeg / leg.length).coerceIn(0f, 1f)
                    }
                leg.sourceSpan + (leg.targetSpan - leg.sourceSpan) * progress
            }
        }

    private fun connectorTime(
        leg: RouteLeg.Connector,
        startDistance: Float,
        targetDistance: Float,
    ): Float {
        if (leg.length <= ROUTE_EPSILON) return 0f
        val speedDelta = leg.targetSpeed - leg.sourceSpeed
        if (abs(speedDelta) <= ROUTE_EPSILON) return (targetDistance - startDistance) / leg.sourceSpeed
        val rate = speedDelta / leg.length
        return (ln(leg.sourceSpeed + rate * targetDistance) - ln(leg.sourceSpeed + rate * startDistance)) / rate
    }

    private fun connectorDistance(
        leg: RouteLeg.Connector,
        startDistance: Float,
        elapsedMs: Float,
    ): Float {
        if (leg.length <= ROUTE_EPSILON) return leg.length
        val speedDelta = leg.targetSpeed - leg.sourceSpeed
        if (abs(speedDelta) <= ROUTE_EPSILON) return startDistance + leg.sourceSpeed * elapsedMs
        val rate = speedDelta / leg.length
        return (startDistance + leg.sourceSpeed / rate) * exp(rate * elapsedMs) - leg.sourceSpeed / rate
    }
}

private fun requireChaotic(candidate: WaveformConfig) {
    require(candidate.movement == WaveformMovement.CHAOTIC) {
        "Waveform route coordinator requires CHAOTIC movement"
    }
}

private fun ignore(
    current: LifecycleState,
    frame: RouteFrame? = null,
): Transition = Transition(current, RouteUpdate(frame = frame))

private fun WaveformEngine.signalFrame(logicalTimeMs: Long): WaveformFrame =
    requireNotNull(handle(WaveformEvent.Tick(logicalTimeMs, 1f)).frame)

private class RoutePlanner(
    private val random: Random,
) {
    fun createPerimeter(
        graph: RouteGraph,
        previousSurfaceId: String?,
        surfaceId: String,
        entry: PerimeterEntry = PerimeterEntry(),
    ): RouteLeg.Perimeter {
        val surface = graph.surfaces.getValue(surfaceId)
        require(surface.track.isClosed) { "Route surface '$surfaceId' must have a closed waveform track" }
        val startDistance = entry.distance ?: surface.track.signalAnchorDistance
        val localDirection = entry.direction ?: randomDirection()
        val connector =
            when (val targetPolicy = entry.targetPolicy) {
                TargetPolicy.Select -> chooseConnector(graph, previousSurfaceId, surfaceId)
                is TargetPolicy.Preserve ->
                    preserveConnector(graph, previousSurfaceId, surfaceId, targetPolicy.connector)
            }
        val exitDistance =
            connector?.let { planned ->
                forwardDistance(startDistance, planned.sourceDistance, surface.track.length, localDirection)
            } ?: 0f
        val travelDistance = surface.track.length + exitDistance
        return RouteLeg.Perimeter(
            surfaceId = surfaceId,
            entryDistance = wrap(startDistance, surface.track.length),
            direction = localDirection,
            connector = connector,
            lapDistance = surface.track.length,
            length = travelDistance,
            samples =
                routeSamples(
                    surface.track.traversal(startDistance, travelDistance, localDirection),
                    travelDistance,
                ),
            target = RoutePaintTarget.Root(surface.rootId),
            inwardEdges = surface.inwardEdges,
            signalSpan = surface.track.signalSpan,
        )
    }

    private fun preserveConnector(
        graph: RouteGraph,
        previousSurfaceId: String?,
        surfaceId: String,
        planned: RouteConnector?,
    ): RouteConnector? =
        planned
            ?.let { connector ->
                graph.connectorsFrom(surfaceId).firstOrNull { candidate ->
                    candidate.id == connector.id &&
                        candidate.targetId == connector.targetId
                }
            } ?: chooseConnector(graph, previousSurfaceId, surfaceId)

    fun createConnector(
        graph: RouteGraph,
        config: WaveformConfig,
        connector: RouteConnector,
        direction: TravelDirection,
    ): RouteLeg.Connector {
        val source = graph.surfaces.getValue(connector.sourceId)
        val target = graph.surfaces.getValue(connector.targetId)
        val paintTarget =
            if (connector.requiresWindowBridge) {
                RoutePaintTarget.WindowBridge(connector.id)
            } else {
                RoutePaintTarget.Root(source.rootId)
            }
        return RouteLeg.Connector(
            connector = connector,
            direction = direction,
            samples = connectorSamples(connector),
            target = paintTarget,
            sourceSpeed = RouteMotion.perimeterSpeed(source, config),
            targetSpeed = RouteMotion.perimeterSpeed(target, config),
            sourceSpan = source.track.signalSpan,
            targetSpan = target.track.signalSpan,
        )
    }

    fun selectInitialSurface(
        graph: RouteGraph,
        focusedSurfaceId: String?,
    ): String? =
        focusedSurfaceId
            ?.takeIf(graph.surfaces::containsKey)
            ?: editorSurfaceId(graph)
            ?: graph.surfaces.keys
                .sorted()
                .randomOrNull(random)

    fun selectFallbackSurface(
        graph: RouteGraph,
        preferredSurfaceId: String?,
    ): String? =
        preferredSurfaceId
            ?.takeIf(graph.surfaces::containsKey)
            ?: editorSurfaceId(graph)
            ?: graph.surfaces.keys
                .sorted()
                .randomOrNull(random)

    private fun chooseConnector(
        graph: RouteGraph,
        previousSurfaceId: String?,
        surfaceId: String,
    ): RouteConnector? {
        val neighborIds =
            graph
                .connectorsFrom(surfaceId)
                .map(RouteConnector::targetId)
                .distinct()
                .sorted()
        val eligibleIds = neighborIds.filterNot { targetId -> targetId == previousSurfaceId }.ifEmpty { neighborIds }
        val targetId = eligibleIds.randomOrNull(random) ?: return null
        return shortestConnector(graph, surfaceId, targetId)
    }

    private fun shortestConnector(
        graph: RouteGraph,
        surfaceId: String,
        targetId: String,
    ): RouteConnector? =
        graph
            .connectorsFrom(surfaceId)
            .filter { connector -> connector.targetId == targetId }
            .minWithOrNull(CONNECTOR_ROUTE_ORDER)

    private fun editorSurfaceId(graph: RouteGraph): String? =
        graph.surfaces.values
            .filter(RouteSurface::isEditor)
            .minByOrNull(RouteSurface::id)
            ?.id

    private fun randomDirection(): TravelDirection =
        if (random.nextBoolean()) {
            TravelDirection.CLOCKWISE
        } else {
            TravelDirection.COUNTER_CLOCKWISE
        }
}

private class RouteTrail {
    var distanceOffset: Float = 0f
        private set

    private val completedSegments = ArrayDeque<RouteSegment>()

    fun reset() {
        distanceOffset = 0f
        completedSegments.clear()
    }

    fun clearSegments() {
        completedSegments.clear()
    }

    fun discardTarget(target: RoutePaintTarget) {
        completedSegments.removeAll { segment -> segment.target == target }
    }

    fun advance(distance: Float) {
        distanceOffset += distance
    }

    fun keepCenter(
        centerDistance: Float,
        distanceOnLeg: Float,
    ) {
        distanceOffset = centerDistance - distanceOnLeg
    }

    fun centerDistance(current: LifecycleState.Routing): Float = distanceOffset + current.distanceOnLeg

    fun routeFrame(
        current: LifecycleState.Routing,
        engineFrame: WaveformFrame,
    ): RouteFrame {
        val centerDistance = centerDistance(current)
        val signalSpan = RouteMotion.signalSpan(current)
        pruneSegments(centerDistance, signalSpan)
        val slices = completedSegments.map(RouteSegment::toSlice).toMutableList()
        slices += currentSlice(current, signalSpan)
        val visibleSurfaceIds =
            current.leg.visibleSurfaceIds +
                slices.mapNotNull(RouteSlice::surfaceId)
        val signal =
            engineFrame.copy(
                direction = current.leg.direction,
                trace = engineFrame.trace?.copy(anchorOffset = centerDistance),
            )
        return RouteFrame(
            signal = signal,
            centerDistance = centerDistance,
            signalSpan = signalSpan,
            currentSurfaceId = current.leg.currentSurfaceId,
            visibleSurfaceIds = visibleSurfaceIds,
            slices = slices,
        )
    }

    fun render(
        current: LiveState,
        signal: WaveformFrame,
    ): RouteFrame? =
        when (current) {
            is LifecycleState.Empty -> null
            is LifecycleState.Routing -> routeFrame(current, signal)
            is LifecycleState.Recovering -> {
                val progress = (current.elapsedMs / RECOVERY_DURATION_MS).coerceIn(0f, 1f)
                current.frame.copy(
                    signal =
                        signal.copy(
                            direction = current.frame.signal.direction,
                            trace = signal.trace?.copy(anchorOffset = current.frame.centerDistance),
                        ),
                    alpha = 1f - progress,
                )
            }
        }

    fun recordSegment(leg: RouteLeg) {
        completedSegments +=
            RouteSegment(
                target = leg.target,
                surfaceId = leg.sliceSurfaceId,
                samples = leg.samples,
                distanceOffset = distanceOffset,
                inwardEdges = leg.inwardEdges,
                length = leg.length,
            )
    }

    fun recordPrefix(
        leg: RouteLeg,
        distance: Float,
    ) {
        if (distance <= ROUTE_EPSILON) return
        completedSegments +=
            RouteSegment(
                target = leg.target,
                surfaceId = leg.sliceSurfaceId,
                samples = leg.samples.takeWhile { sample -> sample.distance <= distance + ROUTE_EPSILON },
                distanceOffset = distanceOffset,
                inwardEdges = leg.inwardEdges,
                length = distance,
            )
    }

    private fun currentSlice(
        current: LifecycleState.Routing,
        signalSpan: Float,
    ): RouteSlice {
        val limit = current.distanceOnLeg + HEAD_LEAD * signalSpan
        val samples = current.leg.samples.takeWhile { sample -> sample.distance <= limit + ROUTE_EPSILON }
        return RouteSlice(
            target = current.leg.target,
            surfaceId = current.leg.sliceSurfaceId,
            samples = samples.ifEmpty { listOf(current.leg.samples.first()) },
            distanceOffset = distanceOffset,
            inwardEdges = current.leg.inwardEdges,
        )
    }

    private fun pruneSegments(
        centerDistance: Float,
        signalSpan: Float,
    ) {
        while (completedSegments.isNotEmpty()) {
            val first = completedSegments.first
            if (first.distanceOffset + first.length >= centerDistance - signalSpan) return
            completedSegments.removeFirst()
        }
    }
}

private class RouteClock {
    var logicalTimeMs: Long = 0L
        private set

    var engineTimeMs: Long = 0L
        private set

    private var lastWallTickMs: Long? = null

    fun reset() {
        logicalTimeMs = 0L
        engineTimeMs = 0L
        lastWallTickMs = null
    }

    fun resetWallTick() {
        lastWallTickMs = null
    }

    fun elapsed(nowMs: Long): Long {
        val previous = lastWallTickMs
        lastWallTickMs = nowMs
        if (previous == null) return 0L
        val elapsed = (nowMs - previous).coerceAtLeast(0L)
        logicalTimeMs += elapsed
        engineTimeMs = maxOf(engineTimeMs, logicalTimeMs)
        return elapsed
    }

    fun eventTime(nowMs: Long): Long {
        val projected =
            lastWallTickMs?.let { lastTick ->
                logicalTimeMs + (nowMs - lastTick).coerceAtLeast(0L)
            } ?: logicalTimeMs
        engineTimeMs = maxOf(engineTimeMs, projected)
        return engineTimeMs
    }
}

private fun forwardDistance(
    start: Float,
    target: Float,
    length: Float,
    direction: TravelDirection,
): Float =
    when (direction) {
        TravelDirection.CLOCKWISE -> wrap(target - start, length)
        TravelDirection.COUNTER_CLOCKWISE -> wrap(start - target, length)
    }

private fun wrap(
    distance: Float,
    length: Float,
): Float = ((distance % length) + length) % length

private fun routeSamples(
    samples: List<WaveformSample>,
    travelDistance: Float,
): List<WaveformSample> {
    val divisor = (samples.size - 1).coerceAtLeast(1)
    return samples.mapIndexed { index, sample ->
        sample.copy(distance = travelDistance * index / divisor)
    }
}

private fun connectorSamples(connector: RouteConnector): List<WaveformSample> {
    val deltaX = connector.targetPoint.x - connector.sourcePoint.x
    val deltaY = connector.targetPoint.y - connector.sourcePoint.y
    val tangentLength = hypot(deltaX.toDouble(), deltaY.toDouble()).toFloat().coerceAtLeast(ROUTE_EPSILON)
    val normalX = -deltaY / tangentLength
    val normalY = deltaX / tangentLength
    val stepCount = ceil(connector.length / ROUTE_SAMPLE_STEP).toInt().coerceAtLeast(1)
    return List(stepCount + 1) { index ->
        val progress = index.toFloat() / stepCount
        WaveformSample(
            x = connector.sourcePoint.x + deltaX * progress,
            y = connector.sourcePoint.y + deltaY * progress,
            normalX = normalX,
            normalY = normalY,
            distance = connector.length * progress,
            amplitudeMask = 1f,
        )
    }
}

private fun hasIdenticalTopology(
    first: RouteGraph,
    second: RouteGraph,
): Boolean =
    first.surfaces.keys == second.surfaces.keys &&
        first.connectors.values
            .flatten()
            .map(RouteConnector::id)
            .toSet() ==
        second.connectors.values
            .flatten()
            .map(RouteConnector::id)
            .toSet()
