package dev.ayuislands.glow.waveform

import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test as TestCase

class RouteCoordinatorTest {
    @TestCase
    fun `route completes a full perimeter before using the planned exit`() {
        val graph =
            testGraph(
                lengths = mapOf("Editor" to 1_000f, "Commit" to 600f),
                edges =
                    listOf(
                        TestEdge(
                            sourceId = "Editor",
                            targetId = "Commit",
                            sourceDistance = 250f,
                            targetDistance = 100f,
                            connectorLength = 12f,
                        ),
                    ),
            )
        val coordinator = testCoordinator(random = seededRandom(3))
        coordinator.handle(RouteEvent.Activate(graph, focusedSurfaceId = "Editor", powerSaveEnabled = false))

        coordinator.handle(RouteEvent.Tick(0L))
        val beforeLap = coordinator.handle(RouteEvent.Tick(19_000L))
        val afterExit = coordinator.handle(RouteEvent.Tick(25_500L))

        assertEquals("Editor", requireNotNull(beforeLap.frame).currentSurfaceId)
        assertEquals("Commit", requireNotNull(afterExit.frame).currentSurfaceId)
    }

    @TestCase
    fun `clockwise handoff prefers straight top endpoint over earlier bottom endpoint`() {
        val coordinator = testCoordinator(DirectionRandom(TravelDirection.CLOCKWISE))
        val driver = RouteDriver(coordinator)
        coordinator.handle(RouteEvent.Activate(cornerGraph(sourceAnchor = 110f), "Editor", false))
        coordinator.handle(RouteEvent.Tick(0L))

        val arrival = driver.advanceTimed("Commit")

        assertTrue(arrival.nowMs in 39_000L..40_000L, "arrival was ${arrival.nowMs} ms")
        assertEquals(TravelDirection.CLOCKWISE, coordinator.snapshot.direction)
    }

    @TestCase
    fun `counter clockwise handoff prefers straight bottom endpoint over earlier top endpoint`() {
        val coordinator = testCoordinator(DirectionRandom(TravelDirection.COUNTER_CLOCKWISE))
        val driver = RouteDriver(coordinator)
        coordinator.handle(RouteEvent.Activate(cornerGraph(sourceAnchor = 190f), "Editor", false))
        coordinator.handle(RouteEvent.Tick(0L))

        val arrival = driver.advanceTimed("Commit")

        assertTrue(arrival.nowMs in 39_000L..40_000L, "arrival was ${arrival.nowMs} ms")
        assertEquals(TravelDirection.COUNTER_CLOCKWISE, coordinator.snapshot.direction)
    }

    @TestCase
    fun `shorter bridge outranks smoother earlier exit`() {
        val coordinator = testCoordinator(DirectionRandom(TravelDirection.CLOCKWISE))
        val driver = RouteDriver(coordinator)
        coordinator.handle(RouteEvent.Activate(lengthPriorityGraph(), "Editor", false))
        coordinator.handle(RouteEvent.Tick(0L))

        val arrival = driver.advanceTimed("Commit")

        assertTrue(arrival.nowMs in 41_500L..42_500L, "arrival was ${arrival.nowMs} ms")
        assertEquals(TravelDirection.CLOCKWISE, coordinator.snapshot.direction)
    }

    @TestCase
    fun `bridge alignment outranks earlier exit for equal lengths`() {
        val coordinator = testCoordinator(DirectionRandom(TravelDirection.CLOCKWISE))
        val driver = RouteDriver(coordinator)
        coordinator.handle(RouteEvent.Activate(turnPriorityGraph(), "Editor", false))
        coordinator.handle(RouteEvent.Tick(0L))

        val arrival = driver.advanceTimed("Commit")

        assertTrue(arrival.nowMs in 44_000L..45_000L, "arrival was ${arrival.nowMs} ms")
        assertEquals(TravelDirection.CLOCKWISE, coordinator.snapshot.direction)
    }

    @TestCase
    fun `earlier exit outranks stable endpoint order when geometry ties`() {
        val coordinator = testCoordinator(DirectionRandom(TravelDirection.CLOCKWISE))
        val driver = RouteDriver(coordinator)
        coordinator.handle(RouteEvent.Activate(exitPriorityGraph(), "Editor", false))
        coordinator.handle(RouteEvent.Tick(0L))

        val arrival = driver.advanceTimed("Commit")

        assertTrue(arrival.nowMs in 27_500L..27_600L, "arrival was ${arrival.nowMs} ms")
        assertEquals(TravelDirection.CLOCKWISE, coordinator.snapshot.direction)
    }

    @TestCase
    fun `every adjacent side preserves geometric direction in both source directions`() {
        TravelDirection.entries.forEach { sourceDirection ->
            val sourceAnchor =
                when (sourceDirection) {
                    TravelDirection.CLOCKWISE -> 110f
                    TravelDirection.COUNTER_CLOCKWISE -> 190f
                }
            repeat(4) { quarterTurns ->
                val coordinator = testCoordinator(DirectionRandom(sourceDirection))
                val driver = RouteDriver(coordinator)
                coordinator.handle(
                    RouteEvent.Activate(
                        cornerGraph(sourceAnchor).rotated(quarterTurns),
                        "Editor",
                        false,
                    ),
                )
                coordinator.handle(RouteEvent.Tick(0L))

                val arrival = driver.advanceTimed("Commit")

                assertTrue(
                    arrival.nowMs in 39_000L..40_000L,
                    "$sourceDirection rotation $quarterTurns arrived at ${arrival.nowMs} ms",
                )
                assertEquals(
                    sourceDirection,
                    coordinator.snapshot.direction,
                    "$sourceDirection rotation $quarterTurns changed direction",
                )
            }
        }
    }

    @TestCase
    fun `previous island is excluded while another neighbor exists`() {
        val coordinator = testCoordinator(random = seededRandom(11))
        val graph =
            testGraph(
                lengths = mapOf("Editor" to 400f, "Commit" to 400f, "Problems" to 400f),
                edges =
                    listOf(
                        TestEdge("Editor", "Commit"),
                        TestEdge("Commit", "Problems"),
                    ),
            )
        val driver = RouteDriver(coordinator)
        coordinator.handle(RouteEvent.Activate(graph, "Editor", false))

        driver.advanceUntilSurface("Commit")
        driver.advanceUntilSurface("Problems")

        assertEquals("Problems", coordinator.snapshot.currentSurfaceId)
    }

    @TestCase
    fun `only neighbor permits return to previous island`() {
        val coordinator = testCoordinator(random = seededRandom(13))
        val graph =
            testGraph(
                lengths = mapOf("Editor" to 400f, "Commit" to 400f),
                edges = listOf(TestEdge("Editor", "Commit")),
            )
        val driver = RouteDriver(coordinator)
        coordinator.handle(RouteEvent.Activate(graph, "Editor", false))

        driver.advanceUntilSurface("Commit")
        driver.advanceUntilSurface("Editor")

        assertEquals("Editor", coordinator.snapshot.currentSurfaceId)
    }

    @TestCase
    fun `invalid alternative still permits return to finite previous island`() {
        val coordinator = testCoordinator(random = seededRandom(14))
        val graph =
            testGraph(
                lengths = mapOf("Editor" to 400f, "Commit" to 400f, "Problems" to 400f),
                edges =
                    listOf(
                        TestEdge("Editor", "Commit", connectorLength = 0f),
                        TestEdge("Commit", "Problems", connectorLength = Float.NaN),
                    ),
            )
        val driver = RouteDriver(coordinator)
        coordinator.handle(RouteEvent.Activate(graph, "Editor", false))

        driver.advanceUntilSurface("Commit")
        val returned = driver.advanceUntilSurface("Editor")

        assertEquals("Editor", returned.currentSurfaceId)
        assertFiniteFrame(returned)
    }

    @TestCase
    fun `neighbor selection is uniform by neighbor rather than connector count`() {
        val graph =
            testGraph(
                lengths =
                    mapOf(
                        "Editor" to 400f,
                        "Commit" to 400f,
                        "Problems" to 400f,
                        "Git" to 400f,
                    ),
                edges =
                    listOf(
                        TestEdge("Editor", "Commit"),
                        TestEdge("Editor", "Problems"),
                        TestEdge("Editor", "Git"),
                        TestEdge("Editor", "Git", sourceDistance = 100f),
                    ),
            )
        val counts =
            (0 until 900)
                .map { seed ->
                    val coordinator = testCoordinator(seededRandom(seed))
                    coordinator.handle(RouteEvent.Activate(graph, "Editor", false))
                    requireNotNull(coordinator.snapshot.plannedTargetId)
                }.groupingBy { it }
                .eachCount()

        assertEquals(setOf("Commit", "Problems", "Git"), counts.keys)
        assertTrue(counts.values.all { it in 250..350 }, "unexpected distribution: $counts")
    }

    @TestCase
    fun `activation selects focus then editor then a uniform random surface`() {
        val focusedGraph =
            testGraph(
                lengths = mapOf("Editor" to 400f, "Commit" to 400f),
                edges = emptyList(),
            )
        val focused = testCoordinator(seededRandom(1))
        focused.handle(RouteEvent.Activate(focusedGraph, "Commit", false))
        assertEquals("Commit", focused.snapshot.currentSurfaceId)

        val editorFallback = testCoordinator(seededRandom(2))
        editorFallback.handle(RouteEvent.Activate(focusedGraph, "Missing", false))
        assertEquals("Editor", editorFallback.snapshot.currentSurfaceId)

        val randomGraph =
            testGraph(
                lengths = mapOf("Commit" to 400f, "Git" to 400f, "Problems" to 400f),
                edges = emptyList(),
            )
        val counts =
            (0 until 300)
                .map { seed ->
                    testCoordinator(seededRandom(seed))
                        .also { coordinator ->
                            coordinator.handle(RouteEvent.Activate(randomGraph, "Missing", false))
                        }.snapshot.currentSurfaceId
                }.groupingBy { it }
                .eachCount()

        assertEquals(setOf("Commit", "Git", "Problems"), counts.keys)
        assertTrue(counts.values.all { it in 75..125 }, "unexpected distribution: $counts")
    }

