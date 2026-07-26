package dev.ayuislands.glow.waveform

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln

private const val ROUTE_MILLIS_PER_SECOND = 1_000f

internal object RouteMotion {
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
            plan =
                routing.plan.replaceActive(
                    leg.copy(
                        sourceSpeed = perimeterSpeed(source, config),
                        targetSpeed = perimeterSpeed(target, config),
                    ),
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
            is RouteLeg.Perimeter -> {
                val entrySpan = leg.entrySpan ?: return leg.signalSpan
                val transitionDistance = leg.lapDistance * leg.spanDistanceRatio
                if (transitionDistance <= ROUTE_EPSILON) return leg.signalSpan
                val progress = (current.distanceOnLeg / transitionDistance).coerceIn(0f, 1f)
                entrySpan + (leg.signalSpan - entrySpan) * progress
            }

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

internal class RouteClock {
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

internal fun forwardDistance(
    start: Float,
    target: Float,
    length: Float,
    direction: TravelDirection,
): Float =
    when (direction) {
        TravelDirection.CLOCKWISE -> wrap(target - start, length)
        TravelDirection.COUNTER_CLOCKWISE -> wrap(start - target, length)
    }

internal fun wrap(
    distance: Float,
    length: Float,
): Float = ((distance % length) + length) % length
