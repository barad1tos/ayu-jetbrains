package dev.ayuislands.glow

import com.intellij.openapi.project.Project
import dev.ayuislands.glow.waveform.CrossWindowBridge
import dev.ayuislands.glow.waveform.RouteConnector
import dev.ayuislands.glow.waveform.RouteConnectorId
import dev.ayuislands.glow.waveform.RouteCoordinator
import dev.ayuislands.glow.waveform.RouteEndpoint
import dev.ayuislands.glow.waveform.RouteEvent
import dev.ayuislands.glow.waveform.RouteFrame
import dev.ayuislands.glow.waveform.RouteGraph
import dev.ayuislands.glow.waveform.RouteLayerStyle
import dev.ayuislands.glow.waveform.RoutePaintTarget
import dev.ayuislands.glow.waveform.RoutePoint
import dev.ayuislands.glow.waveform.RouteRootId
import dev.ayuislands.glow.waveform.RouteSide
import dev.ayuislands.glow.waveform.RouteSlice
import dev.ayuislands.glow.waveform.RouteUpdate
import dev.ayuislands.glow.waveform.TimerDirective
import dev.ayuislands.glow.waveform.TravelDirection
import dev.ayuislands.glow.waveform.WaveformConfig
import dev.ayuislands.glow.waveform.WaveformFrame
import dev.ayuislands.glow.waveform.WaveformMovement
import dev.ayuislands.glow.waveform.WaveformSample
import dev.ayuislands.settings.AyuIslandsState
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.unmockkConstructor
import io.mockk.verify
import org.junit.jupiter.api.Test
import java.awt.Color
import java.awt.Point
import java.awt.Window
import javax.swing.JLayeredPane
import javax.swing.Timer
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class RouteControllerTest {
    @Test
    fun `inactive route does not advance until one route window is active`() {
        val controller = controller()
        val coordinator = mockk<RouteCoordinator>()
        every { coordinator.handle(any()) } returns RouteUpdate(timerDirective = TimerDirective.START)
        seedCoordinator(controller, coordinator)
        val firstWindow = mockk<Window>()
        val secondWindow = mockk<Window>()
        every { firstWindow.isActive } returns false
        every { secondWindow.isActive } returns false
        seedRouteRoot(controller, RouteRootId(1), firstWindow)
        seedRouteRoot(controller, RouteRootId(2), secondWindow)

        controller.handle(RouteEvent.Tick(100L))

        verify(exactly = 1) { coordinator.handle(RouteEvent.Tick(100L, isWindowActive = false)) }
        assertNull(routeTimer(controller))

        every { secondWindow.isActive } returns true
        controller.handle(RouteEvent.Tick(200L))

        verify(exactly = 1) { coordinator.handle(RouteEvent.Tick(200L, isWindowActive = true)) }
        assertNotNull(routeTimer(controller)).stop()
    }

    @Test
    fun `every visible window bridge slice is rendered`() {
        mockkConstructor(CrossWindowBridge::class)
        every {
            anyConstructed<CrossWindowBridge>().show(any(), any(), any(), any<List<RouteSlice>>(), any())
        } just Runs
        val controller =
            RouteController(
                project = mockk<Project>(relaxed = true),
                overlays = { emptyList() },
                focusedSurfaceId = { null },
                state = { AyuIslandsState() },
                onFailure = {},
            )
        val first = connector("Editor", "Commit")
        val second = connector("Commit", "Project")
        seedBridgeTarget(controller, first)
        seedBridgeTarget(controller, second)
        val frame =
            RouteFrame(
                signal =
                    WaveformFrame(
                        config = WaveformConfig(movement = WaveformMovement.CHAOTIC),
                        direction = TravelDirection.CLOCKWISE,
                    ),
                centerDistance = 10f,
                signalSpan = 20f,
                currentSurfaceId = "Commit",
                slices = listOf(bridgeSlice(first), bridgeSlice(second)),
            )
        val style =
            RouteLayerStyle(
                accent = Color.ORANGE,
                glowStyle = GlowStyle.SOFT,
                intensity = 100,
                width = 2,
                arcWidth = 2,
                config = WaveformConfig(movement = WaveformMovement.CHAOTIC),
            )

        try {
            val method =
                controller.javaClass.getDeclaredMethod(
                    "renderBridge",
                    RouteFrame::class.java,
                    RouteLayerStyle::class.java,
                )
            method.isAccessible = true
            method.invoke(controller, frame, style)

            verify(exactly = 2) {
                anyConstructed<CrossWindowBridge>().show(any(), any(), frame, any<List<RouteSlice>>(), style)
            }
        } finally {
            controller.dispose()
            unmockkConstructor(CrossWindowBridge::class)
        }
    }

    @Test
    fun `bridge endpoints keep independent renderers`() {
        mockkConstructor(CrossWindowBridge::class)
        every {
            anyConstructed<CrossWindowBridge>().show(any(), any(), any(), any<List<RouteSlice>>(), any())
        } just Runs
        val controller = controller()
        val start = connector("Editor", "Commit", RouteEndpoint.START)
        val end = connector("Editor", "Commit", RouteEndpoint.END)
        seedBridgeTarget(controller, start)
        seedBridgeTarget(controller, end)

        try {
            renderBridge(controller, routeFrame(start, end))

            assertEquals(2, bridgeCount(controller))
        } finally {
            controller.dispose()
            unmockkConstructor(CrossWindowBridge::class)
        }
    }

    @Test
    fun `layout refresh retains every visible bridge target`() {
        val controller = controller()
        val first = connector("Editor", "Commit")
        val second = connector("Commit", "Project")
        seedBridgeTarget(controller, first)
        seedBridgeTarget(controller, second)
        setLastFrame(controller, routeFrame(first, second))

        cacheBridgeTargets(controller, RouteGraph(emptyMap(), emptyMap()))

        assertEquals(setOf(first.id, second.id), bridgeTargetIds(controller))
        controller.dispose()
    }

    @Test
    fun `current endpoint keeps peer preserved geometry`() {
        mockkConstructor(CrossWindowBridge::class)
        every {
            anyConstructed<CrossWindowBridge>().show(any(), any(), any(), any<List<RouteSlice>>(), any())
        } just Runs
        val controller = controller()
        val start = connector("Editor", "Commit", RouteEndpoint.START, verticalOffset = 0f)
        val oldEnd = connector("Editor", "Commit", RouteEndpoint.END, verticalOffset = 20f)
        val currentEnd = connector("Editor", "Commit", RouteEndpoint.END, verticalOffset = 100f)
        val startTarget = seedBridgeTarget(controller, start)
        val currentEndTarget = seedBridgeTarget(controller, currentEnd)
        seedBridgeTarget(controller, oldEnd)
        seedCurrentTargets(controller, start.id, startTarget, currentEndTarget)
        val frame = routeFrame(start, oldEnd)

        try {
            renderBridge(controller, frame)

            verify(exactly = 1) {
                anyConstructed<CrossWindowBridge>().show(any(), oldEnd, frame, listOf(frame.slices[1]), any())
            }
        } finally {
            controller.dispose()
            unmockkConstructor(CrossWindowBridge::class)
        }
    }

    @Test
    fun `newer bridge render survives reentrant predecessor`() {
        val controller = controller()
        val first = connector("Editor", "Commit")
        val replacement = connector("Commit", "Project")
        val firstBridge = mockk<CrossWindowBridge>(relaxed = true)
        val replacementBridge = mockk<CrossWindowBridge>(relaxed = true)
        seedBridge(controller, first, firstBridge)
        seedBridge(controller, replacement, replacementBridge)
        seedBridgeTarget(controller, first)
        seedBridgeTarget(controller, replacement)
        val replacementFrame = routeFrame(replacement)
        every { firstBridge.show(any(), first, any(), any<List<RouteSlice>>(), any()) } answers {
            renderBridge(controller, replacementFrame)
        }

        try {
            renderBridge(controller, routeFrame(first))

            verify(exactly = 0) { replacementBridge.hide() }
        } finally {
            controller.dispose()
        }
    }

    @Test
    fun `same endpoint bridge slices share one render`() {
        mockkConstructor(CrossWindowBridge::class)
        every {
            anyConstructed<CrossWindowBridge>().show(any(), any(), any(), any<List<RouteSlice>>(), any())
        } just Runs
        val controller = controller()
        val connector = connector("Editor", "Commit")
        seedBridgeTarget(controller, connector)
        val frame = routeFrame(connector, connector)

        try {
            renderBridge(controller, frame)

            verify(exactly = 1) {
                anyConstructed<CrossWindowBridge>().show(any(), connector, frame, frame.slices, any())
            }
        } finally {
            controller.dispose()
            unmockkConstructor(CrossWindowBridge::class)
        }
    }

    @Test
    fun `obsolete bridge geometry is disposed`() {
        mockkConstructor(CrossWindowBridge::class)
        every {
            anyConstructed<CrossWindowBridge>().show(any(), any(), any(), any<List<RouteSlice>>(), any())
        } just Runs
        every { anyConstructed<CrossWindowBridge>().dispose() } just Runs
        val controller = controller()

        try {
            repeat(10) { index ->
                val connector =
                    connector(
                        sourceId = "Editor",
                        targetId = "Commit",
                        verticalOffset = index * 10f,
                    )
                replaceBridgeTarget(controller, connector)
                renderBridge(controller, routeFrame(connector))
            }

            assertEquals(1, bridgeCount(controller))
            verify(exactly = 9) { anyConstructed<CrossWindowBridge>().dispose() }
        } finally {
            controller.dispose()
            unmockkConstructor(CrossWindowBridge::class)
        }
    }

    @Test
    fun `newer bridge survives reentrant cleanup`() {
        val controller = controller()
        val first = connector("Editor", "Commit")
        val replacement = connector("Commit", "Project")
        val firstBridge = mockk<CrossWindowBridge>(relaxed = true)
        val replacementBridge = mockk<CrossWindowBridge>(relaxed = true)
        seedBridge(controller, first, firstBridge)
        seedBridge(controller, replacement, replacementBridge)
        seedBridgeTarget(controller, first)
        seedBridgeTarget(controller, replacement)
        var didReenter = false
        every { firstBridge.hide() } answers {
            if (!didReenter) {
                didReenter = true
                renderBridge(controller, routeFrame(replacement))
            }
        }

        try {
            renderBridge(controller, routeFrame())

            verify(exactly = 0) { replacementBridge.hide() }
            verify(exactly = 0) { replacementBridge.dispose() }
        } finally {
            controller.dispose()
        }
    }

    private fun controller(): RouteController =
        RouteController(
            project = mockk<Project>(relaxed = true),
            overlays = { emptyList() },
            focusedSurfaceId = { null },
            state = { AyuIslandsState() },
            onFailure = {},
        )

    private fun seedCoordinator(
        controller: RouteController,
        coordinator: RouteCoordinator,
    ) {
        val field = controller.javaClass.getDeclaredField("coordinator")
        field.isAccessible = true
        field.set(controller, coordinator)
    }

    private fun seedRouteRoot(
        controller: RouteController,
        rootId: RouteRootId,
        window: Window,
    ) {
        val rootClass = Class.forName("dev.ayuislands.glow.RouteRoot")
        val constructor =
            rootClass.getDeclaredConstructor(
                JLayeredPane::class.java,
                Window::class.java,
                Point::class.java,
            )
        constructor.isAccessible = true
        val root = constructor.newInstance(JLayeredPane(), window, Point())
        val field = controller.javaClass.getDeclaredField("roots")
        field.isAccessible = true
        val roots = field.get(controller)
        val put = roots.javaClass.getMethod("put", Any::class.java, Any::class.java)
        put.invoke(roots, rootId, root)
    }

    private fun routeTimer(controller: RouteController): Timer? {
        val field = controller.javaClass.getDeclaredField("timer")
        field.isAccessible = true
        return field.get(controller) as Timer?
    }

    private fun renderBridge(
        controller: RouteController,
        frame: RouteFrame,
    ) {
        val method =
            controller.javaClass.getDeclaredMethod(
                "renderBridge",
                RouteFrame::class.java,
                RouteLayerStyle::class.java,
            )
        method.isAccessible = true
        method.invoke(controller, frame, bridgeStyle())
    }

    private fun cacheBridgeTargets(
        controller: RouteController,
        graph: RouteGraph,
    ) {
        val method = controller.javaClass.getDeclaredMethod("cacheBridgeTargets", RouteGraph::class.java)
        method.isAccessible = true
        method.invoke(controller, graph)
    }

    private fun setLastFrame(
        controller: RouteController,
        frame: RouteFrame,
    ) {
        val field = controller.javaClass.getDeclaredField("lastFrame")
        field.isAccessible = true
        field.set(controller, frame)
    }

    private fun bridgeTargetIds(controller: RouteController): Set<RouteConnectorId> {
        val field = controller.javaClass.getDeclaredField("bridgeTargets")
        field.isAccessible = true
        val targets = field.get(controller) as Map<*, *>
        return targets.keys.filterIsInstance<RouteConnectorId>().toSet()
    }

    private fun bridgeCount(controller: RouteController): Int {
        val field = controller.javaClass.getDeclaredField("bridges")
        field.isAccessible = true
        return (field.get(controller) as Map<*, *>).size
    }

    private fun seedBridge(
        controller: RouteController,
        connector: RouteConnector,
        bridge: CrossWindowBridge,
    ) {
        val field = controller.javaClass.getDeclaredField("bridges")
        field.isAccessible = true
        val bridges = field.get(controller) as Map<*, *>
        val put = bridges.javaClass.getMethod("put", Any::class.java, Any::class.java)
        put.invoke(bridges, connector, bridge)
    }

    private fun seedBridgeTarget(
        controller: RouteController,
        connector: RouteConnector,
    ): Any {
        val owner = mockk<Window>(relaxed = true)
        val targetClass = Class.forName("dev.ayuislands.glow.BridgeTarget")
        val constructor = targetClass.getDeclaredConstructor(RouteConnector::class.java, Window::class.java)
        constructor.isAccessible = true
        val target = constructor.newInstance(connector, owner)
        val field = controller.javaClass.getDeclaredField("bridgeTargets")
        field.isAccessible = true
        val targets = field.get(controller)
        val existing = targets.javaClass.getMethod("get", Any::class.java).invoke(targets, connector.id)
        if (existing != null) {
            existing.javaClass.getMethod("add", Any::class.java).invoke(existing, target)
            return target
        }
        val put = targets.javaClass.getMethod("put", Any::class.java, Any::class.java)
        put.invoke(targets, connector.id, mutableListOf(target))
        return target
    }

    private fun seedCurrentTargets(
        controller: RouteController,
        connectorId: RouteConnectorId,
        vararg targets: Any,
    ) {
        val field = controller.javaClass.getDeclaredField("currentBridgeTargets")
        field.isAccessible = true
        field.set(controller, mapOf(connectorId to targets.toList()))
    }

    private fun replaceBridgeTarget(
        controller: RouteController,
        connector: RouteConnector,
    ) {
        val field = controller.javaClass.getDeclaredField("bridgeTargets")
        field.isAccessible = true
        val targets = field.get(controller)
        targets.javaClass
            .getMethod("clear")
            .invoke(targets)
        val target = seedBridgeTarget(controller, connector)
        seedCurrentTargets(controller, connector.id, target)
    }

    private fun connector(
        sourceId: String,
        targetId: String,
        endpoint: RouteEndpoint = RouteEndpoint.END,
        verticalOffset: Float = if (endpoint == RouteEndpoint.START) 0f else 20f,
    ): RouteConnector =
        RouteConnector(
            id = RouteConnectorId(sourceId, targetId, RouteSide.RIGHT),
            endpoint = endpoint,
            sourceId = sourceId,
            targetId = targetId,
            sourceSide = RouteSide.RIGHT,
            targetSide = RouteSide.LEFT,
            sourceDistance = 0f,
            targetDistance = 0f,
            sourcePoint = RoutePoint(0f, verticalOffset),
            targetPoint = RoutePoint(10f, verticalOffset),
            length = 10f,
            requiresWindowBridge = true,
        )

    private fun routeFrame(vararg connectors: RouteConnector): RouteFrame =
        RouteFrame(
            signal =
                WaveformFrame(
                    config = WaveformConfig(movement = WaveformMovement.CHAOTIC),
                    direction = TravelDirection.CLOCKWISE,
                ),
            centerDistance = 10f,
            signalSpan = 20f,
            currentSurfaceId = "Commit",
            slices = connectors.map(::bridgeSlice),
        )

    private fun bridgeStyle(): RouteLayerStyle =
        RouteLayerStyle(
            accent = Color.ORANGE,
            glowStyle = GlowStyle.SOFT,
            intensity = 100,
            width = 2,
            arcWidth = 2,
            config = WaveformConfig(movement = WaveformMovement.CHAOTIC),
        )

    private fun bridgeSlice(connector: RouteConnector): RouteSlice =
        RouteSlice(
            target = RoutePaintTarget.WindowBridge(connector.id),
            surfaceId = null,
            samples =
                listOf(
                    WaveformSample(
                        x = 5f,
                        y = connector.sourcePoint.y,
                        normalX = 0f,
                        normalY = -1f,
                        distance = 5f,
                        amplitudeMask = 1f,
                    ),
                ),
            distanceOffset = 0f,
            inwardEdges = emptySet(),
        )
}