    @TestCase
    fun `local direction stays fixed for one island entry`() {
        val coordinator = testCoordinator(seededRandom(5))
        val graph = testGraph(mapOf("Editor" to 400f), emptyList())
        coordinator.handle(RouteEvent.Activate(graph, "Editor", false))
        val initialDirection = requireNotNull(coordinator.snapshot.direction)

        coordinator.handle(RouteEvent.Tick(0L))
        repeat(12) { index ->
            coordinator.handle(RouteEvent.Tick((index + 1) * 1_000L))
            assertEquals(initialDirection, coordinator.snapshot.direction)
        }
    }

    @TestCase
    fun `connector preserves motion`() {
        val random =
            object : kotlin.random.Random() {
                override fun nextBits(bitCount: Int): Int = 0
            }
        val coordinator = testCoordinator(random)
        val graph =
            testGraph(
                lengths = mapOf("Editor" to 400f, "Commit" to 400f),
                edges = listOf(TestEdge("Editor", "Commit")),
            )
        val driver = RouteDriver(coordinator)
        coordinator.handle(RouteEvent.Activate(graph, "Editor", false))
        val sourceDirection = requireNotNull(coordinator.snapshot.direction)

        driver.advanceUntilSurface("Commit")

        val targetDirection = requireNotNull(coordinator.snapshot.direction)
        val continuedDirection =
            when (sourceDirection) {
                TravelDirection.CLOCKWISE -> TravelDirection.COUNTER_CLOCKWISE
                TravelDirection.COUNTER_CLOCKWISE -> TravelDirection.CLOCKWISE
            }
        assertEquals(continuedDirection, targetDirection)
    }

    @TestCase
    fun `planned exit is reached after at least one lap and before two`() {
        val coordinator = testCoordinator(seededRandom(7))
        val graph =
            testGraph(
                lengths = mapOf("Editor" to 400f, "Commit" to 400f),
                edges = listOf(TestEdge("Editor", "Commit", sourceDistance = 100f)),
            )
        val driver = RouteDriver(coordinator)
        coordinator.handle(RouteEvent.Activate(graph, "Editor", false))

        val arrival = driver.advanceTimed("Commit")

        assertTrue(arrival.nowMs >= 20_000L, "departed before one full lap at ${arrival.nowMs} ms")
        assertTrue(arrival.nowMs < 41_000L, "departed after two laps at ${arrival.nowMs} ms")
    }

    @TestCase
    fun `isolated surface keeps looping with its local direction`() {
        val coordinator = testCoordinator(seededRandom(9))
        val graph = testGraph(mapOf("Editor" to 400f), emptyList())
        coordinator.handle(RouteEvent.Activate(graph, "Editor", false))
        val direction = coordinator.snapshot.direction
        coordinator.handle(RouteEvent.Tick(0L))

        val frame = requireNotNull(coordinator.handle(RouteEvent.Tick(65_000L)).frame)

        assertEquals("Editor", frame.currentSurfaceId)
        assertEquals(direction, coordinator.snapshot.direction)
        assertNull(coordinator.snapshot.plannedTargetId)
        assertTrue(frame.centerDistance > 1_200f)
    }

    @TestCase
    fun `missing target replans immediately at the same route center`() {
        val coordinator = testCoordinator(seededRandom(17))
        val initial =
            testGraph(
                lengths = mapOf("Editor" to 400f, "Commit" to 400f),
                edges = listOf(TestEdge("Editor", "Commit")),
            )
        val replacement =
            testGraph(
                lengths = mapOf("Editor" to 400f, "Problems" to 400f),
                edges = listOf(TestEdge("Editor", "Problems")),
            )
        coordinator.handle(RouteEvent.Activate(initial, "Editor", false))
        val before = requireNotNull(coordinator.handle(RouteEvent.Tick(0L)).frame)

        val rebound = requireNotNull(coordinator.handle(RouteEvent.GraphChanged(replacement)).frame)

        assertEquals("Problems", coordinator.snapshot.plannedTargetId)
        assertEquals(before.centerDistance, rebound.centerDistance, 0.001f)
        assertEquals(1f, rebound.alpha)
    }

    @TestCase
    fun `missing current surface fades then selects editor fallback`() {
        val coordinator = testCoordinator(seededRandom(19))
        val initial = testGraph(mapOf("Commit" to 400f), emptyList())
        val replacement = testGraph(mapOf("Editor" to 400f, "Problems" to 400f), emptyList())
        coordinator.handle(RouteEvent.Activate(initial, "Commit", false))
        coordinator.handle(RouteEvent.Tick(0L))
        val fadeStart = requireNotNull(coordinator.handle(RouteEvent.GraphChanged(replacement)).frame)

        val fadeMiddle = requireNotNull(coordinator.handle(RouteEvent.Tick(80L)).frame)
        val recovered = requireNotNull(coordinator.handle(RouteEvent.Tick(160L)).frame)

        assertEquals("Commit", fadeStart.currentSurfaceId)
        assertEquals(1f, fadeStart.alpha, 0.001f)
        assertEquals(0.5f, fadeMiddle.alpha, 0.01f)
        assertEquals("Editor", recovered.currentSurfaceId)
        assertEquals(1f, recovered.alpha, 0.001f)
    }

    @TestCase
    fun `changed topology replans immediately at the same route center`() {
        val coordinator = testCoordinator(seededRandom(23))
        val isolated = testGraph(mapOf("Editor" to 400f), emptyList())
        val connected =
            testGraph(
                lengths = mapOf("Editor" to 400f, "Commit" to 400f),
                edges = listOf(TestEdge("Editor", "Commit")),
            )
        coordinator.handle(RouteEvent.Activate(isolated, "Editor", false))
        val before = requireNotNull(coordinator.handle(RouteEvent.Tick(0L)).frame)

        val rebound = requireNotNull(coordinator.handle(RouteEvent.GraphChanged(connected)).frame)

        assertEquals("Commit", coordinator.snapshot.plannedTargetId)
        assertEquals(before.centerDistance, rebound.centerDistance, 0.001f)
        assertEquals(1f, rebound.alpha)
    }

    @TestCase
    fun `additive topology preserves the planned connector`() {
        val initial =
            testGraph(
                lengths = mapOf("Editor" to 400f, "Commit" to 400f),
                edges = listOf(TestEdge("Editor", "Commit")),
            )
        val expanded =
            testGraph(
                lengths = mapOf("Editor" to 400f, "Commit" to 400f, "Problems" to 400f),
                edges =
                    listOf(
                        TestEdge("Editor", "Commit"),
                        TestEdge("Editor", "Problems"),
                    ),
            )

        val plannedTargets =
            (101..116).map { seed ->
                val coordinator = testCoordinator(seededRandom(seed))
                coordinator.handle(RouteEvent.Activate(initial, "Editor", false))
                coordinator.handle(RouteEvent.Tick(0L))
                assertEquals("Commit", coordinator.snapshot.plannedTargetId)

                coordinator.handle(RouteEvent.GraphChanged(expanded))
                coordinator.handle(RouteEvent.Tick(20_000L))

                coordinator.snapshot.plannedTargetId
            }

        assertEquals(setOf("Commit"), plannedTargets.toSet())
    }

    @TestCase
    fun `identical topology rebinds geometry immediately with normalized progress`() {
        val coordinator = testCoordinator(seededRandom(29))
        val initial = testGraph(mapOf("Editor" to 400f), emptyList())
        val rebound = testGraph(mapOf("Editor" to 800f), emptyList(), yOffset = 60f)
        coordinator.handle(RouteEvent.Activate(initial, "Editor", false))
        coordinator.handle(RouteEvent.Tick(0L))
        coordinator.handle(RouteEvent.Tick(5_000L))
        assertEquals(100f, coordinator.snapshot.distanceOnLeg, 0.01f)

        val update = coordinator.handle(RouteEvent.GraphChanged(rebound))
        val frame = requireNotNull(update.frame)

        assertEquals(200f, coordinator.snapshot.distanceOnLeg, 0.01f)
        assertTrue(frame.slices.flatMap(RouteSlice::samples).all { sample -> sample.y >= 60f })
    }

    @TestCase
    fun `active gap rebind to touching enters target in the returned frame`() {
        val coordinator = testCoordinator(DirectionRandom(TravelDirection.COUNTER_CLOCKWISE))
        val initial =
            testGraph(
                lengths = mapOf("Editor" to 100f, "Commit" to 100f),
                edges = listOf(TestEdge("Editor", "Commit", connectorLength = 100f)),
                signalSpans = mapOf("Editor" to 80f, "Commit" to 80f),
            )
        val touching =
            testGraph(
                lengths = mapOf("Editor" to 100f, "Commit" to 100f),
                edges = listOf(TestEdge("Editor", "Commit", connectorLength = 0f)),
                signalSpans = mapOf("Editor" to 80f, "Commit" to 80f),
            )
        coordinator.handle(RouteEvent.Activate(initial, "Editor", false))
        coordinator.handle(RouteEvent.Tick(0L))
        coordinator.handle(RouteEvent.Keystroke(20_500L))
        val gapFrame = requireNotNull(coordinator.handle(RouteEvent.Tick(21_000L)).frame)

        val rebound = requireNotNull(coordinator.handle(RouteEvent.GraphChanged(touching)).frame)
        val next = requireNotNull(coordinator.handle(RouteEvent.Tick(21_001L)).frame)

        assertEquals("Commit", rebound.currentSurfaceId)
        assertEquals("Commit", next.currentSurfaceId)
        assertEquals(TravelDirection.CLOCKWISE, rebound.signal.direction)
        assertEquals(TravelDirection.CLOCKWISE, next.signal.direction)
        assertEquals(gapFrame.centerDistance, rebound.centerDistance, 0.001f)
        assertTrue(next.centerDistance >= rebound.centerDistance)
        assertEquals(1f, rebound.alpha)
        assertEquals(1f, next.alpha)
        assertEquals(1, rebound.slices.count { slice -> slice.surfaceId == "Editor" })
        assertTrue(rebound.slices.none { slice -> slice.surfaceId == null })
        assertEquals(gapFrame.signal.morphology, rebound.signal.morphology)
        assertEquals(gapFrame.signal.trace?.history, rebound.signal.trace?.history)
        assertEquals(gapFrame.signal.energy, rebound.signal.energy, 0.001f)
        listOf(rebound, next).forEach(::assertFiniteFrame)
    }

