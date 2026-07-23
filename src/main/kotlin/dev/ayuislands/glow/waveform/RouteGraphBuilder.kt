package dev.ayuislands.glow.waveform

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

@JvmInline
internal value class RouteRootId(
    val value: Int,
)

internal enum class RouteSide {
    TOP,
    RIGHT,
    BOTTOM,
    LEFT,
}

internal enum class RouteWindowKind {
    MAIN,
    DOCKED_TOOL_WINDOW,
    FLOATING_TOOL_WINDOW,
    WINDOWED_TOOL_WINDOW,
    OTHER,
    ;

    val permitsWindowBridge: Boolean
        get() =
            when (this) {
                FLOATING_TOOL_WINDOW,
                WINDOWED_TOOL_WINDOW,
                -> true

                MAIN,
                DOCKED_TOOL_WINDOW,
                OTHER,
                -> false
            }
}

internal data class RoutePoint(
    val x: Float,
    val y: Float,
)

internal data class RouteConnectorId(
    val firstSurfaceId: String,
    val secondSurfaceId: String,
    val firstSide: RouteSide,
)

internal data class RouteSurface(
    val id: String,
    val rootId: RouteRootId,
    val track: WaveformTrack,
    val isEditor: Boolean,
    val windowKind: RouteWindowKind,
    val inwardEdges: Set<WaveformEdge>,
)

internal data class RouteConnector(
    val id: RouteConnectorId,
    val sourceId: String,
    val targetId: String,
    val sourceSide: RouteSide,
    val targetSide: RouteSide,
    val sourceDistance: Float,
    val targetDistance: Float,
    val sourcePoint: RoutePoint,
    val targetPoint: RoutePoint,
    val length: Float,
    val requiresWindowBridge: Boolean,
)

internal data class RouteGraph(
    val surfaces: Map<String, RouteSurface>,
    val connectors: Map<String, List<RouteConnector>>,
) {
    fun connectorsFrom(surfaceId: String): List<RouteConnector> = connectors[surfaceId].orEmpty()

    fun without(connectorId: RouteConnectorId): RouteGraph =
        copy(
            connectors =
                connectors.mapValues { (_, values) ->
                    values.filterNot { connector -> connector.id == connectorId }
                },
        )
}

internal class RouteGraphBuilder(
    private val maximumGap: Float,
    private val canBridge: (RouteRootId, RouteRootId) -> Boolean,
) {
    fun build(surfaces: List<RouteSurface>): RouteGraph {
        val orderedSurfaces = surfaces.sortedBy(RouteSurface::id)
        val connectors =
            orderedSurfaces
                .associate { surface ->
                    surface.id to emptyList<RouteConnector>()
                }.toMutableMap()

        orderedSurfaces.forEachIndexed { index, source ->
            orderedSurfaces.drop(index + 1).forEach { target ->
                addPair(source, target, connectors)
            }
        }

        return RouteGraph(
            surfaces = orderedSurfaces.associateBy(RouteSurface::id),
            connectors =
                connectors.mapValues { (_, values) ->
                    values.sortedWith(CONNECTOR_ORDER)
                },
        )
    }

    private fun addPair(
        first: RouteSurface,
        second: RouteSurface,
        connectors: MutableMap<String, List<RouteConnector>>,
    ) {
        val requiresWindowBridge = first.rootId != second.rootId
        if (requiresWindowBridge && !canConnectRoots(first, second)) return

        for ((sourceSide, targetSide) in FACING_SIDES) {
            val connector =
                createConnector(
                    source = first,
                    target = second,
                    sourceSide = sourceSide,
                    targetSide = targetSide,
                    requiresWindowBridge = requiresWindowBridge,
                )
            if (connector != null) {
                connectors[first.id] = connectors.getValue(first.id) + connector
                connectors[second.id] = connectors.getValue(second.id) + connector.reversed()
            }
        }
    }

    private fun canConnectRoots(
        first: RouteSurface,
        second: RouteSurface,
    ): Boolean {
        val bridgeableWindow =
            first.windowKind.permitsWindowBridge ||
                second.windowKind.permitsWindowBridge
        return bridgeableWindow && canBridge(first.rootId, second.rootId)
    }

    private fun createConnector(
        source: RouteSurface,
        target: RouteSurface,
        sourceSide: RouteSide,
        targetSide: RouteSide,
        requiresWindowBridge: Boolean,
    ): RouteConnector? {
        val sourceSamples = source.track.straightSamples(sourceSide)
        val targetSamples = target.track.straightSamples(targetSide)
        if (sourceSamples.isEmpty() || targetSamples.isEmpty()) return null

        val separation = sideSeparation(sourceSamples.first(), targetSamples.first(), sourceSide)
        if (separation !in 0f..maximumGap) return null

        val overlapStart =
            max(
                sourceSamples.minOf { sample -> sample.projection(sourceSide) },
                targetSamples.minOf { sample -> sample.projection(targetSide) },
            )
        val overlapEnd =
            min(
                sourceSamples.maxOf { sample -> sample.projection(sourceSide) },
                targetSamples.maxOf { sample -> sample.projection(targetSide) },
            )
        if (overlapStart >= overlapEnd) return null

        val midpoint = (overlapStart + overlapEnd) / 2f
        val sourceSample = sourceSamples.nearestTo(midpoint, sourceSide)
        val targetSample = targetSamples.nearestTo(midpoint, targetSide)
        val sourcePoint = sourceSample.toRoutePoint()
        val targetPoint = targetSample.toRoutePoint()
        return RouteConnector(
            id = connectorId(source, target, sourceSide, targetSide),
            sourceId = source.id,
            targetId = target.id,
            sourceSide = sourceSide,
            targetSide = targetSide,
            sourceDistance = sourceSample.distance,
            targetDistance = targetSample.distance,
            sourcePoint = sourcePoint,
            targetPoint = targetPoint,
            length =
                hypot(
                    (targetPoint.x - sourcePoint.x).toDouble(),
                    (targetPoint.y - sourcePoint.y).toDouble(),
                ).toFloat(),
            requiresWindowBridge = requiresWindowBridge,
        )
    }
}

