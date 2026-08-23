package dev.ayuislands.glow.waveform

import dev.ayuislands.glow.GlowStyle
import java.awt.Color
import java.awt.Rectangle
import java.awt.image.BufferedImage
import javax.swing.JComponent
import javax.swing.RepaintManager
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class RouteLayerPixelTest {
    @Test
    fun `source connector and destination paint one continuous hot trace`() {
        val layer = WaveformRouteLayer(RouteRootId(1)) { throw it }
        layer.setBounds(0, 0, CANVAS_WIDTH, CANVAS_HEIGHT)
        layer.updateStyle(testStyle())
        val frame = transitionFrame()
        layer.showFrame(frame, frame.slices, fullSurfaceBounds(frame))

        val image = render(layer)

        assertTrue(hasPaintedPixel(image, Rectangle(292, 138, 12, 24)))
        assertTrue(hasPaintedPixel(image, Rectangle(314, 138, 12, 24)))
        assertTrue(hasPaintedPixel(image, Rectangle(336, 138, 12, 24)))
        assertFalse(
            hasEmptyColumnRun(
                image = image,
                yRange = 138..162,
            ),
        )
    }

    @Test
    fun `perimeter traces stay inside their islands while connector remains visible`() {
        val layer = WaveformRouteLayer(RouteRootId(1)) { throw it }
        layer.setBounds(0, 0, CANVAS_WIDTH, CANVAS_HEIGHT)
        layer.updateStyle(testStyle())
        val frame = transitionFrame()
        val editorBounds = Rectangle(280, 144, 18, 13)
        val commitBounds = Rectangle(342, 144, 18, 13)

        layer.showFrame(
            frame = frame,
            slices = frame.slices,
            surfaceBounds = mapOf("Editor" to editorBounds, "Commit" to commitBounds),
        )

        val image = render(layer)

        assertTrue(hasPaintedPixel(image, editorBounds), "source perimeter must remain visible")
        assertFalse(hasPaintedPixel(image, Rectangle(280, 130, 18, 14)))
        assertFalse(hasPaintedPixel(image, Rectangle(280, 157, 18, 14)))
        assertTrue(hasPaintedPixel(image, Rectangle(314, 138, 12, 24)), "connector must remain visible")
    }

    @Test
    fun `recovery omits a missing current island while preserving its route`() {
        val layer = WaveformRouteLayer(RouteRootId(1)) { throw it }
        layer.setBounds(0, 0, CANVAS_WIDTH, CANVAS_HEIGHT)
        layer.updateStyle(testStyle())
        val frame = transitionFrame()
        val editorBounds = Rectangle(280, 144, 18, 13)

        layer.showFrame(
            frame = frame,
            slices = frame.slices,
            surfaceBounds = mapOf("Editor" to editorBounds),
        )

        val image = render(layer)
        val missingPerimeterRegion = Rectangle(354, 138, 6, 24)

        assertTrue(
            hasPaintedPixel(renderFrame(frame), missingPerimeterRegion),
            "fixture must include the current perimeter",
        )
        assertTrue(hasPaintedPixel(image, editorBounds), "surviving perimeter must remain visible")
        assertTrue(hasPaintedPixel(image, Rectangle(314, 138, 12, 24)), "connector must remain visible")
        assertFalse(hasPaintedPixel(image, missingPerimeterRegion), "missing perimeter must not leave a ghost")
    }

    @Test
    fun `touching perimeter handoff keeps brightness and bounded motion`() {
        val frames = touchingCoordinatorFrames()
        val images = frames.map(::renderFrame)
        val sourceRegion = Rectangle(276, 136, 44, 28)
        val targetRegion = Rectangle(320, 136, 44, 28)

        assertTrue(frames.first().slices.any { slice -> slice.surfaceId == "Commit" })
        assertTrue(frames[1].slices.any { slice -> slice.surfaceId == "Editor" })
        assertTrue(frames[1].slices.any { slice -> slice.surfaceId == "Commit" })
        assertTrue(frames.last().slices.any { slice -> slice.surfaceId == "Editor" })
        assertTrue(images.all { image -> assertNotNull(paintedBounds(image)).width >= 24 })
        assertTrue(alphaMass(images[1], sourceRegion) > 0L, "middle frame must retain the source tail")
        assertTrue(alphaMass(images[1], targetRegion) > 0L, "middle frame must include the target head")
        val alphaMasses = images.map { image -> alphaMass(image) }
        val maximumMass = requireNotNull(alphaMasses.maxOrNull())
        val minimumMass = requireNotNull(alphaMasses.minOrNull())
        assertTrue(
            maximumMass - minimumMass <= maximumMass / BRIGHTNESS_TOLERANCE_DIVISOR,
            "painted alpha mass changed across handoff: $alphaMasses",
        )
        val centers = images.map(::alphaCentroid)
        val routeSpeed =
            TOUCHING_TRACK_LENGTH /
                (testStyle().config.loopSeconds * MILLIS_PER_SECOND)
        val movementBudget = HANDOFF_TICK_MS.toFloat() * routeSpeed + testStyle().width
        centers.zipWithNext().forEach { (first, second) ->
            val movement =
                hypot(
                    (second.x - first.x).toDouble(),
                    (second.y - first.y).toDouble(),
                )
            assertTrue(movement <= movementBudget, "visual center moved $movement pixels")
        }
        images.forEach { image ->
            assertTrue(
                hasPaintedPixel(image, Rectangle(314, 138, 12, 24)),
                "shared endpoint must stay painted",
            )
        }
        frames.forEach { frame ->
            assertEquals(1f, frame.alpha)
            assertTrue(frame.signal.energy > 0.9f)
            assertTrue(frame.signal.brightness > 0.98f)
            assertEquals(
                frame.signal.config.brightnessAt(frame.signal.energy),
                frame.signal.brightness,
                0.001f,
            )
        }
    }

    @Test
    fun `unequal touching spans keep one bounded visual footprint`() {
        val frames = coordinatorFrames(unequalGraph())
        val images = frames.map(::renderFrame)
        val sourceRegion = Rectangle(276, 136, 44, 28)
        val targetRegion = Rectangle(320, 136, 84, 28)
        val style = testStyle()
        val routeSpeed = 320f / (style.config.loopSeconds * MILLIS_PER_SECOND)
        val travelBudget = HANDOFF_TICK_MS.toFloat() * routeSpeed
        val visualBudget = travelBudget + style.width

        assertEquals(80f, frames.first().signalSpan, 0.001f)
        assertEquals(80f, frames[1].signalSpan, 0.001f)
        assertTrue(frames.last().signalSpan in 80f..160f && frames.last().signalSpan != 80f)
        assertTrue(alphaMass(images[1], sourceRegion) > 0L, "boundary frame must retain the source tail")
        assertTrue(alphaMass(images[1], targetRegion) > 0L, "boundary frame must include the target head")
        val alphaMasses = images.map { image -> alphaMass(image) }
        val maximumMass = requireNotNull(alphaMasses.maxOrNull())
        val minimumMass = requireNotNull(alphaMasses.minOrNull())
        val massBudget =
            (
                maximumMass *
                    visualBudget /
                    frames.first().signalSpan
            ).roundToLong()
        assertTrue(
            maximumMass - minimumMass <= massBudget,
            "unequal-span alpha mass exceeded $massBudget: $alphaMasses",
        )
        val extents = images.map { image -> assertNotNull(paintedBounds(image)) }
        val widths = extents.map { extent -> extent.width }
        assertTrue(
            requireNotNull(widths.maxOrNull()) - requireNotNull(widths.minOrNull()) <= visualBudget.roundToInt(),
            "painted extent exceeded $visualBudget: $widths",
        )
        images
            .map(::alphaCentroid)
            .zipWithNext()
            .forEach { (first, second) ->
                val movement =
                    hypot(
                        (second.x - first.x).toDouble(),
                        (second.y - first.y).toDouble(),
                    )
                assertTrue(movement <= visualBudget, "unequal-span centroid moved $movement pixels")
            }
        frames.forEach { frame ->
            assertEquals(1f, frame.alpha)
            assertEquals(frame.signal.config.brightnessAt(frame.signal.energy), frame.signal.brightness, 0.001f)
        }
    }

    @Test
    fun `geometry rebind moves retained tail without ghosts`() {
        val (before, rebound, next) =
            reboundFrames(
                initial = unequalGraph(),
                rebound = unequalGraph(yOffset = 40f),
                rebindAtMs = TOUCHING_BOUNDARY_MS + 1_000L,
            )
        val images = listOf(before, rebound, next).map(::renderFrame)
        val oldRegion = Rectangle(270, 132, 140, 40)
        val movedSourceRegion = Rectangle(270, 176, 56, 54)

        assertTrue(alphaMass(images.first(), oldRegion) > 0L)
        assertEquals(0L, alphaMass(images[1], oldRegion))
        assertTrue(rebound.slices.any { slice -> slice.surfaceId == "Editor" })
        assertTrue(next.slices.any { slice -> slice.surfaceId == "Editor" })
        assertTrue(alphaMass(images[1], movedSourceRegion) > 0L)
        assertTrue(hasPaintedPixel(images[1], Rectangle(314, 178, 12, 24)), "moved join must stay painted")
        val massBudget = alphaMass(images.first()) / 4
        assertTrue(
            kotlin.math.abs(alphaMass(images[1]) - alphaMass(images.first())) <= massBudget,
            "geometry rebind changed alpha mass beyond $massBudget",
        )
        val centroids = images.map(::alphaCentroid)
        val move =
            hypot(
                (centroids[1].x - centroids[0].x).toDouble(),
                (centroids[1].y - centroids[0].y).toDouble(),
            )
        assertTrue(move <= 40f + testStyle().width, "geometry rebind centroid moved $move pixels")
        val nextMove =
            hypot(
                (centroids[2].x - centroids[1].x).toDouble(),
                (centroids[2].y - centroids[1].y).toDouble(),
            )
        assertTrue(nextMove <= testStyle().width, "post-rebind centroid moved $nextMove pixels")
        val extents = images.map { image -> assertNotNull(paintedBounds(image)) }
        assertTrue(kotlin.math.abs(extents[1].width - extents[0].width) <= testStyle().width)
        assertTrue(kotlin.math.abs(extents[1].height - extents[0].height) <= testStyle().width)
    }

    @Test
    fun `target exit rebind keeps span and extent stable`() {
        val (before, rebound, next) =
            reboundFrames(
                initial = unequalGraph(),
                rebound = unequalGraph(targetExitDistance = 80f),
                rebindAtMs = TOUCHING_BOUNDARY_MS + 5_000L,
            )
        val frames = listOf(before, rebound, next)
        val images = frames.map(::renderFrame)
        val extents = images.map { image -> assertNotNull(paintedBounds(image)) }

        assertEquals(before.signalSpan, rebound.signalSpan, 0.001f)
        assertTrue(next.signalSpan >= rebound.signalSpan)
        assertTrue(kotlin.math.abs(extents[1].width - extents[0].width) <= testStyle().width)
        val reboundMove =
            hypot(
                (alphaCentroid(images[1]).x - alphaCentroid(images[0]).x).toDouble(),
                (alphaCentroid(images[1]).y - alphaCentroid(images[0]).y).toDouble(),
            )
        assertTrue(reboundMove <= testStyle().width, "exit rebind centroid moved $reboundMove pixels")
        frames.forEach { frame ->
            assertEquals(frame.signal.config.brightnessAt(frame.signal.energy), frame.signal.brightness, 0.001f)
        }
    }

    @Test
    fun `short target hands head to persisted successor`() {
        val frames = coordinatorFrames(successorPixelGraph(), SUCCESSOR_BOUNDARY_MS)
        val images = frames.map(::renderFrame)
        val targetRegion = Rectangle(196, 136, 20, 28)
        val successorRegion = Rectangle(210, 136, 104, 28)

        frames.forEach { frame ->
            assertTrue(frame.slices.any { slice -> slice.surfaceId == "Commit" })
            assertTrue(frame.slices.any { slice -> slice.surfaceId == "Problems" })
            assertEquals(1f, frame.alpha)
            assertEquals(frame.signal.config.brightnessAt(frame.signal.energy), frame.signal.brightness, 0.001f)
        }
        images.forEach { image ->
            assertTrue(alphaMass(image, targetRegion) > 0L)
            assertTrue(alphaMass(image, successorRegion) > 0L)
        }
        assertBoundedPixels(frames, images)
    }

    @Test
    fun `reverse spans contract with bounded pixels`() {
        val frames = coordinatorFrames(reverseUnequalGraph(), REVERSE_BOUNDARY_MS)
        val images = frames.map(::renderFrame)
        val sourceRegion = Rectangle(264, 136, 60, 28)
        val targetRegion = Rectangle(320, 136, 54, 28)

        assertEquals(160f, frames.first().signalSpan, 0.001f)
        assertEquals(160f, frames[1].signalSpan, 0.001f)
        assertTrue(frames.last().signalSpan < frames[1].signalSpan)
        assertTrue(alphaMass(images[1], sourceRegion) > 0L)
        assertTrue(alphaMass(images[1], targetRegion) > 0L)
        assertBoundedPixels(frames, images)
    }

    @Test
    fun `route layer never paints the solid base frame`() {
        val layer = WaveformRouteLayer(RouteRootId(1)) { throw it }
        layer.setBounds(0, 0, CANVAS_WIDTH, CANVAS_HEIGHT)
        layer.updateStyle(testStyle())
        val frame = transitionFrame()
        layer.showFrame(frame, frame.slices, fullSurfaceBounds(frame))

        val image = render(layer)

        assertEquals(0, image.getRGB(20, 20).ushr(24))
    }

    @Test
    fun `counter clockwise slices retain coordinator sample order`() {
        val clockwise = transitionFrame()
        val counterClockwise =
            clockwise.copy(
                signal = clockwise.signal.copy(direction = TravelDirection.COUNTER_CLOCKWISE),
            )

        val clockwiseImage = renderFrame(clockwise)
        val counterClockwiseImage = renderFrame(counterClockwise)

        assertEquals(0, pixelDifference(clockwiseImage, counterClockwiseImage))
    }

    @Test
    fun `tail remains on source while hot head reaches destination`() {
        val frame = transitionFrame().copy(centerDistance = 190f)
        val headDistance =
            frame.centerDistance +
                (WaveformPainter.HEAD_PHASE - WaveformPainter.R_PEAK_PHASE) * frame.signalSpan
        val layer = routeLayer(frame)

        val image = render(layer)

        assertTrue(headDistance.roundToInt() in 342..360)
        assertTrue(hasPaintedPixel(image, Rectangle(280, 138, 18, 24)), "source tail must remain visible")
        assertTrue(hasPaintedPixel(image, Rectangle(342, 138, 18, 24)), "destination head must remain visible")
    }

    @Test
    fun `global phase follows each slice distance offset`() {
        val continuous = transitionFrame().copy(centerDistance = 270f)
        val resetOffsets =
            continuous.copy(
                slices = continuous.slices.map { slice -> slice.copy(distanceOffset = 0f) },
            )

        val continuousImage = renderFrame(continuous)
        val resetImage = renderFrame(resetOffsets)

        assertTrue(hasPaintedPixel(continuousImage, Rectangle(342, 138, 18, 24)))
        assertTrue(
            pixelDifference(continuousImage, resetImage) > 0,
            "resetting slice offsets must move the global waveform phase",
        )
    }

    @Test
    fun `frame changes repaint the union of old and new signal bounds`() {
        val layer = WaveformRouteLayer(RouteRootId(1)) { throw it }
        layer.setBounds(0, 0, CANVAS_WIDTH, CANVAS_HEIGHT)
        layer.updateStyle(testStyle())
        val firstFrame = transitionFrame().copy(centerDistance = 270f)
        val secondFrame = transitionFrame().copy(centerDistance = 320f)
        val previousManager = RepaintManager.currentManager(layer)
        val recordingManager = RecordingRepaintManager()
        RepaintManager.setCurrentManager(recordingManager)
        try {
            layer.showFrame(firstFrame, firstFrame.slices, fullSurfaceBounds(firstFrame))
            val firstBounds = assertNotNull(paintedBounds(render(layer)))
            recordingManager.clear()

            layer.showFrame(secondFrame, secondFrame.slices, fullSurfaceBounds(secondFrame))
            val secondBounds = assertNotNull(paintedBounds(render(layer)))
            val dirtyBounds = assertNotNull(recordingManager.dirtyBounds())

            assertTrue(
                dirtyBounds.contains(firstBounds.union(secondBounds)),
                "dirty bounds $dirtyBounds must contain both rendered extents",
            )
        } finally {
            RepaintManager.setCurrentManager(previousManager)
        }
    }

    @Test
    fun `route layer stays transparent to hit testing`() {
        val frame = transitionFrame()
        val layer = routeLayer(frame)

        assertFalse(layer.contains(20, 20))
        assertFalse(layer.contains(320, 150))
    }

    @Test
    fun `route frame alpha fades every slice`() {
        val visible = renderFrame(transitionFrame())
        val hidden = renderFrame(transitionFrame().copy(alpha = 0f))

        assertTrue(assertNotNull(paintedBounds(visible)).width > 0)
        assertEquals(null, paintedBounds(hidden))
    }

    private fun routeLayer(frame: RouteFrame): WaveformRouteLayer =
        WaveformRouteLayer(RouteRootId(1)) { throw it }.also { layer ->
            layer.setBounds(0, 0, CANVAS_WIDTH, CANVAS_HEIGHT)
            layer.updateStyle(testStyle())
            layer.showFrame(frame, frame.slices, fullSurfaceBounds(frame))
        }

    private fun renderFrame(frame: RouteFrame): BufferedImage = render(routeLayer(frame))

    private class RecordingRepaintManager : RepaintManager() {
        private val regions = mutableListOf<Rectangle>()

        override fun addDirtyRegion(
            component: JComponent,
            x: Int,
            y: Int,
            width: Int,
            height: Int,
        ) {
            if (width > 0 && height > 0) regions += Rectangle(x, y, width, height)
        }

        fun clear() {
            regions.clear()
        }

        fun dirtyBounds(): Rectangle? = regions.reduceOrNull(Rectangle::union)
    }
}