    @TestCase
    fun `geometry rebind retains source tail on moved coordinates`() {
        val coordinator = testCoordinator(DirectionRandom(TravelDirection.COUNTER_CLOCKWISE))
        val initial =
            testGraph(
                lengths = mapOf("Editor" to 100f, "Commit" to 200f),
                edges = listOf(TestEdge("Editor", "Commit", connectorLength = 0f)),
                signalSpans = mapOf("Editor" to 80f, "Commit" to 160f),
            )
        val moved =
            testGraph(
                lengths = mapOf("Editor" to 100f, "Commit" to 200f),
                edges = listOf(TestEdge("Editor", "Commit", connectorLength = 0f)),
                signalSpans = mapOf("Editor" to 80f, "Commit" to 160f),
                yOffset = 30f,
            )
        coordinator.handle(RouteEvent.Activate(initial, "Editor", false))
        coordinator.handle(RouteEvent.Tick(0L))
        val before = requireNotNull(coordinator.handle(RouteEvent.Tick(21_000L)).frame)
        val sourceOffset = before.slices.single { slice -> slice.surfaceId == "Editor" }.distanceOffset

        val rebound = requireNotNull(coordinator.handle(RouteEvent.GraphChanged(moved)).frame)
        val next = requireNotNull(coordinator.handle(RouteEvent.Tick(21_001L)).frame)

        listOf(rebound, next).forEach { frame ->
            val source = frame.slices.single { slice -> slice.surfaceId == "Editor" }
            assertEquals(sourceOffset, source.distanceOffset, 0.001f)
            assertTrue(source.samples.isNotEmpty())
            assertTrue(frame.slices.flatMap(RouteSlice::samples).all { sample -> sample.y == 30f })
            assertFiniteFrame(frame)
        }
    }

    @TestCase
    fun `removed connector drops the disconnected source tail`() {
        val coordinator = testCoordinator(DirectionRandom(TravelDirection.COUNTER_CLOCKWISE))
        val connected =
            testGraph(
                lengths = mapOf("Editor" to 100f, "Commit" to 200f),
                edges = listOf(TestEdge("Editor", "Commit", connectorLength = 0f)),
                signalSpans = mapOf("Editor" to 80f, "Commit" to 160f),
            )
        val disconnected =
            testGraph(
                lengths = mapOf("Editor" to 100f, "Commit" to 200f),
                edges = emptyList(),
                signalSpans = mapOf("Editor" to 80f, "Commit" to 160f),
            )
        coordinator.handle(RouteEvent.Activate(connected, "Editor", false))
        coordinator.handle(RouteEvent.Tick(0L))
        val before = requireNotNull(coordinator.handle(RouteEvent.Tick(21_000L)).frame)
        assertTrue(before.slices.any { slice -> slice.surfaceId == "Editor" })

        val rebound = requireNotNull(coordinator.handle(RouteEvent.GraphChanged(disconnected)).frame)

        assertEquals("Commit", rebound.currentSurfaceId)
        assertTrue(rebound.slices.none { slice -> slice.surfaceId == "Editor" })
        assertTrue(rebound.slices.any { slice -> slice.surfaceId == "Commit" })
        assertEquals(1f, rebound.alpha)
        assertFiniteFrame(rebound)
    }

    @TestCase
    fun `endpoint loss replans the current route immediately`() {
        val random =
            object : kotlin.random.Random() {
                override fun nextBits(bitCount: Int): Int = 0
            }
        val initial =
            testGraph(
                lengths = mapOf("Editor" to 1_000f, "Commit" to 600f),
                edges =
                    listOf(
                        TestEdge("Editor", "Commit", RouteEndpoint.START, sourceDistance = 200f),
                        TestEdge("Editor", "Commit", RouteEndpoint.END, sourceDistance = 800f),
                    ),
            )
        val collapsed =
            testGraph(
                lengths = mapOf("Editor" to 1_000f, "Commit" to 600f),
                edges = listOf(TestEdge("Editor", "Commit", RouteEndpoint.START, sourceDistance = 200f)),
            )
        val coordinator = testCoordinator(random)
        coordinator.handle(RouteEvent.Activate(initial, "Editor", false))
        coordinator.handle(RouteEvent.Tick(0L))
        val activeConnector = requireNotNull(coordinator.handle(RouteEvent.Tick(24_100L)).frame)

        val refreshed = coordinator.handle(RouteEvent.GraphChanged(collapsed))
        val frame = requireNotNull(refreshed.frame)

        assertEquals(activeConnector.centerDistance, frame.centerDistance, 0.001f)
        assertEquals("Editor", frame.currentSurfaceId)
        assertEquals(1f, frame.alpha)
        assertTrue(frame.slices.none { slice -> slice.surfaceId == null })
        assertEquals("Commit", coordinator.snapshot.plannedTargetId)
    }

    @TestCase
    fun `bridge failure removes both directions and never crosses the failed edge`() {
        val coordinator = testCoordinator(seededRandom(31))
        val graph =
            testGraph(
                lengths = mapOf("Editor" to 400f, "Commit" to 400f),
                edges = listOf(TestEdge("Editor", "Commit", connectorLength = 200f)),
            )
        val connectorId = graph.connectorsFrom("Editor").single().id
        coordinator.handle(RouteEvent.Activate(graph, "Editor", false))
        coordinator.handle(RouteEvent.Tick(0L))
        coordinator.handle(RouteEvent.Tick(20_100L))

        coordinator.handle(RouteEvent.BridgeFailed(connectorId))
        repeat(500) { index ->
            coordinator.handle(RouteEvent.Tick(20_200L + index * 100L))
            assertNotEquals("Commit", coordinator.snapshot.currentSurfaceId)
        }
        assertNull(coordinator.snapshot.plannedTargetId)
    }

    @TestCase
    fun `bridge failure removes window target immediately`() {
        val coordinator = testCoordinator(seededRandom(73))
        val graph =
            testGraph(
                lengths = mapOf("Editor" to 400f, "Commit" to 400f),
                edges =
                    listOf(
                        TestEdge(
                            sourceId = "Editor",
                            targetId = "Commit",
                            connectorLength = 200f,
                            requiresWindowBridge = true,
                        ),
                    ),
            )
        val connectorId = graph.connectorsFrom("Editor").single().id
        coordinator.handle(RouteEvent.Activate(graph, "Editor", false))
        coordinator.handle(RouteEvent.Tick(0L))
        val bridgeFrame = requireNotNull(coordinator.handle(RouteEvent.Tick(20_100L)).frame)
        assertTrue(bridgeFrame.slices.any { slice -> slice.target == RoutePaintTarget.WindowBridge(connectorId) })

        val failed = requireNotNull(coordinator.handle(RouteEvent.BridgeFailed(connectorId)).frame)
        repeat(500) { index ->
            coordinator.handle(RouteEvent.Tick(20_200L + index * 100L))
            assertNotEquals("Commit", coordinator.snapshot.currentSurfaceId)
        }

        assertTrue(failed.slices.none { slice -> slice.target == RoutePaintTarget.WindowBridge(connectorId) })
        assertNull(coordinator.snapshot.plannedTargetId)
    }

    @TestCase
    fun `activation focus changes never redirect an active route`() {
        val coordinator = testCoordinator(seededRandom(37))
        val graph =
            testGraph(
                lengths = mapOf("Editor" to 400f, "Commit" to 400f),
                edges = emptyList(),
            )
        coordinator.handle(RouteEvent.Activate(graph, "Editor", false))
        coordinator.handle(RouteEvent.Tick(0L))
        coordinator.handle(RouteEvent.Tick(4_000L))
        val before = coordinator.snapshot

        val update = coordinator.handle(RouteEvent.Activate(graph, "Commit", false))

        assertEquals(before, coordinator.snapshot)
        assertEquals(TimerDirective.KEEP, update.timerDirective)
    }

    @TestCase
    fun `application and power save suspension require every reason to clear`() {
        val coordinator = testCoordinator(seededRandom(41))
        val graph = testGraph(mapOf("Editor" to 400f), emptyList())
        coordinator.handle(RouteEvent.Activate(graph, "Editor", false))
        coordinator.handle(RouteEvent.Tick(0L))
        val moving = requireNotNull(coordinator.handle(RouteEvent.Tick(5_000L)).frame)

        val appStopped = coordinator.handle(RouteEvent.ApplicationActiveChanged(false))
        val powerStopped = coordinator.handle(RouteEvent.PowerSaveChanged(true))
        val appOnlyResumed = coordinator.handle(RouteEvent.ApplicationActiveChanged(true))
        val resumed = coordinator.handle(RouteEvent.PowerSaveChanged(false))

        assertEquals(TimerDirective.STOP, appStopped.timerDirective)
        assertEquals(moving, appStopped.frame)
        assertEquals(TimerDirective.STOP, powerStopped.timerDirective)
        assertEquals(TimerDirective.KEEP, appOnlyResumed.timerDirective)
        assertEquals(TimerDirective.START, resumed.timerDirective)
    }

    @TestCase
    fun `empty graph clears a frame while routing is suspended`() {
        val coordinator = testCoordinator(seededRandom(42))
        val graph = testGraph(mapOf("Editor" to 400f), emptyList())
        coordinator.handle(RouteEvent.Activate(graph, "Editor", false))
        coordinator.handle(RouteEvent.Tick(0L))
        assertNotNull(coordinator.handle(RouteEvent.Tick(1_000L)).frame)
        coordinator.handle(RouteEvent.PowerSaveChanged(true))

        val emptied = coordinator.handle(RouteEvent.GraphChanged(testGraph(emptyMap(), emptyList())))
        val resumed = coordinator.handle(RouteEvent.PowerSaveChanged(false))

        assertEquals(TimerDirective.STOP, emptied.timerDirective)
        assertNull(emptied.frame)
        assertEquals(TimerDirective.STOP, resumed.timerDirective)
        assertNull(resumed.frame)
    }

    @TestCase
    fun `first tick after suspension has no wall time jump`() {
        val coordinator = testCoordinator(seededRandom(43))
        val graph = testGraph(mapOf("Editor" to 400f), emptyList())
        coordinator.handle(RouteEvent.Activate(graph, "Editor", false))
        coordinator.handle(RouteEvent.Tick(0L))
        coordinator.handle(RouteEvent.Tick(5_000L))
        val before = coordinator.snapshot.distanceOnLeg
        coordinator.handle(RouteEvent.ApplicationActiveChanged(false))
        coordinator.handle(RouteEvent.Tick(500_000L))
        assertEquals(before, coordinator.snapshot.distanceOnLeg, 0.001f)

        coordinator.handle(RouteEvent.ApplicationActiveChanged(true))
        coordinator.handle(RouteEvent.Tick(900_000L))
        assertEquals(before, coordinator.snapshot.distanceOnLeg, 0.001f)

        coordinator.handle(RouteEvent.Tick(901_000L))
        assertTrue(coordinator.snapshot.distanceOnLeg > before)
    }

