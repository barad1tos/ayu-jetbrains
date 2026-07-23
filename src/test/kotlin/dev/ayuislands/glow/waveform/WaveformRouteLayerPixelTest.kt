package dev.ayuislands.glow.waveform

import dev.ayuislands.glow.GlowStyle
import java.awt.Color
import java.awt.Rectangle
import java.awt.image.BufferedImage
import javax.swing.JComponent
import javax.swing.RepaintManager
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class WaveformRouteLayerPixelTest {
    @Test
    fun `source connector and destination paint one continuous hot trace`() {
        val layer = WaveformRouteLayer(RouteRootId(1)) { throw it }
        layer.setBounds(0, 0, 640, 320)
        layer.updateStyle(testStyle())
        val frame = transitionFrame()
        layer.showFrame(frame, frame.slices)

        val image = render(layer, 640, 320)

        assertTrue(hasPaintedPixel(image, Rectangle(292, 138, 12, 24)))
        assertTrue(hasPaintedPixel(image, Rectangle(314, 138, 12, 24)))
        assertTrue(hasPaintedPixel(image, Rectangle(336, 138, 12, 24)))
        assertFalse(
            hasEmptyColumnRun(
                image = image,
                yRange = 138..162,
                fromX = 298,
                toX = 342,
                minimumRun = 2,
            ),
        )
    }

    @Test
    fun `route layer never paints the solid base frame`() {
        val layer = WaveformRouteLayer(RouteRootId(1)) { throw it }
        layer.setBounds(0, 0, 640, 320)
        layer.updateStyle(testStyle())
        val frame = transitionFrame()
        layer.showFrame(frame, frame.slices)

        val image = render(layer, 640, 320)

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

        val image = render(layer, 640, 320)

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
        layer.setBounds(0, 0, 640, 320)
        layer.updateStyle(testStyle())
        val firstFrame = transitionFrame().copy(centerDistance = 270f)
        val secondFrame = transitionFrame().copy(centerDistance = 320f)
        val previousManager = RepaintManager.currentManager(layer)
        val recordingManager = RecordingRepaintManager()
        RepaintManager.setCurrentManager(recordingManager)
        try {
            layer.showFrame(firstFrame, firstFrame.slices)
            val firstBounds = assertNotNull(paintedBounds(render(layer, 640, 320)))
            recordingManager.clear()

            layer.showFrame(secondFrame, secondFrame.slices)
            val secondBounds = assertNotNull(paintedBounds(render(layer, 640, 320)))
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
            layer.setBounds(0, 0, 640, 320)
            layer.updateStyle(testStyle())
            layer.showFrame(frame, frame.slices)
        }

    private fun renderFrame(frame: RouteFrame): BufferedImage = render(routeLayer(frame), 640, 320)

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
        visibleSurfaceIds = setOf("Editor", "Commit"),
        slices = listOf(source, connector, destination),
    )
}

private fun render(
    component: JComponent,
    width: Int,
    height: Int,
): BufferedImage {
    val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
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
    fromX: Int,
    toX: Int,
    minimumRun: Int,
): Boolean {
    var run = 0
    for (x in fromX..toX) {
        val painted = yRange.any { y -> nonTransparent(image, x, y) }
        run = if (painted) 0 else run + 1
        if (run >= minimumRun) return true
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