private const val TOUCHING_TRACK_LENGTH = 160f
private const val TOUCHING_BOUNDARY_MS = 39_500L
private const val HANDOFF_TICK_MS = 20L
private const val MILLIS_PER_SECOND = 1_000f
private const val BRIGHTNESS_TOLERANCE_DIVISOR = 8
private const val SUCCESSOR_BOUNDARY_MS = 39_800L
private const val REVERSE_BOUNDARY_MS = 39_600L
private const val PIXEL_CHANGE_LIMIT = 12f
private const val CANVAS_WIDTH = 640
private const val CANVAS_HEIGHT = 320
private const val BRIDGE_SCAN_START_X = 298
private const val BRIDGE_SCAN_END_X = 342
private const val MINIMUM_EMPTY_COLUMN_RUN = 2

private fun testStyle(): RouteLayerStyle =
    RouteLayerStyle(
        accent = Color(255, 204, 102),
        glowStyle = GlowStyle.SHARP_NEON,
        intensity = 100,
        width = 4,
        arcWidth = 16,
        config =
            WaveformConfig(
                movement = WaveformMovement.CHAOTIC,
                amplitude = 8,
                intensity = 100,
                traceLength = 360,
            ),
    )

private fun fullSurfaceBounds(frame: RouteFrame): Map<String, Rectangle> =
    frame.slices
        .mapNotNull(RouteSlice::surfaceId)
        .distinct()
        .associateWith { Rectangle(0, 0, CANVAS_WIDTH, CANVAS_HEIGHT) }

