package dev.ayuislands.glow.waveform

import java.awt.Rectangle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RouteGraphBuilderTest {
    @Test
    fun `horizontal neighbors receive one centered connector away from corners`() {
        val left = surface("Editor", root = 1, bounds = Rectangle(0, 0, 400, 300))
        val right = surface("Commit", root = 1, bounds = Rectangle(408, 40, 220, 180))

        val connector = builder().build(listOf(left, right)).connectorsFrom("Editor").single()

        assertEquals("Commit", connector.targetId)
        assertEquals(RouteSide.RIGHT, connector.sourceSide)
        assertEquals(RouteSide.LEFT, connector.targetSide)
        assertEquals(130f, connector.sourcePoint.y, 0.001f)
        assertEquals(130f, connector.targetPoint.y, 0.001f)
        assertTrue(connector.sourcePoint.x < connector.targetPoint.x)
        assertFalse(connector.requiresWindowBridge)
    }

    @Test
    fun `vertical neighbors receive one centered connector away from corners`() {
        val top = surface("Editor", root = 1, bounds = Rectangle(0, 0, 300, 200))
        val bottom = surface("Terminal", root = 1, bounds = Rectangle(40, 208, 180, 220))

        val connector = builder().build(listOf(top, bottom)).connectorsFrom("Editor").single()

        assertEquals("Terminal", connector.targetId)
        assertEquals(RouteSide.BOTTOM, connector.sourceSide)
        assertEquals(RouteSide.TOP, connector.targetSide)
        assertEquals(130f, connector.sourcePoint.x, 0.001f)
        assertEquals(130f, connector.targetPoint.x, 0.001f)
        assertTrue(connector.sourcePoint.y < connector.targetPoint.y)
    }

    @Test
    fun `gap beyond twenty four logical pixels is not adjacent`() {
        val left = surface("Editor", root = 1, bounds = Rectangle(0, 0, 400, 300))
        val right = surface("Commit", root = 1, bounds = Rectangle(425, 40, 220, 180))

        assertTrue(builder().build(listOf(left, right)).connectorsFrom("Editor").isEmpty())
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
    fun `isolated surfaces remain in a disconnected component`() {
        val editor = surface("Editor", root = 1, bounds = Rectangle(0, 0, 400, 300))
        val commit = surface("Commit", root = 1, bounds = Rectangle(408, 40, 220, 180))
        val isolated = surface("Search", root = 1, bounds = Rectangle(1_000, 700, 240, 180))

        val graph = builder().build(listOf(editor, commit, isolated))

        assertEquals(setOf("Commit", "Editor", "Search"), graph.surfaces.keys)
        assertEquals(listOf("Commit"), graph.connectorsFrom("Editor").map(RouteConnector::targetId))
        assertEquals(listOf("Editor"), graph.connectorsFrom("Commit").map(RouteConnector::targetId))
        assertTrue(graph.connectorsFrom("Search").isEmpty())
    }

    @Test
    fun `directed connectors reverse one canonical physical connector`() {
        val zulu = surface("Zulu", root = 1, bounds = Rectangle(0, 0, 400, 300))
        val alpha = surface("Alpha", root = 1, bounds = Rectangle(408, 40, 220, 180))

        val graph = builder().build(listOf(zulu, alpha))
        val forward = graph.connectorsFrom("Zulu").single()
        val reverse = graph.connectorsFrom("Alpha").single()

        assertEquals(forward.id, reverse.id)
        assertEquals(RouteConnectorId("Alpha", "Zulu", RouteSide.LEFT), forward.id)
        assertEquals(forward.sourceId, reverse.targetId)
        assertEquals(forward.targetId, reverse.sourceId)
        assertEquals(forward.sourceSide, reverse.targetSide)
        assertEquals(forward.targetSide, reverse.sourceSide)
        assertEquals(forward.sourceDistance, reverse.targetDistance)
        assertEquals(forward.targetDistance, reverse.sourceDistance)
        assertEquals(forward.sourcePoint, reverse.targetPoint)
        assertEquals(forward.targetPoint, reverse.sourcePoint)
        assertEquals(forward.length, reverse.length)

        val disconnected = graph.without(forward.id)
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

        assertTrue(blocked.connectorsFrom("Editor").isEmpty())
        assertTrue(allowed.connectorsFrom("Editor").single().requiresWindowBridge)
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
        assertEquals(listOf("Alpha", "Zulu"), ordered.connectorsFrom("Editor").map(RouteConnector::targetId))
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