    @TestCase
    fun `resume preserves the pre-suspend frame`() {
        val coordinator = testCoordinator(seededRandom(97))
        val graph = testGraph(mapOf("Editor" to 400f), emptyList())
        coordinator.handle(RouteEvent.Activate(graph, "Editor", false))
        val stable = requireNotNull(coordinator.handle(RouteEvent.Tick(0L)).frame)
        coordinator.handle(RouteEvent.Keystroke(500L))
        coordinator.handle(RouteEvent.Keystroke(600L))
        val appStopped = coordinator.handle(RouteEvent.ApplicationActiveChanged(false))
        val powerStopped = coordinator.handle(RouteEvent.PowerSaveChanged(true))
        val ignoredKey = coordinator.handle(RouteEvent.Keystroke(9_000L))
        val appCleared = coordinator.handle(RouteEvent.ApplicationActiveChanged(true))
        val resumed = coordinator.handle(RouteEvent.PowerSaveChanged(false))
        val firstTick = requireNotNull(coordinator.handle(RouteEvent.Tick(10_000L)).frame)

        assertEquals(TimerDirective.STOP, appStopped.timerDirective)
        assertEquals(TimerDirective.STOP, powerStopped.timerDirective)
        assertEquals(TimerDirective.KEEP, ignoredKey.timerDirective)
        assertEquals(TimerDirective.KEEP, appCleared.timerDirective)
        assertEquals(TimerDirective.START, resumed.timerDirective)
        assertEquals(stable, appStopped.frame)
        assertEquals(stable, powerStopped.frame)
        assertEquals(stable, ignoredKey.frame)
        assertEquals(stable, resumed.frame)
        assertEquals(stable, firstTick)
        assertEquals(0f, coordinator.snapshot.distanceOnLeg, 0.001f)

        val nextTick = requireNotNull(coordinator.handle(RouteEvent.Tick(11_000L)).frame)

        assertEquals(20f, coordinator.snapshot.distanceOnLeg, 0.001f)
        assertTrue(nextTick.signal.energy > firstTick.signal.energy)
        assertNotEquals(firstTick.signal, nextTick.signal)
    }

    @TestCase
    fun `focus bounce before the first resumed frame keeps the route frozen`() {
        val coordinator = testCoordinator(seededRandom(101))
        val graph = testGraph(mapOf("Editor" to 400f), emptyList())
        coordinator.handle(RouteEvent.Activate(graph, "Editor", false))
        coordinator.handle(RouteEvent.Tick(0L))
        val stable = requireNotNull(coordinator.handle(RouteEvent.Tick(5_000L)).frame)
        val distanceBefore = coordinator.snapshot.distanceOnLeg
        coordinator.handle(RouteEvent.ApplicationActiveChanged(false))
        val resumed = coordinator.handle(RouteEvent.ApplicationActiveChanged(true))

        val stoppedAgain = coordinator.handle(RouteEvent.ApplicationActiveChanged(false))
        val ignoredTick = coordinator.handle(RouteEvent.Tick(500_000L))

        assertEquals(TimerDirective.START, resumed.timerDirective)
        assertEquals(TimerDirective.STOP, stoppedAgain.timerDirective)
        assertEquals(stable, stoppedAgain.frame)
        assertEquals(stable, ignoredTick.frame)
        assertEquals(distanceBefore, coordinator.snapshot.distanceOnLeg, 0.001f)
    }

    @TestCase
    fun `power save before the first resumed frame keeps the route frozen`() {
        val coordinator = testCoordinator(seededRandom(103))
        val graph = testGraph(mapOf("Editor" to 400f), emptyList())
        coordinator.handle(RouteEvent.Activate(graph, "Editor", false))
        coordinator.handle(RouteEvent.Tick(0L))
        val stable = requireNotNull(coordinator.handle(RouteEvent.Tick(5_000L)).frame)
        val distanceBefore = coordinator.snapshot.distanceOnLeg
        coordinator.handle(RouteEvent.ApplicationActiveChanged(false))
        coordinator.handle(RouteEvent.ApplicationActiveChanged(true))

        val powerStopped = coordinator.handle(RouteEvent.PowerSaveChanged(true))
        val ignoredTick = coordinator.handle(RouteEvent.Tick(500_000L))

        assertEquals(TimerDirective.STOP, powerStopped.timerDirective)
        assertEquals(stable, powerStopped.frame)
        assertEquals(stable, ignoredTick.frame)
        assertEquals(distanceBefore, coordinator.snapshot.distanceOnLeg, 0.001f)
    }

    @TestCase
    fun `keystroke before the first resumed frame energizes the next moving frame`() {
        val coordinator = testCoordinator(seededRandom(107))
        val graph = testGraph(mapOf("Editor" to 400f), emptyList())
        coordinator.handle(RouteEvent.Activate(graph, "Editor", false))
        val stable = requireNotNull(coordinator.handle(RouteEvent.Tick(0L)).frame)
        coordinator.handle(RouteEvent.ApplicationActiveChanged(false))
        coordinator.handle(RouteEvent.ApplicationActiveChanged(true))

        val keyed = coordinator.handle(RouteEvent.Keystroke(9_000L))
        val firstTick = requireNotNull(coordinator.handle(RouteEvent.Tick(10_000L)).frame)
        val moving = requireNotNull(coordinator.handle(RouteEvent.Tick(10_100L)).frame)

        assertEquals(TimerDirective.KEEP, keyed.timerDirective)
        assertEquals(stable, keyed.frame)
        assertEquals(stable, firstTick)
        assertTrue(moving.signal.energy > firstTick.signal.energy)
    }

    @TestCase
    fun `duplicate resume notifications keep the stable frame`() {
        val coordinator = testCoordinator(seededRandom(109))
        val graph = testGraph(mapOf("Editor" to 400f), emptyList())
        coordinator.handle(RouteEvent.Activate(graph, "Editor", false))
        val stable = requireNotNull(coordinator.handle(RouteEvent.Tick(0L)).frame)
        coordinator.handle(RouteEvent.ApplicationActiveChanged(false))
        coordinator.handle(RouteEvent.ApplicationActiveChanged(true))

        val activeAgain = coordinator.handle(RouteEvent.ApplicationActiveChanged(true))
        val powerSaveStillClear = coordinator.handle(RouteEvent.PowerSaveChanged(false))

        assertEquals(TimerDirective.KEEP, activeAgain.timerDirective)
        assertEquals(stable, activeAgain.frame)
        assertEquals(TimerDirective.KEEP, powerSaveStillClear.timerDirective)
        assertEquals(stable, powerSaveStillClear.frame)
    }

    @TestCase
    fun `deactivating before the first resumed frame keeps glow stopped`() {
        val coordinator = testCoordinator(seededRandom(113))
        val graph = testGraph(mapOf("Editor" to 400f), emptyList())
        coordinator.handle(RouteEvent.Activate(graph, "Editor", false))
        coordinator.handle(RouteEvent.Tick(0L))
        coordinator.handle(RouteEvent.ApplicationActiveChanged(false))
        coordinator.handle(RouteEvent.ApplicationActiveChanged(true))

        val deactivated = coordinator.handle(RouteEvent.Deactivate)
        val ignoredTick = coordinator.handle(RouteEvent.Tick(500_000L))

        assertEquals(TimerDirective.STOP, deactivated.timerDirective)
        assertNull(deactivated.frame)
        assertEquals(TimerDirective.KEEP, ignoredTick.timerDirective)
        assertNull(ignoredTick.frame)
        assertNull(coordinator.snapshot.currentSurfaceId)
    }

    @TestCase
    fun `empty activation stays power-save suspended`() {
        val coordinator = testCoordinator(seededRandom(79))
        val graph = testGraph(mapOf("Editor" to 400f), emptyList())

        val activated =
            coordinator.handle(
                RouteEvent.Activate(
                    graph = RouteGraph(emptyMap(), emptyMap()),
                    focusedSurfaceId = null,
                    powerSaveEnabled = true,
                ),
            )
        val discovered = coordinator.handle(RouteEvent.GraphChanged(graph))
        coordinator.handle(RouteEvent.Keystroke(500L))
        val resumed = coordinator.handle(RouteEvent.PowerSaveChanged(false))
        val firstTick = requireNotNull(coordinator.handle(RouteEvent.Tick(1_000L)).frame)
        val distance = coordinator.snapshot.distanceOnLeg
        coordinator.handle(RouteEvent.Tick(1_800L))

        assertEquals(TimerDirective.STOP, activated.timerDirective)
        assertEquals(TimerDirective.STOP, discovered.timerDirective)
        assertEquals(TimerDirective.START, resumed.timerDirective)
        assertEquals(0f, firstTick.signal.energy, 0.001f)
        assertEquals(0f, distance, 0.001f)
        assertTrue(coordinator.snapshot.distanceOnLeg > distance)
    }

    @TestCase
    fun `delayed tick honors key time without route drift`() {
        val coordinator = testCoordinator(seededRandom(83))
        val graph = testGraph(mapOf("Editor" to 400f), emptyList())
        coordinator.handle(RouteEvent.Activate(graph, "Editor", false))
        coordinator.handle(RouteEvent.Tick(0L))

        coordinator.handle(RouteEvent.Keystroke(500L))
        val frame = requireNotNull(coordinator.handle(RouteEvent.Tick(800L)).frame)

        assertEquals(16f, coordinator.snapshot.distanceOnLeg, 0.001f)
        assertEquals(0.633f, frame.signal.energy, 0.001f)
    }

    @TestCase
    fun `delayed tick preserves repeated-key cadence`() {
        val coordinator = testCoordinator(seededRandom(89))
        val graph = testGraph(mapOf("Editor" to 400f), emptyList())
        coordinator.handle(RouteEvent.Activate(graph, "Editor", false))
        coordinator.handle(RouteEvent.Tick(0L))

        coordinator.handle(RouteEvent.Keystroke(500L))
        coordinator.handle(RouteEvent.Keystroke(600L))
        val frame = requireNotNull(coordinator.handle(RouteEvent.Tick(800L)).frame)

        assertEquals(0.8f, frame.signal.energy, 0.001f)
        assertTrue(requireNotNull(frame.signal.trace).phase < 0.3f)
    }