private fun transitionFrame(): RouteFrame {
    val target = RoutePaintTarget.Root(RouteRootId(1))
    val source =
        RouteSlice(
            target = target,
            surfaceId = "Editor",
            samples =
                listOf(
                    WaveformSample(280f, 150f, 0f, -1f, 0f, 1f),
                    WaveformSample(298f, 150f, 0f, -1f, 18f, 1f),
                ),
            distanceOffset = 280f,
            inwardEdges = emptySet(),
        )
    val connector =
        RouteSlice(
            target = target,
            surfaceId = null,
            samples =
                listOf(
                    WaveformSample(298f, 150f, 0f, -1f, 0f, 1f),
                    WaveformSample(342f, 150f, 0f, -1f, 44f, 1f),
                ),
            distanceOffset = 298f,
            inwardEdges = emptySet(),
        )
    val destination =
        RouteSlice(
            target = target,
            surfaceId = "Commit",
            samples =
                listOf(
                    WaveformSample(342f, 150f, 0f, -1f, 0f, 1f),
                    WaveformSample(360f, 150f, 0f, -1f, 18f, 1f),
                ),
            distanceOffset = 342f,
            inwardEdges = emptySet(),
        )
    val config = testStyle().config
    return RouteFrame(
        signal =
            WaveformFrame(
                config = config,
                direction = TravelDirection.CLOCKWISE,
                trace =
                    FrameTrace(
                        anchorOffset = 0f,
                        history = List(config.traceComplexCount) { BeatMorphology.standard() },
                        phase = 0.4f,
                    ),
            ),
        centerDistance = 320f,
        signalSpan = 360f,
        currentSurfaceId = "Commit",
        slices = listOf(source, connector, destination),
    )
}

