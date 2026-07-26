package dev.ayuislands.glow.waveform

import java.awt.Rectangle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RouteGraphBuilderTest {
    @Test
    fun `horizontal bridge uses shared edge ends`() {
        val left = surface("Editor", root = 1, bounds = Rectangle(0, 0, 400, 300))
        val right = surface("Commit", root = 1, bounds = Rectangle(408, 40, 220, 180))

        val connectors = builder().build(listOf(left, right)).connectorsFrom("Editor")
        val start = connectors.single { connector -> connector.endpoint == RouteEndpoint.START }
        val end = connectors.single { connector -> connector.endpoint == RouteEndpoint.END }

        assertEquals(setOf("Commit"), connectors.map(RouteConnector::targetId).toSet())
        assertTrue(connectors.all { connector -> connector.sourceSide == RouteSide.RIGHT })
        assertTrue(connectors.all { connector -> connector.targetSide == RouteSide.LEFT })
        assertEquals(56f, start.sourcePoint.y, 0.001f)
        assertEquals(start.sourcePoint.y, start.targetPoint.y, 0.001f)
        assertEquals(204f, end.sourcePoint.y, 0.001f)
        assertEquals(end.sourcePoint.y, end.targetPoint.y, 0.001f)
        assertTrue(connectors.all { connector -> connector.sourcePoint.x < connector.targetPoint.x })
        assertTrue(connectors.none(RouteConnector::requiresWindowBridge))
    }

    @Test
    fun `vertical bridge uses shared edge ends`() {
        val top = surface("Editor", root = 1, bounds = Rectangle(0, 0, 300, 200))
        val bottom = surface("Terminal", root = 1, bounds = Rectangle(40, 208, 180, 220))

        val connectors = builder().build(listOf(top, bottom)).connectorsFrom("Editor")
        val start = connectors.single { connector -> connector.endpoint == RouteEndpoint.START }
        val end = connectors.single { connector -> connector.endpoint == RouteEndpoint.END }

        assertEquals(setOf("Terminal"), connectors.map(RouteConnector::targetId).toSet())
        assertTrue(connectors.all { connector -> connector.sourceSide == RouteSide.BOTTOM })
        assertTrue(connectors.all { connector -> connector.targetSide == RouteSide.TOP })
        assertEquals(56f, start.sourcePoint.x, 0.001f)
        assertEquals(start.sourcePoint.x, start.targetPoint.x, 0.001f)
        assertEquals(204f, end.sourcePoint.x, 0.001f)
        assertEquals(end.sourcePoint.x, end.targetPoint.x, 0.001f)
        assertTrue(connectors.all { connector -> connector.sourcePoint.y < connector.targetPoint.y })
    }

    @Test
    fun `gap beyond twenty four logical pixels is not adjacent`() {
        val left = surface("Editor", root = 1, bounds = Rectangle(0, 0, 400, 300))
        val right = surface("Commit", root = 1, bounds = Rectangle(425, 40, 220, 180))

        assertTrue(builder().build(listOf(left, right)).connectorsFrom("Editor").isEmpty())
    }

    @Test
    fun `gap at twenty four logical pixels is adjacent`() {
        val left = surface("Editor", root = 1, bounds = Rectangle(0, 0, 400, 300))
        val right = surface("Commit", root = 1, bounds = Rectangle(424, 40, 220, 180))

        val connectors = builder().build(listOf(left, right)).connectorsFrom("Editor")

        assertEquals(2, connectors.size)
        assertEquals(setOf("Commit"), connectors.map(RouteConnector::targetId).toSet())
    }

    @Test
    fun `zero gap is adjacent`() {
        val left = surface("Editor", root = 1, bounds = Rectangle(0, 0, 400, 300))
        val right = surface("Commit", root = 1, bounds = Rectangle(400, 40, 220, 180))

        val connectors = builder().build(listOf(left, right)).connectorsFrom("Editor")

        assertEquals(2, connectors.size)
        assertEquals(setOf("Commit"), connectors.map(RouteConnector::targetId).toSet())
    }

    @Test
    fun `overlapping rectangles are not adjacent`() {
        val left = surface("Editor", root = 1, bounds = Rectangle(0, 0, 400, 300))
        val overlapping = surface("Commit", root = 1, bounds = Rectangle(399, 40, 220, 180))

        assertTrue(builder().build(listOf(left, overlapping)).connectorsFrom("Editor").isEmpty())
    }

    @Test
    fun `neighbors without straight edge projection overlap are not adjacent`() {
        val left = surface("Editor", root = 1, bounds = Rectangle(0, 0, 400, 300))
        val right = surface("Commit", root = 1, bounds = Rectangle(408, 300, 220, 180))

        assertTrue(builder().build(listOf(left, right)).connectorsFrom("Editor").isEmpty())
    }

    @Test
    fun `rounded corner endpoint overlap is not adjacent`() {
        val left = surface("Editor", root = 1, bounds = Rectangle(0, 0, 400, 300))
        val right = surface("Commit", root = 1, bounds = Rectangle(408, 268, 220, 180))

        assertTrue(builder().build(listOf(left, right)).connectorsFrom("Editor").isEmpty())
    }

    @Test
    fun `bounds overlap formed only by rounded arcs is not adjacent`() {
        val left = surface("Editor", root = 1, bounds = Rectangle(0, 0, 400, 300))
        val right = surface("Commit", root = 1, bounds = Rectangle(408, 269, 220, 180))

        assertTrue(builder().build(listOf(left, right)).connectorsFrom("Editor").isEmpty())
    }

    @Test
    fun `isolated surfaces remain in a disconnected component`() {
        val editor = surface("Editor", root = 1, bounds = Rectangle(0, 0, 400, 300))
        val commit = surface("Commit", root = 1, bounds = Rectangle(408, 40, 220, 180))
        val isolated = surface("Search", root = 1, bounds = Rectangle(1_000, 700, 240, 180))

        val graph = builder().build(listOf(editor, commit, isolated))

        assertEquals(setOf("Commit", "Editor", "Search"), graph.surfaces.keys)
        assertEquals(listOf("Commit"), graph.connectorsFrom("Editor").map(RouteConnector::targetId).distinct())
        assertEquals(listOf("Editor"), graph.connectorsFrom("Commit").map(RouteConnector::targetId).distinct())
        assertTrue(graph.connectorsFrom("Search").isEmpty())
    }

    @Test
    fun `directed connectors reverse canonical physical endpoints`() {
        val zulu = surface("Zulu", root = 1, bounds = Rectangle(0, 0, 400, 300))
        val alpha = surface("Alpha", root = 1, bounds = Rectangle(408, 40, 220, 180))

        val graph = builder().build(listOf(zulu, alpha))
        val forward = graph.connectorsFrom("Zulu")
        val reverse = graph.connectorsFrom("Alpha")

        assertEquals(2, forward.size)
        assertEquals(2, reverse.size)
        assertEquals(setOf(RouteConnectorId("Alpha", "Zulu", RouteSide.LEFT)), forward.map(RouteConnector::id).toSet())
        forward.forEach { forwardConnector ->
            val reverseConnector =
                reverse.single { candidate ->
                    candidate.sourcePoint == forwardConnector.targetPoint &&
                        candidate.targetPoint == forwardConnector.sourcePoint
                }
            assertEquals(forwardConnector.id, reverseConnector.id)
            assertEquals(forwardConnector.sourceId, reverseConnector.targetId)
            assertEquals(forwardConnector.targetId, reverseConnector.sourceId)
            assertEquals(forwardConnector.sourceSide, reverseConnector.targetSide)
            assertEquals(forwardConnector.targetSide, reverseConnector.sourceSide)
            assertEquals(forwardConnector.sourceDistance, reverseConnector.targetDistance)
            assertEquals(forwardConnector.targetDistance, reverseConnector.sourceDistance)
            assertEquals(forwardConnector.length, reverseConnector.length)
        }

        val disconnected = graph.without(forward.first().id)
        assertTrue(disconnected.connectorsFrom("Zulu").isEmpty())
        assertTrue(disconnected.connectorsFrom("Alpha").isEmpty())
    }

    @Test
    fun `cross root connector requires bridge capability`() {
        val main = surface("Editor", root = 1, bounds = Rectangle(0, 0, 400, 300))
        val floating =
            surface(
                "Git",
                root = 2,
                bounds = Rectangle(408, 40, 220, 180),
                windowKind = RouteWindowKind.FLOATING_TOOL_WINDOW,
            )

        val blocked = builder(canBridge = false).build(listOf(main, floating))
        val allowed = builder(canBridge = true).build(listOf(main, floating))
        val connectors = allowed.connectorsFrom("Editor")

        assertTrue(blocked.connectorsFrom("Editor").isEmpty())
        assertEquals(2, connectors.size)
        assertTrue(connectors.all(RouteConnector::requiresWindowBridge))
    }

    @Test
    fun `cross root main surfaces remain disconnected even with bridge capability`() {
        val first = surface("Editor", root = 1, bounds = Rectangle(0, 0, 400, 300))
        val second = surface("Commit", root = 2, bounds = Rectangle(408, 40, 220, 180))

        assertTrue(builder(canBridge = true).build(listOf(first, second)).connectorsFrom("Editor").isEmpty())
    }

    @Test
    fun `input reordering preserves sorted surfaces and connectors`() {
        val editor = surface("Editor", root = 1, bounds = Rectangle(400, 0, 400, 300))
        val zulu = surface("Zulu", root = 1, bounds = Rectangle(808, 40, 220, 180))
        val alpha = surface("Alpha", root = 1, bounds = Rectangle(172, 40, 220, 180))

        val ordered = builder().build(listOf(editor, zulu, alpha))
        val reordered = builder().build(listOf(alpha, editor, zulu))

        assertEquals(ordered, reordered)
        assertEquals(listOf("Alpha", "Editor", "Zulu"), ordered.surfaces.keys.toList())
        assertEquals(
            listOf("Alpha", "Zulu"),
            ordered.connectorsFrom("Editor").map(RouteConnector::targetId).distinct(),
        )
    }

    private fun surface(
        id: String,
        root: Int,
        bounds: Rectangle,
        windowKind: RouteWindowKind = RouteWindowKind.MAIN,
    ): RouteSurface {
        val localTrack =
            Rectangle(0, 0, bounds.width, bounds.height).toWaveformTrack(
                margin = 0f,
                arcRadius = 16f,
                config = WaveformConfig(),
                direction = TravelDirection.CLOCKWISE,
            )
        return RouteSurface(
            id = id,
            rootId = RouteRootId(root),
            track = localTrack.translated(bounds.x.toFloat(), bounds.y.toFloat()),
            isEditor = id == "Editor",
            windowKind = windowKind,
            inwardEdges = emptySet(),
        )
    }

    private fun builder(canBridge: Boolean = false): RouteGraphBuilder =
        RouteGraphBuilder(
            maximumGap = 24f,
            canBridge = { _, _ -> canBridge },
        )
}