private data class FacingSides(
    val source: RouteSide,
    val target: RouteSide,
)

private val FACING_SIDES =
    listOf(
        FacingSides(RouteSide.RIGHT, RouteSide.LEFT),
        FacingSides(RouteSide.LEFT, RouteSide.RIGHT),
        FacingSides(RouteSide.BOTTOM, RouteSide.TOP),
        FacingSides(RouteSide.TOP, RouteSide.BOTTOM),
    )

private val CONNECTOR_ORDER =
    compareBy(
        RouteConnector::targetId,
        RouteConnector::length,
        { connector -> connector.sourceSide.ordinal },
        { connector -> connector.targetSide.ordinal },
    )

private fun WaveformTrack.straightSamples(side: RouteSide): List<WaveformSample> =
    samples.filter { sample ->
        when (side) {
            RouteSide.TOP -> sample.normalY == -1f
            RouteSide.RIGHT -> sample.normalX == 1f
            RouteSide.BOTTOM -> sample.normalY == 1f
            RouteSide.LEFT -> sample.normalX == -1f
        }
    }

private fun sideSeparation(
    source: WaveformSample,
    target: WaveformSample,
    sourceSide: RouteSide,
): Float =
    when (sourceSide) {
        RouteSide.TOP -> source.y - target.y
        RouteSide.RIGHT -> target.x - source.x
        RouteSide.BOTTOM -> target.y - source.y
        RouteSide.LEFT -> source.x - target.x
    }

private fun WaveformSample.projection(side: RouteSide): Float =
    when (side) {
        RouteSide.TOP,
        RouteSide.BOTTOM,
        -> x

        RouteSide.RIGHT,
        RouteSide.LEFT,
        -> y
    }

private fun List<WaveformSample>.nearestTo(
    projection: Float,
    side: RouteSide,
): WaveformSample =
    minWith(
        compareBy(
            { sample -> abs(sample.projection(side) - projection) },
            WaveformSample::distance,
        ),
    )

private fun WaveformSample.toRoutePoint(): RoutePoint = RoutePoint(x, y)

private fun connectorId(
    source: RouteSurface,
    target: RouteSurface,
    sourceSide: RouteSide,
    targetSide: RouteSide,
): RouteConnectorId =
    if (source.id < target.id) {
        RouteConnectorId(source.id, target.id, sourceSide)
    } else {
        RouteConnectorId(target.id, source.id, targetSide)
    }

private fun RouteConnector.reversed(): RouteConnector =
    copy(
        sourceId = targetId,
        targetId = sourceId,
        sourceSide = targetSide,
        targetSide = sourceSide,
        sourceDistance = targetDistance,
        targetDistance = sourceDistance,
        sourcePoint = targetPoint,
        targetPoint = sourcePoint,
    )
