package dev.ayuislands.glow.waveform

import dev.ayuislands.glow.GlowRenderer
import dev.ayuislands.glow.GlowStyle
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
    fun `signal strokes derive from the solid width`() {
        assertEquals(
            WaveformPainter.SignalStrokes(core = 2f, inner = 4f, bloom = 8f),
            WaveformPainter.strokeWidths(solidWidth = 4),
        )
    }

    @Test
    fun `track cache observes mutation of a reused bounds instance`() {
        val mutableBounds = Rectangle(0, 0, WIDTH, HEIGHT)
        val config = WaveformConfig()
        val initial = painter.trackLength(mutableBounds, ARC_WIDTH, config, SOLID_WIDTH)

        mutableBounds.width += 100
        val resized = painter.trackLength(mutableBounds, ARC_WIDTH, config, SOLID_WIDTH)

        assertTrue(resized > initial)
    }

    @Test
    fun `R peak changes production waveform pixels`() {
        val config = WaveformConfig(amplitude = 10, intensity = 80)
        val flat = render(WaveformFrame(config = config))
        val center = flat.result.track.length * 0.25f
        val beat = render(frame(config, center))

        assertTrue(pixelDifference(flat.image, beat.image) > MIN_PIXEL_DIFFERENCE)
        assertSame(flat.result.track, beat.result.track, "unchanged geometry must reuse the sampled perimeter")
    }

    @Test
    fun `idle monitor keeps a visible ECG peak without active-beat brightness`() {
        val config = WaveformConfig(amplitude = 14, intensity = 100)
        val idle = render(WaveformFrame(config = config, brightness = IDLE_WAVEFORM_BRIGHTNESS))
        val peak =
            idle.result.track.samples.minBy { sample ->
                kotlin.math.abs(sample.distance - idle.result.track.signalAnchorDistance)
            }
        val probeX = (peak.x + peak.normalX * IDLE_PEAK_PROBE).roundToInt()
        val probeY = (peak.y + peak.normalY * IDLE_PEAK_PROBE).roundToInt()
        val active = render(frame(config, peak.distance))

        assertTrue(alphaAt(idle.image, probeX, probeY) > 0, "idle R peak must read as ECG geometry")
        assertTrue(alphaSum(active.image) > alphaSum(idle.image), "typing beat must remain brighter than idle ECG")
    }

    @Test
    fun `waveform intensity zero leaves the shared solid base unchanged`() {
        val config = WaveformConfig(amplitude = 16, intensity = 0)
        val waveform = render(WaveformFrame(config = config))
        val expected = renderSolidBase()

        assertEquals(0, pixelDifference(expected, waveform.image))
    }

    @Test
    fun `pixels away from the local top ECG equal the base only render`() {
        val config = WaveformConfig(motion = WaveformMotion.STATIC_PULSE, amplitude = 16, intensity = 100)
        val active =
            render(
                WaveformFrame(
                    config = config,
                    energy = 1f,
                    brightness = 1f,
                ),
            )
        val base = renderSolidBase()
        val lowerThird = Rectangle(0, HEIGHT * 2 / 3, WIDTH, HEIGHT / 3)

        assertEquals(0, pixelDifference(base, active.image, lowerThird))
        assertTrue(pixelDifference(base, active.image) > MIN_PIXEL_DIFFERENCE)
        val signalColor = strongestChangedColor(base, active.image)
        assertEquals(accent.red.toDouble(), signalColor.red.toDouble(), COLOR_TOLERANCE)
        assertEquals(accent.green.toDouble(), signalColor.green.toDouble(), COLOR_TOLERANCE)
        assertEquals(accent.blue.toDouble(), signalColor.blue.toDouble(), COLOR_TOLERANCE)
    }

    @Test
    fun `occupied editor top span hides ECG but preserves the solid base`() {
        val config = WaveformConfig(amplitude = 16, intensity = 100)
        val occupied = render(frame(config, 0f), occupiedTopSpans = listOf(0 until WIDTH))
        val base = renderSolidBase()
        val topAwayFromFallback = Rectangle(0, 0, WIDTH - RIGHT_FALLBACK_BAND, HEIGHT / 3)

        assertEquals(
            0,
            pixelDifference(base, occupied.image, topAwayFromFallback),
            "top chrome mask must suppress only the ECG signal",
        )
        assertTrue(
            pixelDifference(base, occupied.image) > MIN_PIXEL_DIFFERENCE,
            "signal must fall back to the right edge",
        )
    }

    @Test
    fun `editor monitor starts from the two thirds top anchor`() {
        for (direction in WaveformDirection.entries) {
            val config = WaveformConfig(direction = direction, amplitude = 14, intensity = 100)
            val idle = render(WaveformFrame(config = config))
            val started = render(frame(config, center = 0f))
            val anchor = started.result.track.sampleNearest(started.result.track.signalAnchorDistance)
            val topAnchorBand =
                Rectangle(
                    (anchor.x - started.result.track.signalSpan / 2f).roundToInt().coerceAtLeast(0),
                    0,
                    0,
                    HEIGHT / 3,
                ).also { region ->
                    region.width =
                        started.result.track.signalSpan
                            .roundToInt()
                            .coerceAtMost(WIDTH - region.x)
                }

            assertTrue(
                pixelDifference(idle.image, started.image, topAnchorBand) > MIN_PIXEL_DIFFERENCE,
                "$direction monitor must start at the top anchor",
            )
            assertEquals(WIDTH * 2f / 3f, anchor.x, 3f)
        }
    }

    @Test
    fun `editor static pulse ignores dormant monitor direction`() {
        val clockwise =
            render(
                WaveformFrame(
                    config =
                        WaveformConfig(
                            motion = WaveformMotion.STATIC_PULSE,
                            direction = WaveformDirection.CLOCKWISE,
                            amplitude = 16,
                            intensity = 100,
                        ),
                    energy = 1f,
                ),
            )
        val counterClockwise =
            render(
                WaveformFrame(
                    config =
                        WaveformConfig(
                            motion = WaveformMotion.STATIC_PULSE,
                            direction = WaveformDirection.COUNTER_CLOCKWISE,
                            amplitude = 16,
                            intensity = 100,
                        ),
                    energy = 1f,
                ),
            )

        assertEquals(0, pixelDifference(clockwise.image, counterClockwise.image))
    }

    @Test
    fun `R peaks extend outward on right bottom and left edges`() {
        val config = WaveformConfig(amplitude = 16, intensity = 100)
        val flat = render(WaveformFrame(config = config))
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
            val beat = render(frame(config, sample.distance - flat.result.track.signalAnchorDistance))
            val x = (sample.x + sample.normalX * OUTWARD_PROBE).roundToInt()
            val y = (sample.y + sample.normalY * OUTWARD_PROBE).roundToInt()
            assertTrue(alphaAt(beat.image, x, y) > alphaAt(flat.image, x, y), "R peak must paint outward at $x,$y")
        }
    }

    @Test
    fun `full static boost increases displaced pixels and brightness`() {
        val config = WaveformConfig(motion = WaveformMotion.STATIC_PULSE, amplitude = 16, intensity = 100)
        val idle =
            render(
                WaveformFrame(
                    config = config,
                    energy = 0f,
                    brightness = IDLE_WAVEFORM_BRIGHTNESS,
                ),
            )
        val active = render(WaveformFrame(config = config, energy = 1f, brightness = 1f))

        assertTrue(pixelDifference(idle.image, active.image) > MIN_PIXEL_DIFFERENCE)
        assertTrue(alphaSum(active.image) > alphaSum(idle.image))
    }

    private fun frame(
        config: WaveformConfig,
        center: Float,
    ): WaveformFrame =
        WaveformFrame(
            config = config,
            beats = listOf(FrameBeat(center, BeatMorphology.random(Random(42)))),
            brightness = 1f,
            energy = 1f,
        )

    private fun render(
        frame: WaveformFrame,
        occupiedTopSpans: List<IntRange> = emptyList(),
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
                            occupiedTopSpans = occupiedTopSpans,
                            solidFrame = solidFrame(),
                        ),
                )
            } finally {
                graphics.dispose()
            }
        return Rendered(image, result)
    }

    private fun renderSolidBase(): BufferedImage {
        val image = BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_ARGB)
        val graphics = image.createGraphics()
        try {
            val renderer = GlowRenderer()
            renderer.ensureCache(accent, GlowStyle.SHARP_NEON, SOLID_INTENSITY / 5, SOLID_WIDTH)
            renderer.paintGlow(graphics, bounds, SOLID_WIDTH, ARC_WIDTH)
        } finally {
            graphics.dispose()
        }
        return image
    }

    private fun solidFrame(): SolidFrameSpec =
        SolidFrameSpec(
            bounds = bounds,
            style = GlowStyle.SHARP_NEON,
            intensity = SOLID_INTENSITY,
            width = SOLID_WIDTH,
        )

    private fun pixelDifference(
        first: BufferedImage,
        second: BufferedImage,
        bounds: Rectangle = Rectangle(0, 0, first.width, first.height),
    ): Int =
        (bounds.y until bounds.y + bounds.height).sumOf { y ->
            (bounds.x until bounds.x + bounds.width).count { x -> first.getRGB(x, y) != second.getRGB(x, y) }
        }

    private fun alphaSum(image: BufferedImage): Long =
        (0 until image.height).sumOf { y ->
            (0 until image.width).sumOf { x -> alphaAt(image, x, y).toLong() }
        }

    private fun strongestChangedColor(
        base: BufferedImage,
        active: BufferedImage,
    ): Color {
        val pixel =
            (0 until active.height)
                .flatMap { y ->
                    (0 until active.width).mapNotNull { x ->
                        active.getRGB(x, y).takeIf { it != base.getRGB(x, y) }
                    }
                }.maxBy { it ushr ALPHA_SHIFT and MAX_ALPHA }
        return Color(pixel, true)
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

    private fun WaveformTrack.sampleNearest(distance: Float): WaveformSample =
        samples.minBy { sample -> kotlin.math.abs(sample.distance - distance) }

    private companion object {
        const val WIDTH = 240
        const val HEIGHT = 160
        const val ARC_WIDTH = 16
        const val OUTWARD_PROBE = 11f
        const val IDLE_PEAK_PROBE = 10f
        const val RIGHT_FALLBACK_BAND = 40
        const val MIN_PIXEL_DIFFERENCE = 100
        const val SOLID_INTENSITY = 80
        const val SOLID_WIDTH = 4
        const val ALPHA_SHIFT = 24
        const val MAX_ALPHA = 0xFF
        const val COLOR_TOLERANCE = 2.0
    }
}
