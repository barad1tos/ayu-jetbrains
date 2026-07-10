package dev.ayuislands.glow.waveform

import java.awt.Color
import java.awt.Rectangle
import java.awt.image.BufferedImage
import kotlin.math.roundToInt
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class WaveformPainterPixelTest {
    private val painter = WaveformPainter()
    private val bounds = Rectangle(0, 0, WIDTH, HEIGHT)
    private val accent = Color(0x5CCFE6)

    @Test
    fun `R peak changes production waveform pixels`() {
        val config = WaveformConfig(amplitude = 10, intensity = 80)
        val flat = render(WaveformFrame(nowMs = 0L, config = config))
        val center = flat.result.track.length * 0.25f
        val beat = render(frame(config, center))

        assertTrue(pixelDifference(flat.image, beat.image) > MIN_PIXEL_DIFFERENCE)
        assertSame(flat.result.track, beat.result.track, "unchanged geometry must reuse the sampled perimeter")
    }

    @Test
    fun `idle monitor keeps a visible ECG peak without active-beat brightness`() {
        val config = WaveformConfig(amplitude = 14, intensity = 100)
        val idle = render(WaveformFrame(nowMs = 0L, config = config, brightness = IDLE_WAVEFORM_BRIGHTNESS))
        val peak =
            idle.result.track.samples.minBy { sample ->
                kotlin.math.abs(sample.distance - idle.result.track.length * WaveformPainter.STATIC_CENTER_FRACTION)
            }
        val probeX = (peak.x + peak.normalX * IDLE_PEAK_PROBE).roundToInt()
        val probeY = (peak.y + peak.normalY * IDLE_PEAK_PROBE).roundToInt()
        val active = render(frame(config, peak.distance))

        assertTrue(alphaAt(idle.image, probeX, probeY) > 0, "idle R peak must read as ECG geometry")
        assertTrue(alphaSum(active.image) > alphaSum(idle.image), "typing beat must remain brighter than idle ECG")
    }

    @Test
    fun `editor top edge stays flat while a beat crosses it`() {
        val config = WaveformConfig(amplitude = 16, intensity = 100)
        val flat = render(WaveformFrame(nowMs = 0L, config = config), isEditorOverlay = true)
        val top =
            flat.result.track.samples
                .first { it.normalY < -0.999f && it.x > WIDTH * 0.45f }
        val beat =
            render(
                frame(config, top.distance),
                isEditorOverlay = true,
                isEdgeAligned = false,
            )
        val outwardLimit = (top.y - WaveformPainter.BLOOM_RADIUS).roundToInt()

        assertEquals(
            0,
            alphaCount(beat.image, (WIDTH * 0.4f).roundToInt() until (WIDTH * 0.6f).roundToInt(), 0 until outwardLimit),
            "masked editor top must not displace pixels into the tab band",
        )
    }

    @Test
    fun `editor monitor beat starts on a visible edge below the tab mask`() {
        val visibleEdges = Rectangle(0, EDITOR_TOP_MASK_BAND, WIDTH, HEIGHT - EDITOR_TOP_MASK_BAND)

        for (direction in WaveformDirection.entries) {
            val config = WaveformConfig(direction = direction, amplitude = 14, intensity = 100)
            val idle = render(WaveformFrame(nowMs = 0L, config = config), isEditorOverlay = true)
            val started = render(frame(config, center = 0f), isEditorOverlay = true)
            val top =
                started.result.track.samples
                    .first { it.normalY < -0.999f && it.x > WIDTH * 0.45f }
            val outwardLimit = (top.y - WaveformPainter.BLOOM_RADIUS).roundToInt()

            assertTrue(
                pixelDifference(idle.image, started.image, visibleEdges) > MIN_PIXEL_DIFFERENCE,
                "$direction monitor beat must start on a visible editor edge",
            )
            assertEquals(
                0,
                alphaCount(started.image, 0 until WIDTH, 0 until outwardLimit),
                "$direction monitor beat must keep the editor tab band clear",
            )
        }
    }

    @Test
    fun `editor static pulse ignores dormant monitor direction`() {
        val clockwise =
            render(
                WaveformFrame(
                    nowMs = 0L,
                    config =
                        WaveformConfig(
                            motion = WaveformMotion.STATIC_PULSE,
                            direction = WaveformDirection.CLOCKWISE,
                            amplitude = 16,
                            intensity = 100,
                        ),
                    staticBoost = 1f,
                ),
                isEditorOverlay = true,
            )
        val counterClockwise =
            render(
                WaveformFrame(
                    nowMs = 0L,
                    config =
                        WaveformConfig(
                            motion = WaveformMotion.STATIC_PULSE,
                            direction = WaveformDirection.COUNTER_CLOCKWISE,
                            amplitude = 16,
                            intensity = 100,
                        ),
                    staticBoost = 1f,
                ),
                isEditorOverlay = true,
            )

        assertEquals(0, pixelDifference(clockwise.image, counterClockwise.image))
    }

    @Test
    fun `R peaks extend outward on right bottom and left edges`() {
        val config = WaveformConfig(amplitude = 16, intensity = 100)
        val flat = render(WaveformFrame(nowMs = 0L, config = config))
        val edgeSamples =
            listOf(
                flat.result.track.samples
                    .first { it.normalX > 0.999f && it.y > HEIGHT * 0.45f },
                flat.result.track.samples
                    .first { it.normalY > 0.999f && it.x < WIDTH * 0.55f },
                flat.result.track.samples
                    .first { it.normalX < -0.999f && it.y < HEIGHT * 0.55f },
            )

        for (sample in edgeSamples) {
            val beat = render(frame(config, sample.distance))
            val x = (sample.x + sample.normalX * OUTWARD_PROBE).roundToInt()
            val y = (sample.y + sample.normalY * OUTWARD_PROBE).roundToInt()
            assertTrue(alphaAt(beat.image, x, y) > alphaAt(flat.image, x, y), "R peak must paint outward at $x,$y")
        }
    }

    @Test
    fun `full static boost increases displaced pixels and brightness`() {
        val config = WaveformConfig(motion = WaveformMotion.STATIC_PULSE, amplitude = 16, intensity = 100)
        val idle = render(WaveformFrame(nowMs = 0L, config = config, staticBoost = 0f, brightness = 0.55f))
        val active = render(WaveformFrame(nowMs = 0L, config = config, staticBoost = 1f, brightness = 1f))

        assertTrue(pixelDifference(idle.image, active.image) > MIN_PIXEL_DIFFERENCE)
        assertTrue(alphaSum(active.image) > alphaSum(idle.image))
    }

    private fun frame(
        config: WaveformConfig,
        center: Float,
    ): WaveformFrame =
        WaveformFrame(
            nowMs = 0L,
            config = config,
            beats = listOf(FrameBeat(center, BeatMorphology.random(Random(42)), opacity = 1f)),
        )

    private fun render(
        frame: WaveformFrame,
        isEditorOverlay: Boolean = false,
        isEdgeAligned: Boolean = isEditorOverlay,
    ): Rendered {
        val image = BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_ARGB)
        val graphics = image.createGraphics()
        val result =
            try {
                painter.paint(
                    graphics = graphics,
                    request =
                        WaveformPaintRequest(
                            bounds = bounds,
                            arcWidth = ARC_WIDTH,
                            accent = accent,
                            frame = frame,
                            isEditorOverlay = isEditorOverlay,
                            isEdgeAligned = isEdgeAligned,
                        ),
                )
            } finally {
                graphics.dispose()
            }
        return Rendered(image, result)
    }

    private fun pixelDifference(
        first: BufferedImage,
        second: BufferedImage,
        bounds: Rectangle = Rectangle(0, 0, first.width, first.height),
    ): Int =
        (bounds.y until bounds.y + bounds.height).sumOf { y ->
            (bounds.x until bounds.x + bounds.width).count { x -> first.getRGB(x, y) != second.getRGB(x, y) }
        }

    private fun alphaCount(
        image: BufferedImage,
        xRange: IntRange,
        yRange: IntRange,
    ): Int = yRange.sumOf { y -> xRange.count { x -> alphaAt(image, x, y) > 0 } }

    private fun alphaSum(image: BufferedImage): Long =
        (0 until image.height).sumOf { y ->
            (0 until image.width).sumOf { x -> alphaAt(image, x, y).toLong() }
        }

    private fun alphaAt(
        image: BufferedImage,
        x: Int,
        y: Int,
    ): Int =
        if (x in 0 until image.width && y in 0 until image.height) {
            image.getRGB(x, y) ushr ALPHA_SHIFT and MAX_ALPHA
        } else {
            0
        }

    private data class Rendered(
        val image: BufferedImage,
        val result: WaveformPaintResult,
    )

    private companion object {
        const val WIDTH = 240
        const val HEIGHT = 160
        const val ARC_WIDTH = 16
        const val OUTWARD_PROBE = 11f
        const val IDLE_PEAK_PROBE = 10f
        const val EDITOR_TOP_MASK_BAND = 40
        const val MIN_PIXEL_DIFFERENCE = 100
        const val ALPHA_SHIFT = 24
        const val MAX_ALPHA = 0xFF
    }
}