    @TestCase
    fun `configure preserves route morphology and energy while applying next tick speed`() {
        val coordinator = testCoordinator(seededRandom(47))
        val graph = testGraph(mapOf("Editor" to 400f), emptyList())
        coordinator.handle(RouteEvent.Activate(graph, "Editor", false))
        coordinator.handle(RouteEvent.Tick(0L))
        coordinator.handle(RouteEvent.Keystroke(100L))
        val before = requireNotNull(coordinator.handle(RouteEvent.Tick(180L)).frame)
        val beforeSnapshot = coordinator.snapshot
        val updatedConfig =
            before.signal.config.copy(
                movement = WaveformMovement.CHAOTIC,
                loopSeconds = 10f,
                amplitude = 30,
            )

        coordinator.handle(RouteEvent.Configure(updatedConfig))
        val configured = requireNotNull(coordinator.handle(RouteEvent.Tick(180L)).frame)

        assertEquals(beforeSnapshot, coordinator.snapshot)
        assertEquals(before.signal.morphology, configured.signal.morphology)
        assertEquals(before.signal.energy, configured.signal.energy, 0.001f)
        assertEquals(updatedConfig, configured.signal.config)

        val distance = coordinator.snapshot.distanceOnLeg
        coordinator.handle(RouteEvent.Tick(1_180L))
        assertEquals(distance + 40f, coordinator.snapshot.distanceOnLeg, 0.01f)
    }

    @TestCase
    fun `configure rebases active connector speed`() {
        val coordinator = testCoordinator(seededRandom(101))
        val graph =
            testGraph(
                lengths = mapOf("Editor" to 400f, "Commit" to 400f),
                edges = listOf(TestEdge("Editor", "Commit", connectorLength = 200f)),
            )
        coordinator.handle(RouteEvent.Activate(graph, "Editor", false))
        coordinator.handle(RouteEvent.Tick(0L))
        val connectorStart = requireNotNull(coordinator.handle(RouteEvent.Tick(20_000L)).frame)
        val before = coordinator.snapshot
        val updatedConfig = connectorStart.signal.config.copy(loopSeconds = 10f)

        val configured = coordinator.handle(RouteEvent.Configure(updatedConfig))
        val afterConfigure = coordinator.snapshot
        val advanced = requireNotNull(coordinator.handle(RouteEvent.Tick(21_000L)).frame)

        assertEquals(before, afterConfigure)
        assertEquals(connectorStart, configured.frame)
        assertEquals("Commit", coordinator.snapshot.plannedTargetId)
        assertEquals(40f, coordinator.snapshot.distanceOnLeg, 0.001f)
        assertEquals(updatedConfig, advanced.signal.config)
    }

    @TestCase
    fun `suspended configure rebases connector after stable resume`() {
        val coordinator = testCoordinator(seededRandom(103))
        val graph =
            testGraph(
                lengths = mapOf("Editor" to 400f, "Commit" to 400f),
                edges = listOf(TestEdge("Editor", "Commit", connectorLength = 200f)),
            )
        coordinator.handle(RouteEvent.Activate(graph, "Editor", false))
        coordinator.handle(RouteEvent.Tick(0L))
        val stable = requireNotNull(coordinator.handle(RouteEvent.Tick(20_000L)).frame)
        coordinator.handle(RouteEvent.ApplicationActiveChanged(false))
        val updatedConfig = stable.signal.config.copy(loopSeconds = 10f)

        val configured = coordinator.handle(RouteEvent.Configure(updatedConfig))
        val resumed = coordinator.handle(RouteEvent.ApplicationActiveChanged(true))
        val firstTick = requireNotNull(coordinator.handle(RouteEvent.Tick(30_000L)).frame)
        val nextTick = requireNotNull(coordinator.handle(RouteEvent.Tick(31_000L)).frame)

        assertEquals(stable, configured.frame)
        assertEquals(TimerDirective.START, resumed.timerDirective)
        assertEquals(stable, resumed.frame)
        assertEquals(stable, firstTick)
        assertEquals("Commit", coordinator.snapshot.plannedTargetId)
        assertEquals(40f, coordinator.snapshot.distanceOnLeg, 0.001f)
        assertEquals(updatedConfig, nextTick.signal.config)
    }

    @TestCase
    fun `configure rejects non chaotic movement without changing route`() {
        val coordinator = testCoordinator(seededRandom(49))
        val graph = testGraph(mapOf("Editor" to 400f), emptyList())
        coordinator.handle(RouteEvent.Activate(graph, "Editor", false))
        val before = coordinator.snapshot

        assertFailsWith<IllegalArgumentException> {
            coordinator.handle(RouteEvent.Configure(WaveformConfig(movement = WaveformMovement.CLOCKWISE)))
        }
        assertEquals(before, coordinator.snapshot)
    }

    @TestCase
    fun `connector interpolates signal span between source and target`() {
        val coordinator = testCoordinator(seededRandom(53))
        val graph =
            testGraph(
                lengths = mapOf("Editor" to 400f, "Commit" to 800f),
                edges = listOf(TestEdge("Editor", "Commit", connectorLength = 100f)),
                signalSpans = mapOf("Editor" to 100f, "Commit" to 300f),
            )
        coordinator.handle(RouteEvent.Activate(graph, "Editor", false))
        coordinator.handle(RouteEvent.Tick(0L))
        val beforeConnector = requireNotNull(coordinator.handle(RouteEvent.Tick(19_999L)).frame)
        val connectorStart = requireNotNull(coordinator.handle(RouteEvent.Tick(20_000L)).frame)
        val connectorMiddle = requireNotNull(coordinator.handle(RouteEvent.Tick(21_932L)).frame)

        assertTrue(beforeConnector.slices.any { slice -> slice.surfaceId == null })
        assertTrue(connectorStart.slices.any { slice -> slice.surfaceId == "Commit" })
        assertTrue(connectorMiddle.signalSpan in 185f..215f, "span was ${connectorMiddle.signalSpan}")
        assertTrue(connectorMiddle.slices.any { slice -> slice.surfaceId == null })
        assertEquals(1f, connectorMiddle.alpha)
    }

    @TestCase
    fun `sub epsilon connector enters target without a connector leg`() {
        val coordinator = testCoordinator(DirectionRandom(TravelDirection.COUNTER_CLOCKWISE))
        val graph =
            testGraph(
                lengths = mapOf("Editor" to 100f, "Commit" to 200f),
                edges = listOf(TestEdge("Editor", "Commit", connectorLength = 0.0005f)),
            )
        coordinator.handle(RouteEvent.Activate(graph, "Editor", false))
        coordinator.handle(RouteEvent.Tick(0L))

        val exactExit = requireNotNull(coordinator.handle(RouteEvent.Tick(20_000L)).frame)

        assertEquals("Commit", exactExit.currentSurfaceId)
        assertEquals(100f, exactExit.centerDistance, 0.001f)
        assertTrue(exactExit.slices.none { slice -> slice.surfaceId == null })
    }

    @TestCase
    fun `non finite connector is not treated as touching`() {
        val coordinator = testCoordinator(DirectionRandom(TravelDirection.COUNTER_CLOCKWISE))
        val graph =
            testGraph(
                lengths = mapOf("Editor" to 100f, "Commit" to 200f),
                edges = listOf(TestEdge("Editor", "Commit", connectorLength = Float.NaN)),
            )
        coordinator.handle(RouteEvent.Activate(graph, "Editor", false))
        coordinator.handle(RouteEvent.Tick(0L))

        val exactExit = requireNotNull(coordinator.handle(RouteEvent.Tick(20_000L)).frame)
        val nextTick = requireNotNull(coordinator.handle(RouteEvent.Tick(20_001L)).frame)

        assertEquals("Editor", exactExit.currentSurfaceId)
        assertEquals("Editor", nextTick.currentSurfaceId)
        assertTrue(exactExit.slices.any { slice -> slice.surfaceId == "Editor" })
        assertTrue(nextTick.slices.any { slice -> slice.surfaceId == "Editor" })
        assertTrue(exactExit.slices.none { slice -> slice.surfaceId == null })
        assertTrue(nextTick.slices.none { slice -> slice.surfaceId == null })
        listOf(exactExit, nextTick).forEach(::assertFiniteFrame)
    }

    @TestCase
    fun `zero gap preserves one visible signal without a connector leg`() {
        val coordinator = testCoordinator(DirectionRandom(TravelDirection.COUNTER_CLOCKWISE))
        val graph =
            testGraph(
                lengths = mapOf("Editor" to 100f, "Commit" to 200f),
                edges = listOf(TestEdge("Editor", "Commit", connectorLength = 0f)),
                signalSpans = mapOf("Editor" to 80f, "Commit" to 160f),
            )
        coordinator.handle(RouteEvent.Activate(graph, "Editor", false))
        coordinator.handle(RouteEvent.Tick(0L))
        coordinator.handle(RouteEvent.Keystroke(19_900L))

        val before = requireNotNull(coordinator.handle(RouteEvent.Tick(19_999L)).frame)
        val exactExit = requireNotNull(coordinator.handle(RouteEvent.Tick(20_000L)).frame)
        val nextTick = requireNotNull(coordinator.handle(RouteEvent.Tick(20_001L)).frame)

        assertEquals("Commit", exactExit.currentSurfaceId)
        assertEquals("Commit", nextTick.currentSurfaceId)
        assertEquals(TravelDirection.COUNTER_CLOCKWISE, before.signal.direction)
        assertEquals(100f, exactExit.centerDistance, 0.001f)
        assertEquals(0.005f, exactExit.centerDistance - before.centerDistance, 0.001f)
        assertEquals(0.01f, nextTick.centerDistance - exactExit.centerDistance, 0.001f)
        assertEquals(before.signal.morphology, exactExit.signal.morphology)
        assertEquals(before.signal.trace?.history, exactExit.signal.trace?.history)
        assertTrue(before.slices.any { slice -> slice.surfaceId == "Commit" })
        assertTrue(before.signal.energy > 0.9f)
        assertTrue(exactExit.signal.energy > 0.9f)
        assertTrue(nextTick.signal.energy > 0.9f)
        assertTrue(before.signal.brightness > 0.99f)
        assertTrue(exactExit.signal.brightness > 0.99f)
        assertTrue(nextTick.signal.brightness > 0.99f)
        val phaseBefore = requireNotNull(before.signal.trace).phase
        val phaseAfter = requireNotNull(exactExit.signal.trace).phase
        val directPhaseDelta = abs(phaseAfter - phaseBefore)
        val phaseDelta = minOf(directPhaseDelta, 1f - directPhaseDelta)
        assertTrue(phaseDelta < 0.05f, "phase changed from $phaseBefore to $phaseAfter")
        listOf(exactExit, nextTick).forEach { frame ->
            assertEquals(TravelDirection.CLOCKWISE, frame.signal.direction)
            assertEquals(1f, frame.alpha)
            assertTrue(frame.slices.none { slice -> slice.surfaceId == null })
            assertEquals(1, frame.slices.count { slice -> slice.surfaceId == "Editor" })
            assertTrue(frame.centerDistance.isFinite())
            assertTrue(frame.signalSpan.isFinite())
            assertTrue(
                frame.slices
                    .flatMap(RouteSlice::samples)
                    .all { sample ->
                        sample.x.isFinite() &&
                            sample.y.isFinite() &&
                            sample.normalX.isFinite() &&
                            sample.normalY.isFinite() &&
                            sample.distance.isFinite()
                    },
            )
        }
    }