private fun touchingCoordinatorFrames(): List<RouteFrame> = coordinatorFrames(touchingPixelGraph())

private fun coordinatorFrames(
    graph: RouteGraph,
    boundaryMs: Long = TOUCHING_BOUNDARY_MS,
): List<RouteFrame> {
    val config = testStyle().config
    val coordinator =
        RouteCoordinator(
            initialConfig = config,
            random =
                object : kotlin.random.Random() {
                    override fun nextBits(bitCount: Int): Int =
                        when (bitCount) {
                            0 -> 0
                            Int.SIZE_BITS -> -1
                            else -> (1 shl bitCount) - 1
                        }
                },
        )
    coordinator.handle(RouteEvent.Activate(graph, "Editor", false))
    coordinator.handle(RouteEvent.Tick(0L))
    coordinator.handle(
        RouteEvent.Keystroke(
            boundaryMs -
                HANDOFF_TICK_MS -
                EnergyEnvelope.ENERGY_RISE_MS,
        ),
    )
    return listOf(
        boundaryMs - HANDOFF_TICK_MS,
        boundaryMs,
        boundaryMs + HANDOFF_TICK_MS,
    ).map { nowMs ->
        requireNotNull(coordinator.handle(RouteEvent.Tick(nowMs)).frame)
    }
}

