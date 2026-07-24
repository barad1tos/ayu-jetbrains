package dev.ayuislands.glow.waveform

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.random.Random

private const val ROUTE_SAMPLE_STEP = 2f

private data class HandoffCandidate(
    val handoff: PlannedHandoff,
    val turnPenalty: Float,
    val exitDistance: Float,
)

private data class RouteVector(
    val x: Float,
    val y: Float,
) {
    fun turnPenalty(next: RouteVector): Float = 1f - (x * next.x + y * next.y).coerceIn(-1f, 1f)
}

internal sealed interface TargetPolicy {
    data object Select : TargetPolicy

    data class Preserve(
        val handoff: PlannedHandoff?,
    ) : TargetPolicy

    data class Fixed(
        val handoff: PlannedHandoff?,
    ) : TargetPolicy
}

internal enum class PlanRegion {
    PAST,
    FUTURE,
}

internal data class PerimeterEntry(
    val distance: Float? = null,
    val direction: TravelDirection? = null,
    val targetPolicy: TargetPolicy = TargetPolicy.Select,
    val entrySpan: Float? = null,
    val spanDistanceRatio: Float? = null,
)

internal data class GeometryRebind(
    val leg: RouteLeg,
    val distanceOnLeg: Float,
    val previousSurfaceId: String?,
)

private val HANDOFF_ORDER =
    compareBy(
        { candidate -> candidate.handoff.connector.length },
        HandoffCandidate::turnPenalty,
        HandoffCandidate::exitDistance,
        { candidate -> candidate.handoff.connector.sourceSide.ordinal },
        { candidate -> candidate.handoff.connector.targetSide.ordinal },
        { candidate -> candidate.handoff.connector.endpoint.ordinal },
        { candidate -> candidate.handoff.targetDirection.ordinal },
    )