    @TestCase
    fun `touching unequal spans blend smoothly across target geometry and rebind`() {
        val coordinator = testCoordinator(DirectionRandom(TravelDirection.COUNTER_CLOCKWISE))
        val graph =
            testGraph(
                lengths = mapOf("Editor" to 100f, "Commit" to 200f),
                edges = listOf(TestEdge("Editor", "Commit", connectorLength = 0f)),
                signalSpans = mapOf("Editor" to 80f, "Commit" to 160f),
            )
        val reboundGraph =
            testGraph(
                lengths = mapOf("Editor" to 100f, "Commit" to 200f),
                edges = listOf(TestEdge("Editor", "Commit", connectorLength = 0f)),
                signalSpans = mapOf("Editor" to 80f, "Commit" to 160f),
                yOffset = 12f,
            )
        coordinator.handle(RouteEvent.Activate(graph, "Editor", false))
        coordinator.handle(RouteEvent.Tick(0L))

        val exact = requireNotNull(coordinator.handle(RouteEvent.Tick(20_000L)).frame)
        val positiveTick = requireNotNull(coordinator.handle(RouteEvent.Tick(21_000L)).frame)
        val rebound =
            requireNotNull(
                coordinator
                    .handle(RouteEvent.GraphChanged(reboundGraph))
                    .frame,
            )
        val frames =
            buildList {
                add(exact)
                add(positiveTick)
                add(rebound)
                (22_000L..28_000L step 1_000L).forEach { nowMs ->
                    add(requireNotNull(coordinator.handle(RouteEvent.Tick(nowMs)).frame))
                }
            }
        val spans = frames.map(RouteFrame::signalSpan)

        assertEquals("Commit", exact.currentSurfaceId)
        assertEquals(80f, exact.signalSpan, 0.001f)
        assertTrue(positiveTick.signalSpan.isFinite())
        assertTrue(positiveTick.signalSpan in 80f..160f && positiveTick.signalSpan != 80f)
        assertEquals(positiveTick.signalSpan, rebound.signalSpan, 0.001f)
        assertEquals(160f, spans.last(), 0.001f)
        spans.zipWithNext().forEach { (before, after) ->
            assertTrue(after + 0.001f >= before, "span regressed from $before to $after")
            assertTrue(after - before <= 10.001f, "span jumped from $before to $after")
        }
        frames.forEach(::assertFiniteFrame)
    }

    @TestCase
    fun `target exit rebind preserves span progress`() {
        val coordinator = testCoordinator(DirectionRandom(TravelDirection.COUNTER_CLOCKWISE))
        val initial =
            testGraph(
                lengths = mapOf("Editor" to 100f, "Commit" to 200f),
                edges = listOf(TestEdge("Editor", "Commit", connectorLength = 0f)),
                signalSpans = mapOf("Editor" to 80f, "Commit" to 160f),
            )
        val movedExit =
            testGraph(
                lengths = mapOf("Editor" to 100f, "Commit" to 200f),
                edges =
                    listOf(
                        TestEdge(
                            sourceId = "Editor",
                            targetId = "Commit",
                            targetDistance = 80f,
                            connectorLength = 0f,
                        ),
                    ),
                signalSpans = mapOf("Editor" to 80f, "Commit" to 160f),
            )
        coordinator.handle(RouteEvent.Activate(initial, "Editor", false))
        coordinator.handle(RouteEvent.Tick(0L))
        val before = requireNotNull(coordinator.handle(RouteEvent.Tick(25_000L)).frame)

        val rebound = requireNotNull(coordinator.handle(RouteEvent.GraphChanged(movedExit)).frame)
        val next = requireNotNull(coordinator.handle(RouteEvent.Tick(25_001L)).frame)

        assertEquals(before.signalSpan, rebound.signalSpan, 0.001f)
        assertTrue(next.signalSpan >= rebound.signalSpan)
        assertTrue(next.signalSpan - rebound.signalSpan <= 0.01f)
        listOf(rebound, next).forEach(::assertFiniteFrame)
    }

    @TestCase
    fun `reverse spans contract smoothly on target`() {
        val coordinator = testCoordinator(DirectionRandom(TravelDirection.COUNTER_CLOCKWISE))
        val graph =
            testGraph(
                lengths = mapOf("Editor" to 200f, "Commit" to 100f),
                edges = listOf(TestEdge("Editor", "Commit", connectorLength = 0f)),
                signalSpans = mapOf("Editor" to 160f, "Commit" to 80f),
            )
        coordinator.handle(RouteEvent.Activate(graph, "Editor", false))
        coordinator.handle(RouteEvent.Tick(0L))
        val frames =
            (20_000L..40_000L step 1_000L).map { nowMs ->
                requireNotNull(coordinator.handle(RouteEvent.Tick(nowMs)).frame)
            }
        val spans = frames.map(RouteFrame::signalSpan)

        assertEquals(160f, spans.first(), 0.001f)
        assertEquals(80f, spans.last(), 0.001f)
        spans.zipWithNext().forEach { (before, after) ->
            assertTrue(after <= before + 0.001f, "span expanded from $before to $after")
            assertTrue(before - after <= 4.001f, "span contracted abruptly from $before to $after")
        }
        assertTrue(frames.first().slices.any { slice -> slice.surfaceId == "Editor" })
        assertTrue(frames.first().slices.any { slice -> slice.surfaceId == "Commit" })
        frames.forEach(::assertFiniteFrame)
    }

    @TestCase
    fun `lookahead persists successor after one target lap`() {
        val coordinator = testCoordinator(DirectionRandom(TravelDirection.COUNTER_CLOCKWISE))
        val graph =
            testGraph(
                lengths = mapOf("Editor" to 400f, "Commit" to 40f, "Problems" to 400f),
                edges =
                    listOf(
                        TestEdge("Editor", "Commit", connectorLength = 0f),
                        TestEdge("Commit", "Problems", connectorLength = 0f),
                    ),
                signalSpans = mapOf("Editor" to 360f, "Commit" to 40f, "Problems" to 360f),
            )
        coordinator.handle(RouteEvent.Activate(graph, "Editor", false))
        coordinator.handle(RouteEvent.Tick(0L))

        val frames =
            listOf(19_999L, 20_000L, 20_001L).map { nowMs ->
                requireNotNull(coordinator.handle(RouteEvent.Tick(nowMs)).frame)
            }

        frames.forEach { frame ->
            val target = frame.slices.single { slice -> slice.surfaceId == "Commit" }
            val coordinateCounts = target.samples.groupingBy { sample -> sample.x to sample.y }.eachCount()
            val sampleDistances = target.samples.map(WaveformSample::distance)
            val successor = frame.slices.single { slice -> slice.surfaceId == "Problems" }

            assertEquals(40f, target.samples.maxOf(WaveformSample::distance), 0.001f)
            assertTrue(
                coordinateCounts.values.all { count -> count <= 2 },
                "target lookahead wrapped over coordinates: $coordinateCounts",
            )
            assertEquals(target.samples.size, sampleDistances.distinct().size)
            assertTrue(successor.samples.maxOf(WaveformSample::distance) > 100f)
            assertEquals(1f, frame.alpha)
            assertEquals(frame.signal.config.brightnessAt(frame.signal.energy), frame.signal.brightness, 0.001f)
            assertFiniteFrame(frame)
        }
        assertEquals("Problems", coordinator.snapshot.plannedTargetId)
    }

    @TestCase
    fun `delayed tick consumes perimeter connector and destination time`() {
        val coordinator = testCoordinator(seededRandom(59))
        val graph =
            testGraph(
                lengths = mapOf("Editor" to 100f, "Commit" to 100f, "Problems" to 100f),
                edges =
                    listOf(
                        TestEdge("Editor", "Commit", connectorLength = 2f),
                        TestEdge("Commit", "Problems", connectorLength = 2f),
                    ),
            )
        coordinator.handle(RouteEvent.Activate(graph, "Editor", false))
        coordinator.handle(RouteEvent.Tick(0L))

        val frame = requireNotNull(coordinator.handle(RouteEvent.Tick(50_000L)).frame)

        assertEquals("Problems", frame.currentSurfaceId)
        assertTrue(frame.centerDistance > 200f, "center distance was ${frame.centerDistance}")
    }

    @TestCase
    fun `long wall clock gap keeps an isolated route active`() {
        val coordinator =
            RouteCoordinator(
                initialConfig =
                    WaveformConfig(
                        movement = WaveformMovement.CHAOTIC,
                        loopSeconds = 1.5f,
                    ),
                random = seededRandom(60),
            )
        val graph = testGraph(lengths = mapOf("Editor" to 100f), edges = emptyList())
        coordinator.handle(RouteEvent.Activate(graph, "Editor", false))
        coordinator.handle(RouteEvent.Tick(0L))

        val update =
            try {
                coordinator.handle(RouteEvent.Tick(20_000_000L))
            } catch (_: IllegalStateException) {
                null
            }

        assertNotNull(update, "A delayed UI tick must not terminate chaotic routing")
        val frame = assertNotNull(update.frame)
        assertTrue(frame.centerDistance < 20_000f, "Delayed tick performed unbounded catch-up: ${frame.centerDistance}")
        assertEquals(TimerDirective.KEEP, update.timerDirective)
    }

