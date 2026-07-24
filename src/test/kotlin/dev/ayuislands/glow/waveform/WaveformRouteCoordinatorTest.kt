package dev.ayuislands.glow.waveform

import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test as TestCase

class WaveformRouteCoordinatorTest {
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
    fun `missing target replans only at a completed lap boundary`() {
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
        coordinator.handle(RouteEvent.Tick(0L))
        coordinator.handle(RouteEvent.GraphChanged(replacement))

        coordinator.handle(RouteEvent.Tick(19_000L))
        assertEquals("Commit", coordinator.snapshot.plannedTargetId)

        coordinator.handle(RouteEvent.Tick(20_000L))
        assertEquals("Problems", coordinator.snapshot.plannedTargetId)
    }

    @TestCase
    fun `missing current surface fades then selects editor fallback`() {
        val coordinator = testCoordinator(seededRandom(19))
        val initial = testGraph(mapOf("Commit" to 400f), emptyList())
        val replacement = testGraph(mapOf("Editor" to 400f, "Problems" to 400f), emptyList())
        coordinator.handle(RouteEvent.Activate(initial, "Commit", false))
        coordinator.handle(RouteEvent.Tick(0L))
        coordinator.handle(RouteEvent.GraphChanged(replacement))

        val fadeStart = requireNotNull(coordinator.handle(RouteEvent.Tick(20_000L)).frame)
        val fadeMiddle = requireNotNull(coordinator.handle(RouteEvent.Tick(20_080L)).frame)
        val recovered = requireNotNull(coordinator.handle(RouteEvent.Tick(20_160L)).frame)

        assertEquals("Commit", fadeStart.currentSurfaceId)
        assertEquals(1f, fadeStart.alpha, 0.001f)
        assertEquals(0.5f, fadeMiddle.alpha, 0.01f)
        assertEquals("Editor", recovered.currentSurfaceId)
        assertEquals(1f, recovered.alpha, 0.001f)
    }

    @TestCase
    fun `changed topology is staged until the full lap boundary`() {
        val coordinator = testCoordinator(seededRandom(23))
        val isolated = testGraph(mapOf("Editor" to 400f), emptyList())
        val connected =
            testGraph(
                lengths = mapOf("Editor" to 400f, "Commit" to 400f),
                edges = listOf(TestEdge("Editor", "Commit")),
            )
        coordinator.handle(RouteEvent.Activate(isolated, "Editor", false))
        coordinator.handle(RouteEvent.Tick(0L))
        coordinator.handle(RouteEvent.GraphChanged(connected))

        coordinator.handle(RouteEvent.Tick(19_000L))
        assertNull(coordinator.snapshot.plannedTargetId)

        coordinator.handle(RouteEvent.Tick(20_000L))
        assertEquals("Commit", coordinator.snapshot.plannedTargetId)
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
        val connectorStart = requireNotNull(coordinator.handle(RouteEvent.Tick(20_000L)).frame)
        val connectorMiddle = requireNotNull(coordinator.handle(RouteEvent.Tick(21_932L)).frame)

        assertEquals(setOf("Editor", "Commit"), connectorStart.visibleSurfaceIds)
        assertTrue(connectorMiddle.signalSpan in 185f..215f, "span was ${connectorMiddle.signalSpan}")
    }

    @TestCase
    fun `zero gap crosses unequal perimeters with finite data`() {
        val coordinator = testCoordinator(seededRandom(97))
        val graph =
            testGraph(
                lengths = mapOf("Editor" to 100f, "Commit" to 200f),
                edges = listOf(TestEdge("Editor", "Commit", connectorLength = 0f)),
                signalSpans = mapOf("Editor" to 80f, "Commit" to 160f),
            )
        coordinator.handle(RouteEvent.Activate(graph, "Editor", false))
        coordinator.handle(RouteEvent.Tick(0L))

        val exactExit = requireNotNull(coordinator.handle(RouteEvent.Tick(20_000L)).frame)
        val nextTick = requireNotNull(coordinator.handle(RouteEvent.Tick(20_001L)).frame)

        assertEquals("Commit", exactExit.currentSurfaceId)
        assertEquals("Commit", nextTick.currentSurfaceId)
        listOf(exactExit, nextTick).forEach { frame ->
            assertTrue(frame.centerDistance.isFinite())
            assertTrue(frame.signalSpan.isFinite())
            assertTrue(
                frame.signal.trace
                    ?.anchorOffset
                    ?.isFinite() != false,
            )
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
        assertEquals(setOf("Editor", "Commit"), frame.visibleSurfaceIds)
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

    private fun assertCounterClockwiseOrder(coordinator: WaveformRouteCoordinator) {
        assertEquals(TravelDirection.COUNTER_CLOCKWISE, coordinator.snapshot.direction)
        coordinator.handle(RouteEvent.Tick(0L))
        val frame = requireNotNull(coordinator.handle(RouteEvent.Tick(2_000L)).frame)
        val samples = frame.slices.single().samples
        assertTrue(samples.zipWithNext().all { (first, second) -> second.distance >= first.distance })
    }
}

private data class TestEdge(
    val sourceId: String,
    val targetId: String,
    val sourceDistance: Float = 0f,
    val targetDistance: Float = 0f,
    val connectorLength: Float = 8f,
    val requiresWindowBridge: Boolean = false,
)

private fun seededRandom(seed: Int): kotlin.random.Random = kotlin.random.Random(seed)

private fun testCoordinator(random: kotlin.random.Random): WaveformRouteCoordinator =
    WaveformRouteCoordinator(
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

private class RouteDriver(
    private val coordinator: WaveformRouteCoordinator,
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
