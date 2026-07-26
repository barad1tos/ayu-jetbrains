package dev.ayuislands.glow.waveform

import java.util.ArrayDeque

internal data class PlanRebind(
    val plan: RoutePlan,
    val previousSurfaceId: String?,
)

internal data class RouteSpan(
    val leg: RouteLeg,
    val startDistance: Float,
) {
    val endDistance: Float
        get() = startDistance + leg.length

    fun visibleSlice(
        visibleStart: Float,
        visibleEnd: Float,
    ): RouteSlice? {
        if (endDistance < visibleStart || startDistance > visibleEnd) return null
        if (leg.samples.isEmpty()) return null

        val localStart = (visibleStart - startDistance).coerceIn(0f, leg.length)
        val localEnd = (visibleEnd - startDistance).coerceIn(0f, leg.length)
        val firstIndex =
            leg.samples
                .indexOfLast { sample -> sample.distance <= localStart }
                .coerceAtLeast(0)
        val lastIndex =
            leg.samples
                .indexOfFirst { sample -> sample.distance >= localEnd }
                .takeIf { index -> index >= 0 }
                ?: leg.samples.lastIndex
        val firstDistance = leg.samples[firstIndex].distance
        val samples =
            leg.samples
                .subList(firstIndex, lastIndex + 1)
                .map { sample -> sample.copy(distance = sample.distance - firstDistance) }
        return RouteSlice(
            target = leg.target,
            surfaceId = leg.sliceSurfaceId,
            samples = samples,
            distanceOffset = startDistance + firstDistance,
            inwardEdges = leg.inwardEdges,
        )
    }
}

internal data class RoutePlan(
    val spans: List<RouteSpan>,
    val activeIndex: Int,
    val distanceOnSpan: Float,
) {
    init {
        require(spans.isNotEmpty()) { "Route plan requires at least one span" }
        require(activeIndex in spans.indices) { "Active route span index is out of bounds" }
        require(distanceOnSpan in 0f..spans[activeIndex].leg.length) {
            "Active route distance must be inside its span"
        }
    }

    val active: RouteLeg
        get() = spans[activeIndex].leg

    val centerDistance: Float
        get() = spans[activeIndex].startDistance + distanceOnSpan

    fun move(distance: Float): RoutePlan = copy(distanceOnSpan = distance.coerceIn(0f, active.length))

    fun append(leg: RouteLeg): RoutePlan {
        val previous = spans.last()
        return copy(spans = spans + RouteSpan(leg, previous.endDistance))
    }

    fun replaceActive(leg: RouteLeg): RoutePlan {
        require(leg.length == active.length) { "Replacing a route span must preserve its length" }
        return copy(
            spans =
                spans.mapIndexed { index, span ->
                    if (index == activeIndex) span.copy(leg = leg) else span
                },
        )
    }

    fun enterNext(): RoutePlan {
        check(activeIndex < spans.lastIndex) { "Route plan has no next span" }
        return copy(activeIndex = activeIndex + 1, distanceOnSpan = 0f)
    }

    fun divert(
        leg: RouteLeg,
        distance: Float = 0f,
    ): RoutePlan {
        val retained = spans.take(activeIndex)
        val replacement = RouteSpan(leg, centerDistance - distance)
        return RoutePlan(
            spans = retained + replacement,
            activeIndex = retained.size,
            distanceOnSpan = distance,
        )
    }

    fun prune(signalSpan: Float): RoutePlan {
        val firstVisibleIndex =
            spans
                .indexOfFirst { span -> span.endDistance >= centerDistance - signalSpan }
                .coerceAtLeast(0)
        if (firstVisibleIndex == 0) return this
        return copy(
            spans = spans.drop(firstVisibleIndex),
            activeIndex = activeIndex - firstVisibleIndex,
        )
    }

    fun visibleSlices(signalSpan: Float): List<RouteSlice> {
        val visibleStart = centerDistance - signalSpan
        val visibleEnd = centerDistance + signalSpan
        return spans.mapNotNull { span -> span.visibleSlice(visibleStart, visibleEnd) }
    }
}

internal fun routePlan(
    leg: RouteLeg,
    distanceOnSpan: Float = 0f,
    centerDistance: Float = distanceOnSpan,
): RoutePlan =
    RoutePlan(
        spans = listOf(RouteSpan(leg, centerDistance - distanceOnSpan)),
        activeIndex = 0,
        distanceOnSpan = distanceOnSpan,
    )

internal fun RoutePlan.rebind(
    replacement: RouteGraph,
    oldGraph: RouteGraph,
    previousSurfaceId: String?,
    config: WaveformConfig,
    planner: RoutePlanner,
): PlanRebind {
    val current = LifecycleState.Routing(this)
    val rebound = planner.rebindGeometry(current, replacement, oldGraph, previousSurfaceId, config)
    val activeStart = centerDistance - rebound.distanceOnLeg
    val reboundSpans = reboundHistory(rebound, replacement, oldGraph, config, planner)

    val reboundIndex = reboundSpans.size - 1
    var nextStart = activeStart + rebound.leg.length
    for (index in activeIndex + 1..spans.lastIndex) {
        val oldLeg = spans[index].leg
        val reboundLeg = planner.rebindLeg(oldLeg, oldGraph, replacement, config, PlanRegion.FUTURE)
        if (reboundLeg == null) {
            if (planner.isCollapsed(oldLeg, replacement)) continue
            break
        }
        if (!planner.continues(reboundSpans.last().leg, reboundLeg)) break
        reboundSpans.add(RouteSpan(reboundLeg, nextStart))
        nextStart += reboundLeg.length
    }

    return PlanRebind(
        plan = RoutePlan(reboundSpans.toList(), reboundIndex, rebound.distanceOnLeg),
        previousSurfaceId = rebound.previousSurfaceId,
    )
}

private fun RoutePlan.reboundHistory(
    rebound: GeometryRebind,
    replacement: RouteGraph,
    oldGraph: RouteGraph,
    config: WaveformConfig,
    planner: RoutePlanner,
): ArrayDeque<RouteSpan> {
    val activeStart = centerDistance - rebound.distanceOnLeg
    val reboundSpans = ArrayDeque<RouteSpan>()
    reboundSpans.add(RouteSpan(rebound.leg, activeStart))

    var previousStart = activeStart
    for (index in activeIndex - 1 downTo 0) {
        val oldLeg = spans[index].leg
        val reboundLeg = planner.rebindLeg(oldLeg, oldGraph, replacement, config, PlanRegion.PAST)
        if (reboundLeg == null) {
            if (planner.isCollapsed(oldLeg, replacement)) continue
            break
        }
        if (!planner.continues(reboundLeg, reboundSpans.first().leg)) break
        previousStart -= reboundLeg.length
        reboundSpans.addFirst(RouteSpan(reboundLeg, previousStart))
    }
    return reboundSpans
}

internal fun routeFrame(
    current: LifecycleState.Routing,
    engineFrame: WaveformFrame,
): RouteFrame {
    val plan = current.plan
    val signalSpan = RouteMotion.signalSpan(current)
    val signal =
        engineFrame.copy(
            direction = current.leg.direction,
            trace = engineFrame.trace?.copy(anchorOffset = plan.centerDistance),
        )
    return RouteFrame(
        signal = signal,
        centerDistance = plan.centerDistance,
        signalSpan = signalSpan,
        currentSurfaceId = current.leg.currentSurfaceId,
        slices = plan.visibleSlices(signalSpan),
    )
}