private fun reboundFrames(
    initial: RouteGraph,
    rebound: RouteGraph,
    rebindAtMs: Long,
): Triple<RouteFrame, RouteFrame, RouteFrame> {
    val coordinator =
        RouteCoordinator(
            initialConfig = testStyle().config,
            random =
                object : kotlin.random.Random() {
                    override fun nextBits(bitCount: Int): Int =
                        when (bitCount) {
                            0 -> 0
                            Int.SIZE_BITS -> -1
                            else -> (1 shl bitCount) - 1
                        }
                },
        )
    coordinator.handle(RouteEvent.Activate(initial, "Editor", false))
    coordinator.handle(RouteEvent.Tick(0L))
    coordinator.handle(RouteEvent.Keystroke(rebindAtMs - EnergyEnvelope.ENERGY_RISE_MS))
    val before = requireNotNull(coordinator.handle(RouteEvent.Tick(rebindAtMs)).frame)
    val reboundFrame = requireNotNull(coordinator.handle(RouteEvent.GraphChanged(rebound)).frame)
    val next = requireNotNull(coordinator.handle(RouteEvent.Tick(rebindAtMs + 1L)).frame)
    return Triple(before, reboundFrame, next)
}

private fun touchingPixelGraph(): RouteGraph {
    val editor =
        RouteSurface(
            id = "Editor",
            rootId = RouteRootId(1),
            track = pixelTrack(originX = 280f, signalAnchorDistance = 44f),
            isEditor = true,
            windowKind = RouteWindowKind.MAIN,
            inwardEdges = emptySet(),
        )
    val commit =
        RouteSurface(
            id = "Commit",
            rootId = RouteRootId(1),
            track = pixelTrack(originX = 320f, signalAnchorDistance = 0f),
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
            sourceDistance = 40f,
            targetDistance = 0f,
            sourcePoint = RoutePoint(320f, 150f),
            targetPoint = RoutePoint(320f, 150f),
            length = 0f,
            requiresWindowBridge = false,
        )
    val bottom =
        top.copy(
            endpoint = RouteEndpoint.END,
            sourceDistance = 80f,
            targetDistance = 120f,
            sourcePoint = RoutePoint(320f, 190f),
            targetPoint = RoutePoint(320f, 190f),
        )
    val connectors = listOf(top, bottom)
    return RouteGraph(
        surfaces = mapOf(editor.id to editor, commit.id to commit),
        connectors =
            mapOf(
                editor.id to connectors,
                commit.id to connectors.map(RouteConnector::pixelReversed),
            ),
    )
}