    @TestCase
    fun `route slices keep two pixel ordered samples and monotonic global distance`() {
        val coordinator = testCoordinator(seededRandom(61))
        val graph =
            testGraph(
                lengths = mapOf("Editor" to 100f, "Commit" to 100f),
                edges = listOf(TestEdge("Editor", "Commit", connectorLength = 10f)),
            )
        coordinator.handle(RouteEvent.Activate(graph, "Editor", false))
        coordinator.handle(RouteEvent.Tick(0L))

        val frame = requireNotNull(coordinator.handle(RouteEvent.Tick(23_000L)).frame)
        val routedDistances =
            frame.slices.flatMap { slice ->
                slice.samples.map { sample -> slice.distanceOffset + sample.distance }
            }

        assertTrue(frame.slices.size >= 2)
        assertTrue(routedDistances.zipWithNext().all { (first, second) -> second >= first })
        assertTrue(
            frame.slices
                .flatMap(RouteSlice::samples)
                .zipWithNext()
                .filter { (first, second) -> second.distance > first.distance }
                .all { (first, second) -> second.distance - first.distance <= 2.01f },
        )
    }

    @TestCase
    fun `visible surfaces include the previous island while its tail remains`() {
        val coordinator = testCoordinator(seededRandom(63))
        val graph =
            testGraph(
                lengths = mapOf("Editor" to 100f, "Commit" to 100f),
                edges = listOf(TestEdge("Editor", "Commit", connectorLength = 10f)),
                signalSpans = mapOf("Editor" to 100f, "Commit" to 100f),
            )
        coordinator.handle(RouteEvent.Activate(graph, "Editor", false))
        coordinator.handle(RouteEvent.Tick(0L))

        val frame = requireNotNull(coordinator.handle(RouteEvent.Tick(23_000L)).frame)

        assertEquals("Commit", frame.currentSurfaceId)
        assertTrue(frame.slices.any { slice -> slice.surfaceId == "Editor" })
    }

    @TestCase
    fun `counter clockwise perimeter samples still use positive route order`() {
        val coordinator = testCoordinator(seededRandom(67))
        val graph = testGraph(mapOf("Editor" to 400f), emptyList())
        coordinator.handle(RouteEvent.Activate(graph, "Editor", false))
        if (coordinator.snapshot.direction != TravelDirection.COUNTER_CLOCKWISE) {
            val alternate = testCoordinator(seededRandom(68))
            alternate.handle(RouteEvent.Activate(graph, "Editor", false))
            assertCounterClockwiseOrder(alternate)
        } else {
            assertCounterClockwiseOrder(coordinator)
        }
    }

    @TestCase
    fun `empty graph stops and later graph starts without losing signal state`() {
        val coordinator = testCoordinator(seededRandom(71))
        val graph = testGraph(mapOf("Editor" to 400f), emptyList())
        coordinator.handle(RouteEvent.Activate(graph, "Editor", false))
        coordinator.handle(RouteEvent.Tick(0L))
        coordinator.handle(RouteEvent.Keystroke(0L))
        val active = requireNotNull(coordinator.handle(RouteEvent.Tick(80L)).frame)

        val stopped = coordinator.handle(RouteEvent.GraphChanged(RouteGraph(emptyMap(), emptyMap())))
        val restarted = coordinator.handle(RouteEvent.GraphChanged(graph))
        val resumed = requireNotNull(coordinator.handle(RouteEvent.Tick(80L)).frame)

        assertEquals(TimerDirective.STOP, stopped.timerDirective)
        assertNull(stopped.frame)
        assertEquals(TimerDirective.START, restarted.timerDirective)
        assertEquals(active.signal.morphology, resumed.signal.morphology)
        assertEquals(active.signal.energy, resumed.signal.energy, 0.001f)
    }

    private fun assertCounterClockwiseOrder(coordinator: RouteCoordinator) {
        assertEquals(TravelDirection.COUNTER_CLOCKWISE, coordinator.snapshot.direction)
        coordinator.handle(RouteEvent.Tick(0L))
        val frame = requireNotNull(coordinator.handle(RouteEvent.Tick(2_000L)).frame)
        val samples = frame.slices.single().samples
        assertTrue(samples.zipWithNext().all { (first, second) -> second.distance >= first.distance })
    }

    private fun assertFiniteFrame(frame: RouteFrame) {
        val frameValues =
            listOf(
                frame.centerDistance,
                frame.signalSpan,
                frame.alpha,
                frame.signal.brightness,
                frame.signal.energy,
                frame.signal.trace?.anchorOffset ?: 0f,
                frame.signal.trace?.phase ?: 0f,
            )
        assertTrue(frameValues.all(Float::isFinite), "frame contained non-finite values: $frameValues")
        assertTrue(
            frame.signal.morphology
                .vertexPhases()
                .all(Float::isFinite),
        )
        assertTrue(
            frame.signal.trace
                ?.history
                .orEmpty()
                .flatMap(BeatMorphology::vertexPhases)
                .all(Float::isFinite),
        )
        assertTrue(
            frame.slices
                .flatMap(RouteSlice::samples)
                .flatMap { sample ->
                    listOf(
                        sample.x,
                        sample.y,
                        sample.normalX,
                        sample.normalY,
                        sample.distance,
                        sample.amplitudeMask,
                    )
                }.all(Float::isFinite),
        )
    }
}

private data class TestEdge(
    val sourceId: String,
    val targetId: String,
    val endpoint: RouteEndpoint = RouteEndpoint.START,
    val sourceDistance: Float = 0f,
    val targetDistance: Float = 0f,
    val connectorLength: Float = 8f,
    val requiresWindowBridge: Boolean = false,
)

private fun seededRandom(seed: Int): kotlin.random.Random = kotlin.random.Random(seed)

private fun testCoordinator(random: kotlin.random.Random): RouteCoordinator =
    RouteCoordinator(
        initialConfig =
            WaveformConfig(
                movement = WaveformMovement.CHAOTIC,
                loopSeconds = 20f,
            ),
        random = random,
    )

private fun testGraph(
    lengths: Map<String, Float>,
    edges: List<TestEdge>,
    signalSpans: Map<String, Float> = emptyMap(),
    yOffset: Float = 0f,
): RouteGraph {
    val surfaces =
        lengths.mapValues { (surfaceId, length) ->
            RouteSurface(
                id = surfaceId,
                rootId = RouteRootId(1),
                track = testTrack(length, signalSpans[surfaceId] ?: minOf(360f, length), yOffset),
                isEditor = surfaceId == "Editor",
                windowKind = RouteWindowKind.MAIN,
                inwardEdges = emptySet(),
            )
        }
    val directed =
        edges.flatMap { edge ->
            val connectorId =
                RouteConnectorId(
                    firstSurfaceId = minOf(edge.sourceId, edge.targetId),
                    secondSurfaceId = maxOf(edge.sourceId, edge.targetId),
                    firstSide = RouteSide.RIGHT,
                )
            val forward =
                RouteConnector(
                    id = connectorId,
                    endpoint = edge.endpoint,
                    sourceId = edge.sourceId,
                    targetId = edge.targetId,
                    sourceSide = RouteSide.RIGHT,
                    targetSide = RouteSide.LEFT,
                    sourceDistance = edge.sourceDistance,
                    targetDistance = edge.targetDistance,
                    sourcePoint = RoutePoint(0f, yOffset),
                    targetPoint = RoutePoint(edge.connectorLength, yOffset),
                    length = edge.connectorLength,
                    requiresWindowBridge = edge.requiresWindowBridge,
                )
            listOf(
                forward,
                forward.copy(
                    sourceId = edge.targetId,
                    targetId = edge.sourceId,
                    sourceSide = RouteSide.LEFT,
                    targetSide = RouteSide.RIGHT,
                    sourceDistance = edge.targetDistance,
                    targetDistance = edge.sourceDistance,
                    sourcePoint = forward.targetPoint,
                    targetPoint = forward.sourcePoint,
                ),
            )
        }
    return RouteGraph(
        surfaces = surfaces,
        connectors = directed.groupBy(RouteConnector::sourceId),
    )
}

private fun testTrack(
    length: Float,
    signalSpan: Float,
    yOffset: Float,
): WaveformTrack =
    WaveformTrack(
        samples =
            listOf(
                WaveformSample(0f, yOffset, 0f, -1f, 0f, 1f),
                WaveformSample(length / 2f, yOffset, 0f, 1f, length / 2f, 1f),
            ),
        length = length,
        signalAnchorDistance = 0f,
        signalSpan = signalSpan,
    )

private class DirectionRandom(
    private val direction: TravelDirection,
) : kotlin.random.Random() {
    override fun nextBits(bitCount: Int): Int =
        when {
            bitCount == 0 -> 0
            direction == TravelDirection.COUNTER_CLOCKWISE -> 0
            bitCount == Int.SIZE_BITS -> -1
            else -> (1 shl bitCount) - 1
        }
}

private fun cornerGraph(sourceAnchor: Float): RouteGraph {
    val editor =
        RouteSurface(
            id = "Editor",
            rootId = RouteRootId(1),
            track = squareTrack(originX = 0f, signalAnchorDistance = sourceAnchor),
            isEditor = true,
            windowKind = RouteWindowKind.MAIN,
            inwardEdges = emptySet(),
        )
    val commit =
        RouteSurface(
            id = "Commit",
            rootId = RouteRootId(1),
            track = squareTrack(originX = 100f, signalAnchorDistance = 0f),
            isEditor = false,
            windowKind = RouteWindowKind.MAIN,
            inwardEdges = emptySet(),
        )
    val connectorId = RouteConnectorId("Commit", "Editor", RouteSide.LEFT)
    val top =
        RouteConnector(
            id = connectorId,
            endpoint = RouteEndpoint.START,
            sourceId = editor.id,
            targetId = commit.id,
            sourceSide = RouteSide.RIGHT,
            targetSide = RouteSide.LEFT,
            sourceDistance = 100f,
            targetDistance = 0f,
            sourcePoint = RoutePoint(100f, 0f),
            targetPoint = RoutePoint(100f, 0f),
            length = 0f,
            requiresWindowBridge = false,
        )
    val bottom =
        top.copy(
            endpoint = RouteEndpoint.END,
            sourceDistance = 200f,
            targetDistance = 300f,
            sourcePoint = RoutePoint(100f, 100f),
            targetPoint = RoutePoint(100f, 100f),
        )
    return RouteGraph(
        surfaces = mapOf(editor.id to editor, commit.id to commit),
        connectors =
            mapOf(
                editor.id to listOf(top, bottom),
                commit.id to listOf(top.testReversed(), bottom.testReversed()),
            ),
    )
}

