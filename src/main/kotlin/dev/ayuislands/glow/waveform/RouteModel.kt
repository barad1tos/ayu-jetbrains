package dev.ayuislands.glow.waveform

internal const val ROUTE_EPSILON = 0.001f

internal sealed interface RouteEvent {
    data class Activate(
        val graph: RouteGraph,
        val focusedSurfaceId: String?,
        val powerSaveEnabled: Boolean,
    ) : RouteEvent

    data class Tick(
        val nowMs: Long,
        val isWindowActive: Boolean = true,
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

internal sealed interface LifecycleState {
    data object Dormant : LifecycleState

    data class Empty(
        val fallbackSurfaceId: String?,
    ) : LifecycleState,
        LiveState

    data class Routing(
        val plan: RoutePlan,
    ) : LifecycleState,
        LiveState {
        val leg: RouteLeg
            get() = plan.active

        val distanceOnLeg: Float
            get() = plan.distanceOnSpan
    }

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

internal sealed interface LiveState : LifecycleState

internal sealed interface SavedState : LifecycleState {
    val resumeState: LiveState
    val stableFrame: RouteFrame?
}

internal data class PlannedHandoff(
    val connector: RouteConnector,
    val targetDirection: TravelDirection,
) {
    val hasFiniteGeometry: Boolean
        get() =
            connector.length.isFinite() &&
                connector.sourceDistance.isFinite() &&
                connector.targetDistance.isFinite() &&
                connector.sourcePoint.x.isFinite() &&
                connector.sourcePoint.y.isFinite() &&
                connector.targetPoint.x.isFinite() &&
                connector.targetPoint.y.isFinite()
}

internal sealed interface RouteLeg {
    val direction: TravelDirection
    val length: Float
    val samples: List<WaveformSample>
    val target: RoutePaintTarget
    val inwardEdges: Set<WaveformEdge>
    val currentSurfaceId: String
    val sliceSurfaceId: String?
    val plannedTargetId: String?

    data class Perimeter(
        val surfaceId: String,
        val entryDistance: Float,
        override val direction: TravelDirection,
        val handoff: PlannedHandoff?,
        val lapDistance: Float,
        override val length: Float,
        override val samples: List<WaveformSample>,
        override val target: RoutePaintTarget,
        override val inwardEdges: Set<WaveformEdge>,
        val signalSpan: Float,
        val entrySpan: Float?,
        val spanDistanceRatio: Float,
    ) : RouteLeg {
        override val currentSurfaceId: String = surfaceId
        override val sliceSurfaceId: String = surfaceId
        override val plannedTargetId: String? = handoff?.connector?.targetId
    }

    data class Connector(
        val connector: RouteConnector,
        override val direction: TravelDirection,
        val targetDirection: TravelDirection,
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
        override val sliceSurfaceId: String? = null
        override val plannedTargetId: String = connector.targetId
    }
}

internal enum class SuspensionReason {
    APPLICATION_INACTIVE,
    POWER_SAVE,
}