private fun unequalGraph(
    yOffset: Float = 0f,
    targetExitDistance: Float = 0f,
): RouteGraph {
    val editor =
        RouteSurface(
            id = "Editor",
            rootId = RouteRootId(1),
            track = pixelTrack(originX = 280f, signalAnchorDistance = 44f, originY = 150f + yOffset),
            isEditor = true,
            windowKind = RouteWindowKind.MAIN,
            inwardEdges = emptySet(),
        )
    val commit =
        RouteSurface(
            id = "Commit",
            rootId = RouteRootId(1),
            track =
                pixelTrack(
                    originX = 320f,
                    signalAnchorDistance = 0f,
                    sideLength = 80f,
                    signalSpan = 160f,
                    originY = 150f + yOffset,
                ),
            isEditor = false,
            windowKind = RouteWindowKind.MAIN,
            inwardEdges = emptySet(),
        )
    val connector =
        RouteConnector(
            id = RouteConnectorId("Commit", "Editor", RouteSide.LEFT),
            endpoint = RouteEndpoint.START,
            sourceId = editor.id,
            targetId = commit.id,
            sourceSide = RouteSide.RIGHT,
            targetSide = RouteSide.LEFT,
            sourceDistance = 40f,
            targetDistance = targetExitDistance,
            sourcePoint = RoutePoint(320f, 150f + yOffset),
            targetPoint = RoutePoint(320f, 150f + yOffset),
            length = 0f,
            requiresWindowBridge = false,
        )
    return RouteGraph(
        surfaces = mapOf(editor.id to editor, commit.id to commit),
        connectors =
            mapOf(
                editor.id to listOf(connector),
                commit.id to listOf(connector.pixelReversed()),
            ),
    )
}