private fun RouteConnector.testReversed(): RouteConnector =
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

private fun RouteGraph.rotated(quarterTurns: Int): RouteGraph {
    val turns = quarterTurns.mod(RouteSide.entries.size)
    if (turns == 0) return this
    return RouteGraph(
        surfaces =
            surfaces.mapValues { (_, surface) ->
                surface.copy(track = surface.track.rotated(turns))
            },
        connectors =
            connectors.mapValues { (_, values) ->
                values.map { connector ->
                    connector.copy(
                        id =
                            connector.id.copy(
                                firstSide = connector.id.firstSide.rotated(turns),
                            ),
                        sourceSide = connector.sourceSide.rotated(turns),
                        targetSide = connector.targetSide.rotated(turns),
                        sourcePoint = connector.sourcePoint.rotated(turns),
                        targetPoint = connector.targetPoint.rotated(turns),
                    )
                }
            },
    )
}

private fun WaveformTrack.rotated(quarterTurns: Int): WaveformTrack =
    WaveformTrack(
        samples =
            samples.map { sample ->
                val point = RoutePoint(sample.x, sample.y).rotated(quarterTurns)
                val normal = RoutePoint(sample.normalX, sample.normalY).rotated(quarterTurns)
                sample.copy(
                    x = point.x,
                    y = point.y,
                    normalX = normal.x,
                    normalY = normal.y,
                )
            },
        length = length,
        signalAnchorDistance = signalAnchorDistance,
        signalSpan = signalSpan,
        isClosed = isClosed,
    )

private fun RoutePoint.rotated(quarterTurns: Int): RoutePoint =
    (0 until quarterTurns).fold(this) { point, _ ->
        RoutePoint(-point.y, point.x)
    }

private fun RouteSide.rotated(quarterTurns: Int): RouteSide =
    RouteSide.entries[(ordinal + quarterTurns).mod(RouteSide.entries.size)]

private fun squareTrack(
    originX: Float,
    signalAnchorDistance: Float,
): WaveformTrack =
    WaveformTrack(
        samples =
            listOf(
                WaveformSample(originX, 0f, 0f, -1f, 0f, 1f),
                WaveformSample(originX + 100f, 0f, 1f, 0f, 100f, 1f),
                WaveformSample(originX + 100f, 100f, 0f, 1f, 200f, 1f),
                WaveformSample(originX, 100f, -1f, 0f, 300f, 1f),
            ),
        length = 400f,
        signalAnchorDistance = signalAnchorDistance,
        signalSpan = 240f,
    )

private fun lengthPriorityGraph(): RouteGraph {
    val connectorId = RouteConnectorId("Commit", "Editor", RouteSide.LEFT)
    val shorter =
        RouteConnector(
            id = connectorId,
            endpoint = RouteEndpoint.START,
            sourceId = "Editor",
            targetId = "Commit",
            sourceSide = RouteSide.RIGHT,
            targetSide = RouteSide.LEFT,
            sourceDistance = 100f,
            targetDistance = 0f,
            sourcePoint = RoutePoint(100f, 0f),
            targetPoint = RoutePoint(100f, 50f),
            length = 50f,
            requiresWindowBridge = false,
        )
    val longer =
        shorter.copy(
            endpoint = RouteEndpoint.END,
            sourceDistance = 120f,
            targetDistance = 70f,
            sourcePoint = RoutePoint(100f, 20f),
            targetPoint = RoutePoint(100f, 120f),
            length = 100f,
        )
    return priorityGraph(lengthPriorityTrack(), listOf(shorter, longer))
}

private fun turnPriorityGraph(): RouteGraph {
    val connectorId = RouteConnectorId("Commit", "Editor", RouteSide.LEFT)
    val aligned =
        RouteConnector(
            id = connectorId,
            endpoint = RouteEndpoint.START,
            sourceId = "Editor",
            targetId = "Commit",
            sourceSide = RouteSide.RIGHT,
            targetSide = RouteSide.LEFT,
            sourceDistance = 100f,
            targetDistance = 0f,
            sourcePoint = RoutePoint(100f, 0f),
            targetPoint = RoutePoint(200f, 0f),
            length = 100f,
            requiresWindowBridge = false,
        )
    val misaligned =
        aligned.copy(
            endpoint = RouteEndpoint.END,
            sourceDistance = 120f,
            targetDistance = 120f,
            sourcePoint = RoutePoint(100f, 20f),
            targetPoint = RoutePoint(200f, 20f),
        )
    return priorityGraph(turnPriorityTrack(), listOf(aligned, misaligned))
}

private fun exitPriorityGraph(): RouteGraph {
    val editor =
        RouteSurface(
            id = "Editor",
            rootId = RouteRootId(1),
            track = squareTrack(originX = 0f, signalAnchorDistance = 130f),
            isEditor = true,
            windowKind = RouteWindowKind.MAIN,
            inwardEdges = emptySet(),
        )
    val commit =
        RouteSurface(
            id = "Commit",
            rootId = RouteRootId(1),
            track = squareTrack(originX = 200f, signalAnchorDistance = 0f),
            isEditor = false,
            windowKind = RouteWindowKind.MAIN,
            inwardEdges = emptySet(),
        )
    val connectorId = RouteConnectorId("Commit", "Editor", RouteSide.LEFT)
    val stableFirst =
        RouteConnector(
            id = connectorId,
            endpoint = RouteEndpoint.START,
            sourceId = editor.id,
            targetId = commit.id,
            sourceSide = RouteSide.RIGHT,
            targetSide = RouteSide.LEFT,
            sourceDistance = 120f,
            targetDistance = 380f,
            sourcePoint = RoutePoint(100f, 20f),
            targetPoint = RoutePoint(200f, 20f),
            length = 100f,
            requiresWindowBridge = false,
        )
    val earlierExit =
        stableFirst.copy(
            endpoint = RouteEndpoint.END,
            sourceDistance = 180f,
            targetDistance = 320f,
            sourcePoint = RoutePoint(100f, 80f),
            targetPoint = RoutePoint(200f, 80f),
        )
    val connectors = listOf(stableFirst, earlierExit)
    return RouteGraph(
        surfaces = mapOf(editor.id to editor, commit.id to commit),
        connectors =
            mapOf(
                editor.id to connectors,
                commit.id to connectors.map(RouteConnector::testReversed),
            ),
    )
}

private fun priorityGraph(
    targetTrack: WaveformTrack,
    connectors: List<RouteConnector>,
): RouteGraph {
    val editor =
        RouteSurface(
            id = "Editor",
            rootId = RouteRootId(1),
            track = prioritySourceTrack(),
            isEditor = true,
            windowKind = RouteWindowKind.MAIN,
            inwardEdges = emptySet(),
        )
    val commit =
        RouteSurface(
            id = "Commit",
            rootId = RouteRootId(1),
            track = targetTrack,
            isEditor = false,
            windowKind = RouteWindowKind.MAIN,
            inwardEdges = emptySet(),
        )
    return RouteGraph(
        surfaces = mapOf(editor.id to editor, commit.id to commit),
        connectors =
            mapOf(
                editor.id to connectors,
                commit.id to connectors.map(RouteConnector::testReversed),
            ),
    )
}

private fun prioritySourceTrack(): WaveformTrack =
    WaveformTrack(
        samples =
            listOf(
                WaveformSample(0f, 0f, 0f, -1f, 0f, 1f),
                WaveformSample(100f, 0f, 1f, 0f, 100f, 1f),
                WaveformSample(100f, 20f, 1f, 0f, 120f, 1f),
                WaveformSample(100f, 100f, 0f, 1f, 200f, 1f),
                WaveformSample(0f, 100f, -1f, 0f, 300f, 1f),
            ),
        length = 400f,
        signalAnchorDistance = 110f,
        signalSpan = 240f,
    )

private fun lengthPriorityTrack(): WaveformTrack =
    WaveformTrack(
        samples =
            listOf(
                WaveformSample(100f, 50f, -1f, 0f, 0f, 1f),
                WaveformSample(100f, 120f, -1f, 0f, 70f, 1f),
                WaveformSample(100f, 150f, -1f, 0f, 100f, 1f),
                WaveformSample(0f, 150f, 0f, 1f, 200f, 1f),
                WaveformSample(0f, 50f, 1f, 0f, 300f, 1f),
            ),
        length = 400f,
        signalAnchorDistance = 0f,
        signalSpan = 240f,
    )

private fun turnPriorityTrack(): WaveformTrack =
    WaveformTrack(
        samples =
            listOf(
                WaveformSample(200f, 0f, 0f, -1f, 0f, 1f),
                WaveformSample(250f, 0f, 1f, 0f, 50f, 1f),
                WaveformSample(250f, 20f, 1f, 0f, 70f, 1f),
                WaveformSample(200f, 20f, 0f, 1f, 120f, 1f),
                WaveformSample(200f, 50f, -1f, 0f, 150f, 1f),
                WaveformSample(100f, 50f, 0f, 1f, 250f, 1f),
                WaveformSample(100f, 0f, 1f, 0f, 300f, 1f),
            ),
        length = 400f,
        signalAnchorDistance = 0f,
        signalSpan = 240f,
    )

private class RouteDriver(
    private val coordinator: RouteCoordinator,
) {
    private var nowMs = 0L

    fun advanceUntilSurface(targetId: String): RouteFrame = advanceTimed(targetId).frame

    fun advanceTimed(targetId: String): TimedFrame {
        repeat(2_000) {
            nowMs += 100L
            coordinator.handle(RouteEvent.Tick(nowMs)).frame?.let { frame ->
                if (frame.currentSurfaceId == targetId) return TimedFrame(nowMs, frame)
            }
        }
        error("Route did not reach $targetId")
    }
}

private data class TimedFrame(
    val nowMs: Long,
    val frame: RouteFrame,
)