internal class RoutePlanner(
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
        val handoff =
            when (val targetPolicy = entry.targetPolicy) {
                TargetPolicy.Select ->
                    chooseHandoff(graph, previousSurfaceId, surface, startDistance, localDirection)

                is TargetPolicy.Preserve ->
                    preserveHandoff(graph, surface.id, targetPolicy.handoff)
                        ?: chooseHandoff(graph, previousSurfaceId, surface, startDistance, localDirection)

                is TargetPolicy.Fixed -> targetPolicy.handoff
            }
        val exitDistance =
            handoff?.connector?.let { connector ->
                forwardDistance(startDistance, connector.sourceDistance, surface.track.length, localDirection)
            } ?: 0f
        val travelDistance = surface.track.length + exitDistance
        val entrySpan =
            entry.entrySpan
                ?.takeIf(Float::isFinite)
                ?.takeIf { sourceSpan -> abs(sourceSpan - surface.track.signalSpan) > ROUTE_EPSILON }
        val spanDistanceRatio =
            if (entrySpan == null) {
                0f
            } else {
                entry.spanDistanceRatio
                    ?: (entrySpan / surface.track.length).coerceIn(0f, 1f)
            }
        return RouteLeg.Perimeter(
            surfaceId = surfaceId,
            entryDistance = wrap(startDistance, surface.track.length),
            direction = localDirection,
            handoff = handoff,
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
            entrySpan = entrySpan,
            spanDistanceRatio = spanDistanceRatio,
        )
    }

    private fun preserveHandoff(
        graph: RouteGraph,
        surfaceId: String,
        planned: PlannedHandoff?,
    ): PlannedHandoff? =
        planned?.let { handoff ->
            graph
                .connectorsFrom(surfaceId)
                .firstOrNull { candidate ->
                    candidate.id == handoff.connector.id &&
                        candidate.endpoint == handoff.connector.endpoint &&
                        candidate.targetId == handoff.connector.targetId
                }?.let { connector -> handoff.copy(connector = connector) }
                ?.takeIf(PlannedHandoff::hasFiniteGeometry)
        }

    fun createConnector(
        graph: RouteGraph,
        config: WaveformConfig,
        handoff: PlannedHandoff,
        direction: TravelDirection,
    ): RouteLeg.Connector {
        val connector = handoff.connector
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
            targetDirection = handoff.targetDirection,
            samples = connectorSamples(connector),
            target = paintTarget,
            sourceSpeed = RouteMotion.perimeterSpeed(source, config),
            targetSpeed = RouteMotion.perimeterSpeed(target, config),
            sourceSpan = source.track.signalSpan,
            targetSpan = target.track.signalSpan,
        )
    }

    fun nextLeg(
        graph: RouteGraph,
        config: WaveformConfig,
        current: RouteLeg,
    ): RouteLeg? =
        when (current) {
            is RouteLeg.Perimeter -> {
                val handoff = current.handoff
                if (handoff == null) {
                    createPerimeter(
                        graph = graph,
                        previousSurfaceId = null,
                        surfaceId = current.surfaceId,
                        entry =
                            PerimeterEntry(
                                distance = RouteMotion.perimeterPosition(current, current.length, graph),
                                direction = current.direction,
                            ),
                    )
                } else if (handoff.connector.length > ROUTE_EPSILON) {
                    createConnector(graph, config, handoff, current.direction)
                } else {
                    targetPerimeter(graph, handoff, current.signalSpan)
                }
            }

            is RouteLeg.Connector ->
                targetPerimeter(
                    graph = graph,
                    handoff = PlannedHandoff(current.connector, current.targetDirection),
                    entrySpan = current.targetSpan,
                )
        }

    private fun targetPerimeter(
        graph: RouteGraph,
        handoff: PlannedHandoff,
        entrySpan: Float,
    ): RouteLeg.Perimeter? {
        val connector = handoff.connector
        if (!graph.surfaces.containsKey(connector.targetId)) return null
        return createPerimeter(
            graph = graph,
            previousSurfaceId = connector.sourceId,
            surfaceId = connector.targetId,
            entry =
                PerimeterEntry(
                    distance = connector.targetDistance,
                    direction = handoff.targetDirection,
                    entrySpan = entrySpan,
                ),
        )
    }

    fun rebindGeometry(
        current: LifecycleState.Routing,
        replacement: RouteGraph,
        oldGraph: RouteGraph,
        previousSurfaceId: String?,
        config: WaveformConfig,
    ): GeometryRebind {
        val oldLeg = current.leg
        val reboundLeg =
            when (oldLeg) {
                is RouteLeg.Perimeter -> {
                    val oldTrack = oldGraph.surfaces.getValue(oldLeg.surfaceId).track
                    val newTrack = replacement.surfaces.getValue(oldLeg.surfaceId).track
                    createPerimeter(
                        graph = replacement,
                        previousSurfaceId = previousSurfaceId,
                        surfaceId = oldLeg.surfaceId,
                        entry =
                            PerimeterEntry(
                                distance = oldLeg.entryDistance / oldTrack.length * newTrack.length,
                                direction = oldLeg.direction,
                                targetPolicy = TargetPolicy.Preserve(oldLeg.handoff),
                                entrySpan = oldLeg.entrySpan,
                                spanDistanceRatio = oldLeg.spanDistanceRatio,
                            ),
                    )
                }

                is RouteLeg.Connector ->
                    rebindConnector(
                        replacement,
                        oldGraph,
                        previousSurfaceId,
                        config,
                        current,
                    )
            }
        val enteredTarget =
            oldLeg is RouteLeg.Connector &&
                reboundLeg is RouteLeg.Perimeter &&
                reboundLeg.surfaceId == oldLeg.connector.targetId
        val reboundPreviousId =
            if (enteredTarget) oldLeg.connector.sourceId else previousSurfaceId
        return GeometryRebind(
            leg = reboundLeg,
            distanceOnLeg = rebindDistance(oldLeg, reboundLeg, current.distanceOnLeg),
            previousSurfaceId = reboundPreviousId,
        )
    }

    fun rebindLeg(
        leg: RouteLeg,
        oldGraph: RouteGraph,
        replacement: RouteGraph,
        config: WaveformConfig,
        region: PlanRegion,
    ): RouteLeg? =
        when (leg) {
            is RouteLeg.Perimeter -> {
                val oldSurface = oldGraph.surfaces[leg.surfaceId] ?: return null
                val newSurface = replacement.surfaces[leg.surfaceId] ?: return null
                val handoff = preserveHandoff(replacement, leg.surfaceId, leg.handoff)
                if (region == PlanRegion.FUTURE && leg.handoff != null && handoff == null) return null
                createPerimeter(
                    graph = replacement,
                    previousSurfaceId = null,
                    surfaceId = leg.surfaceId,
                    entry =
                        PerimeterEntry(
                            distance = leg.entryDistance / oldSurface.track.length * newSurface.track.length,
                            direction = leg.direction,
                            targetPolicy = TargetPolicy.Fixed(handoff),
                            entrySpan = leg.entrySpan,
                            spanDistanceRatio = leg.spanDistanceRatio,
                        ),
                )
            }

            is RouteLeg.Connector -> {
                val connector =
                    replacement
                        .connectorsFrom(leg.connector.sourceId)
                        .firstOrNull { candidate ->
                            candidate.id == leg.connector.id &&
                                candidate.endpoint == leg.connector.endpoint &&
                                candidate.targetId == leg.connector.targetId
                        } ?: return null
                val handoff = PlannedHandoff(connector, leg.targetDirection)
                if (!handoff.hasFiniteGeometry || connector.length <= ROUTE_EPSILON) return null
                createConnector(replacement, config, handoff, leg.direction)
            }
        }

    fun isCollapsed(
        leg: RouteLeg,
        replacement: RouteGraph,
    ): Boolean =
        leg is RouteLeg.Connector &&
            replacement
                .connectorsFrom(leg.connector.sourceId)
                .any { connector ->
                    connector.id == leg.connector.id &&
                        connector.endpoint == leg.connector.endpoint &&
                        connector.targetId == leg.connector.targetId &&
                        connector.length <= ROUTE_EPSILON
                }

    fun continues(
        current: RouteLeg,
        next: RouteLeg,
    ): Boolean =
        when (current) {
            is RouteLeg.Perimeter -> {
                val handoff = current.handoff
                when {
                    handoff == null ->
                        next is RouteLeg.Perimeter && next.surfaceId == current.surfaceId

                    handoff.connector.length > ROUTE_EPSILON ->
                        next is RouteLeg.Connector &&
                            next.connector.id == handoff.connector.id &&
                            next.connector.endpoint == handoff.connector.endpoint &&
                            next.connector.targetId == handoff.connector.targetId

                    else ->
                        next is RouteLeg.Perimeter &&
                            next.surfaceId == handoff.connector.targetId
                }
            }

            is RouteLeg.Connector ->
                next is RouteLeg.Perimeter &&
                    next.surfaceId == current.connector.targetId
        }

    private fun rebindDistance(
        oldLeg: RouteLeg,
        newLeg: RouteLeg,
        distance: Float,
    ): Float =
        when (oldLeg) {
            is RouteLeg.Perimeter ->
                (newLeg as? RouteLeg.Perimeter)
                    ?.let { rebound -> rebindPerimeterDistance(oldLeg, rebound, distance) }
                    ?: 0f

            is RouteLeg.Connector ->
                (newLeg as? RouteLeg.Connector)
                    ?.let { rebound -> rebindConnectorDistance(oldLeg, rebound, distance) }
                    ?: 0f
        }

    private fun rebindPerimeterDistance(
        oldLeg: RouteLeg.Perimeter,
        newLeg: RouteLeg.Perimeter,
        distance: Float,
    ): Float {
        if (distance <= oldLeg.lapDistance + ROUTE_EPSILON) {
            val lapProgress =
                if (oldLeg.lapDistance <= ROUTE_EPSILON) 0f else distance / oldLeg.lapDistance
            return lapProgress.coerceIn(0f, 1f) * newLeg.lapDistance
        }

        val oldExitDistance = oldLeg.length - oldLeg.lapDistance
        val newExitDistance = newLeg.length - newLeg.lapDistance
        val exitProgress =
            if (oldExitDistance <= ROUTE_EPSILON) {
                0f
            } else {
                (distance - oldLeg.lapDistance) / oldExitDistance
            }
        return newLeg.lapDistance + exitProgress.coerceIn(0f, 1f) * newExitDistance
    }

    private fun rebindConnectorDistance(
        oldLeg: RouteLeg.Connector,
        newLeg: RouteLeg.Connector,
        distance: Float,
    ): Float {
        val progress =
            if (oldLeg.length <= ROUTE_EPSILON) 0f else distance / oldLeg.length
        return progress.coerceIn(0f, 1f) * newLeg.length
    }

    fun rebindConnector(
        replacement: RouteGraph,
        oldGraph: RouteGraph,
        previousSurfaceId: String?,
        config: WaveformConfig,
        current: LifecycleState.Routing,
    ): RouteLeg {
        val leg = current.leg as RouteLeg.Connector
        val entrySpan = RouteMotion.signalSpan(current)
        val connector =
            replacement
                .connectorsFrom(leg.connector.sourceId)
                .firstOrNull { candidate ->
                    candidate.id == leg.connector.id &&
                        candidate.endpoint == leg.connector.endpoint &&
                        candidate.targetId == leg.connector.targetId
                }
                ?: return reboundSource(replacement, oldGraph, previousSurfaceId, leg, entrySpan)
        val handoff =
            preserveHandoff(
                graph = replacement,
                surfaceId = leg.connector.sourceId,
                planned = PlannedHandoff(connector, leg.targetDirection),
            ) ?: PlannedHandoff(connector, leg.targetDirection)
        if (!handoff.hasFiniteGeometry) {
            return reboundSource(replacement, oldGraph, previousSurfaceId, leg, entrySpan)
        }
        if (connector.length <= ROUTE_EPSILON) {
            if (!replacement.surfaces.containsKey(connector.targetId)) {
                return reboundSource(replacement, oldGraph, previousSurfaceId, leg, entrySpan)
            }
            return createPerimeter(
                graph = replacement,
                previousSurfaceId = connector.sourceId,
                surfaceId = connector.targetId,
                entry =
                    PerimeterEntry(
                        distance = connector.targetDistance,
                        direction = handoff.targetDirection,
                        entrySpan = entrySpan,
                    ),
            )
        }
        return createConnector(replacement, config, handoff, leg.direction)
    }

    private fun reboundSource(
        replacement: RouteGraph,
        oldGraph: RouteGraph,
        previousSurfaceId: String?,
        leg: RouteLeg.Connector,
        entrySpan: Float,
    ): RouteLeg.Perimeter {
        val oldSource = oldGraph.surfaces.getValue(leg.connector.sourceId)
        val newSource = replacement.surfaces.getValue(leg.connector.sourceId)
        val sourceDistance =
            leg.connector.sourceDistance / oldSource.track.length * newSource.track.length
        return createPerimeter(
            graph = replacement,
            previousSurfaceId = previousSurfaceId,
            surfaceId = leg.connector.sourceId,
            entry =
                PerimeterEntry(
                    distance = sourceDistance,
                    direction = leg.direction,
                    entrySpan = entrySpan,
                ),
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

    private fun chooseHandoff(
        graph: RouteGraph,
        previousSurfaceId: String?,
        surface: RouteSurface,
        startDistance: Float,
        direction: TravelDirection,
    ): PlannedHandoff? =
        selectHandoff(
            graph = graph,
            previousSurfaceId = previousSurfaceId,
            surface = surface,
            startDistance = startDistance,
            direction = direction,
        )

    private fun selectHandoff(
        graph: RouteGraph,
        previousSurfaceId: String?,
        surface: RouteSurface,
        startDistance: Float,
        direction: TravelDirection,
    ): PlannedHandoff? {
        val candidates =
            graph
                .connectorsFrom(surface.id)
                .asSequence()
                .flatMap { connector ->
                    val target = graph.surfaces[connector.targetId] ?: return@flatMap emptySequence()
                    TravelDirection.entries.asSequence().mapNotNull { targetDirection ->
                        val handoff = PlannedHandoff(connector, targetDirection)
                        if (!handoff.hasFiniteGeometry) return@mapNotNull null
                        val candidate =
                            HandoffCandidate(
                                handoff = handoff,
                                turnPenalty =
                                    handoffTurnPenalty(
                                        source = surface.track,
                                        target = target.track,
                                        connector = connector,
                                        sourceDirection = direction,
                                        targetDirection = targetDirection,
                                    ),
                                exitDistance =
                                    forwardDistance(
                                        startDistance,
                                        connector.sourceDistance,
                                        surface.track.length,
                                        direction,
                                    ),
                            )
                        candidate.takeIf { planned ->
                            planned.turnPenalty.isFinite() && planned.exitDistance.isFinite()
                        }
                    }
                }.toList()
        val neighborIds =
            candidates
                .map { candidate -> candidate.handoff.connector.targetId }
                .distinct()
                .sorted()
        val eligibleIds = neighborIds.filterNot { targetId -> targetId == previousSurfaceId }.ifEmpty { neighborIds }
        val targetId = eligibleIds.randomOrNull(random) ?: return null
        return candidates
            .asSequence()
            .filter { candidate -> candidate.handoff.connector.targetId == targetId }
            .minWithOrNull(HANDOFF_ORDER)
            ?.handoff
    }

    private fun handoffTurnPenalty(
        source: WaveformTrack,
        target: WaveformTrack,
        connector: RouteConnector,
        sourceDirection: TravelDirection,
        targetDirection: TravelDirection,
    ): Float {
        val incoming = routeTangent(source, connector.sourceDistance, sourceDirection, isIncoming = true)
        val outgoing = routeTangent(target, connector.targetDistance, targetDirection, isIncoming = false)
        if (connector.length <= ROUTE_EPSILON) return incoming.turnPenalty(outgoing)

        val bridge =
            routeVector(
                connector.sourcePoint.x,
                connector.sourcePoint.y,
                connector.targetPoint.x,
                connector.targetPoint.y,
            )
        return incoming.turnPenalty(bridge) + bridge.turnPenalty(outgoing)
    }

    private fun routeTangent(
        track: WaveformTrack,
        distance: Float,
        direction: TravelDirection,
        isIncoming: Boolean,
    ): RouteVector {
        val signedStep = ROUTE_SAMPLE_STEP * direction.travelSign
        val startDistance = if (isIncoming) distance - signedStep else distance
        val endDistance = if (isIncoming) distance else distance + signedStep
        val start = track.sampleAt(startDistance)
        val end = track.sampleAt(endDistance)
        return routeVector(start.x, start.y, end.x, end.y)
    }

    private fun routeVector(
        startX: Float,
        startY: Float,
        endX: Float,
        endY: Float,
    ): RouteVector {
        val deltaX = endX - startX
        val deltaY = endY - startY
        val length = hypot(deltaX.toDouble(), deltaY.toDouble()).toFloat().coerceAtLeast(ROUTE_EPSILON)
        return RouteVector(deltaX / length, deltaY / length)
    }

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