private fun reverseUnequalGraph(): RouteGraph = spanPixelGraph()

private fun successorPixelGraph(): RouteGraph {
    val editor =
        pixelSurface(
            id = "Editor",
            originX = 100f,
            sideLength = 100f,
            signalAnchorDistance = 104f,
            signalSpan = 360f,
        )
    val commit =
        pixelSurface(
            id = "Commit",
            originX = 200f,
            sideLength = 10f,
            signalAnchorDistance = 0f,
            signalSpan = 40f,
        )
    val problems =
        pixelSurface(
            id = "Problems",
            originX = 210f,
            sideLength = 100f,
            signalAnchorDistance = 0f,
            signalSpan = 360f,
        )
    val first = touchingConnector(editor, commit, 100f)
    val second = touchingConnector(commit, problems, 10f)
    return RouteGraph(
        surfaces = listOf(editor, commit, problems).associateBy(RouteSurface::id),
        connectors =
            mapOf(
                editor.id to listOf(first),
                commit.id to listOf(first.pixelReversed(), second),
                problems.id to listOf(second.pixelReversed()),
            ),
    )
}

private fun spanPixelGraph(): RouteGraph {
    val sourceSide = 50f
    val sourceSpan = 160f
    val targetSide = 25f
    val targetSpan = 80f
    val editor =
        pixelSurface(
            id = "Editor",
            originX = 320f - sourceSide,
            sideLength = sourceSide,
            signalAnchorDistance = sourceSide + 4f,
            signalSpan = sourceSpan,
        )
    val commit =
        pixelSurface(
            id = "Commit",
            originX = 320f,
            sideLength = targetSide,
            signalAnchorDistance = 0f,
            signalSpan = targetSpan,
        )
    val connector = touchingConnector(editor, commit, sourceSide)
    return RouteGraph(
        surfaces = mapOf(editor.id to editor, commit.id to commit),
        connectors =
            mapOf(
                editor.id to listOf(connector),
                commit.id to listOf(connector.pixelReversed()),
            ),
    )
}

private fun pixelSurface(
    id: String,
    originX: Float,
    sideLength: Float,
    signalAnchorDistance: Float,
    signalSpan: Float,
): RouteSurface =
    RouteSurface(
        id = id,
        rootId = RouteRootId(1),
        track =
            pixelTrack(
                originX = originX,
                signalAnchorDistance = signalAnchorDistance,
                sideLength = sideLength,
                signalSpan = signalSpan,
            ),
        isEditor = id == "Editor",
        windowKind = RouteWindowKind.MAIN,
        inwardEdges = emptySet(),
    )

private fun touchingConnector(
    source: RouteSurface,
    target: RouteSurface,
    sourceDistance: Float,
): RouteConnector {
    val sourcePoint = source.track.sampleAt(sourceDistance)
    val targetPoint = target.track.sampleAt(0f)
    return RouteConnector(
        id =
            RouteConnectorId(
                firstSurfaceId = minOf(source.id, target.id),
                secondSurfaceId = maxOf(source.id, target.id),
                firstSide = RouteSide.RIGHT,
            ),
        endpoint = RouteEndpoint.START,
        sourceId = source.id,
        targetId = target.id,
        sourceSide = RouteSide.RIGHT,
        targetSide = RouteSide.LEFT,
        sourceDistance = sourceDistance,
        targetDistance = 0f,
        sourcePoint = RoutePoint(sourcePoint.x, sourcePoint.y),
        targetPoint = RoutePoint(targetPoint.x, targetPoint.y),
        length = 0f,
        requiresWindowBridge = false,
    )
}

private fun RouteConnector.pixelReversed(): RouteConnector =
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

private fun pixelTrack(
    originX: Float,
    signalAnchorDistance: Float,
    sideLength: Float = 40f,
    signalSpan: Float = 80f,
    originY: Float = 150f,
): WaveformTrack =
    WaveformTrack(
        samples =
            listOf(
                WaveformSample(originX, originY, 0f, -1f, 0f, 1f),
                WaveformSample(originX + sideLength, originY, 1f, 0f, sideLength, 1f),
                WaveformSample(
                    originX + sideLength,
                    originY + sideLength,
                    0f,
                    1f,
                    sideLength * 2f,
                    1f,
                ),
                WaveformSample(originX, originY + sideLength, -1f, 0f, sideLength * 3f, 1f),
            ),
        length = sideLength * 4f,
        signalAnchorDistance = signalAnchorDistance,
        signalSpan = signalSpan,
    )

