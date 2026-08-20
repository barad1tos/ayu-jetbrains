package dev.ayuislands.glow.waveform

import com.intellij.util.concurrency.annotations.RequiresEdt
import kotlin.random.Random

private const val RECOVERY_DURATION_MS = 160f
private const val MAX_TICK_TRANSITIONS = 10_000
private const val MAX_TICK_ADVANCES = 128

private data class Transition(
    val state: LifecycleState,
    val update: RouteUpdate,
)

private data class AdvanceResult(
    val state: LiveState,
    val remainingMs: Float,
)

private val EMPTY_ROUTE_SNAPSHOT = RouteSnapshot(null, 0f, null, null)

internal class RouteCoordinator(
    initialConfig: WaveformConfig,
    random: Random = Random.Default,
) {
    private var config = initialConfig
    private val engine = WaveformEngine(initialConfig, random)
    private val planner = RoutePlanner(random)
    private val clock = RouteClock()
    private var graph = RouteGraph(emptyMap(), emptyMap())
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
        if (event is RouteEvent.Tick && !event.isWindowActive) {
            clock.resetWallTick()
            return RouteUpdate()
        }
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
        previousSurfaceId = null
        clock.reset()
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
            prepareRouting(
                LifecycleState.Routing(
                    routePlan(planner.createPerimeter(graph, previousSurfaceId, selectedId)),
                ),
            )
        val engineFrame = requireNotNull(engine.handle(WaveformEvent.Activate(powerSaveEnabled = false)).frame)
        val frame = routeFrame(routing, engineFrame)
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
        val prepared =
            if (advanced is LifecycleState.Routing) {
                prepareRouting(advanced)
            } else {
                advanced
            }
        val signal = engine.signalFrame(clock.engineTimeMs)
        val frame = render(prepared, signal)
        val directive = if (prepared is LifecycleState.Empty) TimerDirective.STOP else TimerDirective.KEEP
        return Transition(prepared, RouteUpdate(directive, frame))
    }

    private fun prepareRouting(current: LifecycleState.Routing): LifecycleState.Routing {
        val signalSpan = RouteMotion.signalSpan(current)
        var plan = current.plan.prune(signalSpan)
        repeat(MAX_TICK_TRANSITIONS) {
            val visibleEnd = plan.centerDistance + signalSpan
            if (plan.spans.last().endDistance > visibleEnd + ROUTE_EPSILON) {
                return current.copy(plan = plan)
            }
            val next = planner.nextLeg(graph, config, plan.spans.last().leg) ?: return current.copy(plan = plan)
            val last = plan.spans.last()
            if (last.endDistance >= visibleEnd - ROUTE_EPSILON &&
                next.target == last.leg.target &&
                next.sliceSurfaceId == last.leg.sliceSurfaceId
            ) {
                return current.copy(plan = plan)
            }
            plan = plan.append(next)
        }
        error("Route plan exceeded $MAX_TICK_TRANSITIONS visible spans")
    }

    private fun render(
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
        if (replacement.surfaces.isEmpty()) return ignore(current)

        val selectedId = planner.selectFallbackSurface(replacement, current.fallbackSurfaceId)
        checkNotNull(selectedId) { "Non-empty route graph must provide a fallback surface" }
        val routing =
            prepareRouting(
                LifecycleState.Routing(
                    routePlan(planner.createPerimeter(graph, previousSurfaceId, selectedId)),
                ),
            )
        clock.resetWallTick()
        val signal = engine.signalFrame(clock.engineTimeMs)
        return Transition(routing, RouteUpdate(TimerDirective.START, routeFrame(routing, signal)))
    }

    private fun changeRoutingGraph(
        current: LifecycleState.Routing,
        replacement: RouteGraph,
    ): Transition {
        if (replacement.surfaces.isEmpty()) return becomeEmpty(current.leg.currentSurfaceId)
        if (!replacement.surfaces.containsKey(current.leg.currentSurfaceId)) {
            val stable = checkNotNull(lastFrame) { "Recovery requires a stable route frame" }
            graph = replacement
            return Transition(
                LifecycleState.Recovering(
                    elapsedMs = 0f,
                    fallbackGraph = replacement,
                    frame = stable.copy(alpha = 1f),
                ),
                RouteUpdate(frame = stable),
            )
        }

        val rebound = current.plan.rebind(replacement, graph, previousSurfaceId, config, planner)
        graph = replacement
        previousSurfaceId = rebound.previousSurfaceId
        val routing = prepareRouting(LifecycleState.Routing(rebound.plan))
        val frame = routeFrame(routing, lastFrame?.signal ?: engine.signalFrame(clock.engineTimeMs))
        return Transition(routing, RouteUpdate(frame = frame))
    }

    private fun failBridge(
        current: LifecycleState.Routing,
        connectorId: RouteConnectorId,
    ): Transition {
        graph = graph.without(connectorId)
        val leg = current.leg
        if (leg is RouteLeg.Connector && leg.connector.id == connectorId) {
            val perimeter =
                planner.createPerimeter(
                    graph = graph,
                    previousSurfaceId = previousSurfaceId,
                    surfaceId = leg.connector.sourceId,
                    entry =
                        PerimeterEntry(
                            distance = leg.connector.sourceDistance,
                            direction = leg.direction,
                            entrySpan = RouteMotion.signalSpan(current),
                        ),
                )
            val routing = prepareRouting(current.copy(plan = current.plan.divert(perimeter)))
            val signal = lastFrame?.signal ?: engine.signalFrame(clock.engineTimeMs)
            return Transition(routing, RouteUpdate(frame = routeFrame(routing, signal)))
        }
        if (leg is RouteLeg.Perimeter && leg.handoff?.connector?.id == connectorId) {
            val entry = RouteMotion.perimeterPosition(leg, current.distanceOnLeg, graph)
            val perimeter =
                planner.createPerimeter(
                    graph,
                    previousSurfaceId,
                    leg.surfaceId,
                    PerimeterEntry(
                        distance = entry,
                        direction = leg.direction,
                        entrySpan = RouteMotion.signalSpan(current),
                    ),
                )
            val routing = prepareRouting(current.copy(plan = current.plan.divert(perimeter)))
            val signal = lastFrame?.signal ?: engine.signalFrame(clock.engineTimeMs)
            return Transition(routing, RouteUpdate(frame = routeFrame(routing, signal)))
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
        val stableFrame =
            if (resumeState is LifecycleState.Empty) {
                null
            } else {
                nested.update.frame ?: current.stableFrame
            }
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
        previousSurfaceId = null
        clock.resetWallTick()
        lastFrame = null
        return Transition(LifecycleState.Dormant, RouteUpdate(TimerDirective.STOP))
    }

    private fun becomeEmpty(fallbackSurfaceId: String?): Transition {
        graph = RouteGraph(emptyMap(), emptyMap())
        clock.resetWallTick()
        lastFrame = null
        return Transition(LifecycleState.Empty(fallbackSurfaceId), RouteUpdate(TimerDirective.STOP))
    }

    private fun advance(
        initial: LiveState,
        elapsedMs: Float,
    ): LiveState {
        var current = initial
        var remainingMs = elapsedMs
        repeat(MAX_TICK_ADVANCES) {
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
        return current
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
            return AdvanceResult(current.copy(plan = current.plan.move(distance)), 0f)
        }

        val atBoundary = current.copy(plan = current.plan.move(boundaryDistance))
        val remainingMs = (availableMs - boundaryMs).coerceAtLeast(0f)
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
        val routing =
            prepareRouting(
                LifecycleState.Routing(
                    routePlan(planner.createPerimeter(graph, previousSurfaceId, selectedId)),
                ),
            )
        return AdvanceResult(routing, (availableMs - remainingFadeMs).coerceAtLeast(0f))
    }

    private fun completeLeg(current: LifecycleState.Routing): LiveState {
        val prepared = prepareRouting(current)
        check(prepared.plan.activeIndex < prepared.plan.spans.lastIndex) {
            "Completed route span requires a planned successor"
        }
        val completed = prepared.leg
        val entered = prepared.plan.spans[prepared.plan.activeIndex + 1].leg
        if (entered is RouteLeg.Perimeter && completed.currentSurfaceId != entered.surfaceId) {
            previousSurfaceId = completed.currentSurfaceId
        }
        return LifecycleState.Routing(prepared.plan.enterNext())
    }
}

private fun snapshotOf(current: LifecycleState): RouteSnapshot =
    when (current) {
        LifecycleState.Dormant -> EMPTY_ROUTE_SNAPSHOT
        is LifecycleState.Empty -> EMPTY_ROUTE_SNAPSHOT
        is LifecycleState.Routing ->
            RouteSnapshot(
                currentSurfaceId = current.leg.currentSurfaceId,
                distanceOnLeg = current.distanceOnLeg,
                direction = current.leg.direction,
                plannedTargetId = current.leg.plannedTargetId,
            )

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