private fun assertBoundedPixels(
    frames: List<RouteFrame>,
    images: List<BufferedImage>,
) {
    val alphaMasses = images.map(::alphaMass)
    val maximumMass = requireNotNull(alphaMasses.maxOrNull())
    val minimumMass = requireNotNull(alphaMasses.minOrNull())
    assertTrue(
        maximumMass - minimumMass <= maximumMass / BRIGHTNESS_TOLERANCE_DIVISOR,
        "alpha mass exceeded boundary budget: $alphaMasses",
    )
    val extents = images.map { image -> assertNotNull(paintedBounds(image)) }
    extents.zipWithNext().forEach { (first, second) ->
        assertTrue(
            kotlin.math.abs(second.width - first.width) <= PIXEL_CHANGE_LIMIT,
            "painted width changed from ${first.width} to ${second.width}",
        )
        assertTrue(
            kotlin.math.abs(second.height - first.height) <= PIXEL_CHANGE_LIMIT,
            "painted height changed from ${first.height} to ${second.height}",
        )
    }
    images.map(::alphaCentroid).zipWithNext().forEach { (first, second) ->
        val movement = hypot((second.x - first.x).toDouble(), (second.y - first.y).toDouble())
        assertTrue(movement <= PIXEL_CHANGE_LIMIT, "centroid moved $movement pixels")
    }
    frames.forEach { frame ->
        assertTrue(frame.signal.energy > 0.9f)
        assertEquals(frame.signal.config.brightnessAt(frame.signal.energy), frame.signal.brightness, 0.001f)
    }
}

private fun alphaMass(
    image: BufferedImage,
    bounds: Rectangle = Rectangle(0, 0, image.width, image.height),
): Long =
    (bounds.y until bounds.y + bounds.height).sumOf { y ->
        (bounds.x until bounds.x + bounds.width).sumOf { x ->
            image.getRGB(x, y).ushr(24).toLong()
        }
    }

private fun alphaCentroid(image: BufferedImage): RoutePoint {
    var totalAlpha = 0L
    var weightedX = 0L
    var weightedY = 0L
    for (y in 0 until image.height) {
        for (x in 0 until image.width) {
            val alpha = image.getRGB(x, y).ushr(24)
            totalAlpha += alpha
            weightedX += x.toLong() * alpha
            weightedY += y.toLong() * alpha
        }
    }
    require(totalAlpha > 0L) { "Rendered route must contain visible pixels" }
    return RoutePoint(
        x = weightedX.toFloat() / totalAlpha,
        y = weightedY.toFloat() / totalAlpha,
    )
}

private fun render(component: JComponent): BufferedImage {
    val image = BufferedImage(CANVAS_WIDTH, CANVAS_HEIGHT, BufferedImage.TYPE_INT_ARGB)
    val graphics = image.createGraphics()
    component.paint(graphics)
    graphics.dispose()
    return image
}

private fun nonTransparent(
    image: BufferedImage,
    x: Int,
    y: Int,
): Boolean = image.getRGB(x, y).ushr(24) > 0

private fun hasPaintedPixel(
    image: BufferedImage,
    bounds: Rectangle,
): Boolean =
    (bounds.x until bounds.x + bounds.width).any { x ->
        (bounds.y until bounds.y + bounds.height).any { y -> nonTransparent(image, x, y) }
    }

private fun hasEmptyColumnRun(
    image: BufferedImage,
    yRange: IntRange,
): Boolean {
    var run = 0
    for (x in BRIDGE_SCAN_START_X..BRIDGE_SCAN_END_X) {
        val painted = yRange.any { y -> nonTransparent(image, x, y) }
        run = if (painted) 0 else run + 1
        if (run >= MINIMUM_EMPTY_COLUMN_RUN) return true
    }
    return false
}

private fun paintedBounds(image: BufferedImage): Rectangle? {
    var left = image.width
    var top = image.height
    var right = -1
    var bottom = -1
    for (y in 0 until image.height) {
        for (x in 0 until image.width) {
            if (!nonTransparent(image, x, y)) continue
            left = minOf(left, x)
            top = minOf(top, y)
            right = maxOf(right, x)
            bottom = maxOf(bottom, y)
        }
    }
    return if (right >= left && bottom >= top) Rectangle(left, top, right - left + 1, bottom - top + 1) else null
}

private fun pixelDifference(
    first: BufferedImage,
    second: BufferedImage,
): Int =
    (0 until first.height).sumOf { y ->
        (0 until first.width).count { x -> first.getRGB(x, y) != second.getRGB(x, y) }
    }
