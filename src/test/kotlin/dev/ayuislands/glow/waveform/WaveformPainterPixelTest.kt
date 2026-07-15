package dev.ayuislands.glow.waveform

import dev.ayuislands.glow.GlowRenderer
import dev.ayuislands.glow.GlowStyle
import java.awt.Color
import java.awt.Rectangle
import java.awt.image.BufferedImage
import kotlin.math.abs
import kotlin.math.min
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
            WaveformPainter.SignalStrokes(core = 2f, inner = 4f, bloom = 8f, outer = 14f),
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
    fun `idle monitor keeps a full sized visible ECG peak`() {
        val config = WaveformConfig(amplitude = 14, intensity = 100)
        val idle = render(WaveformFrame(config = config, brightness = config.brightnessAt(0f)))
        val peak =
            idle.result.track.samples.minBy { sample ->
                kotlin.math.abs(sample.distance - idle.result.track.signalAnchorDistance)
            }
        val probeDistance = config.amplitude * MIN_ACTIVE_HEIGHT_FRACTION
        val probeX = (peak.x + peak.normalX * probeDistance).roundToInt()
        val probeY = (peak.y + peak.normalY * probeDistance).roundToInt()

        assertTrue(alphaAt(idle.image, probeX, probeY) > 0, "idle R peak must read as ECG geometry")
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
            val beat =
                render(
                    frame(
                        config,
                        sample.distance - flat.result.track.signalAnchorDistance,
                        BeatMorphology.standard(),
                    ),
                )
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
                    brightness = config.brightnessAt(0f),
                ),
            )
        val active = render(WaveformFrame(config = config, energy = 1f, brightness = 1f))

        assertTrue(pixelDifference(idle.image, active.image) > MIN_PIXEL_DIFFERENCE)
        assertTrue(alphaSum(active.image) > alphaSum(idle.image))
    }

    @Test
    fun `active R peak reads as a tall sharp flash`() {
        val config = WaveformConfig(motion = WaveformMotion.STATIC_PULSE, amplitude = 24, intensity = 100)
        val idle =
            render(
                WaveformFrame(
                    config = config,
                    energy = 0f,
                    brightness = config.brightnessAt(0f),
                ),
            )
        val active = render(WaveformFrame(config = config, energy = 1f, brightness = 1f))
        val base = renderSolidBase()
        val anchor = active.result.track.sampleNearest(active.result.track.signalAnchorDistance)
        val region =
            Rectangle(
                (anchor.x - FLASH_HALF_WIDTH).roundToInt(),
                0,
                FLASH_HALF_WIDTH * 2,
                anchor.y.roundToInt() + 1,
            )
        val profile = peakProfile(base, active.image, region, anchor.y.roundToInt())
        val activeAlpha = addedAlpha(base, active.image, region)
        val idleAlpha = addedAlpha(base, idle.image, region)

        assertTrue(
            profile.height >= config.amplitude * MIN_ACTIVE_HEIGHT_FRACTION,
            "active R peak must use the configured amplitude: $profile",
        )
        assertTrue(profile.apexWidth <= MAX_APEX_WIDTH, "R apex must stay needle-sharp: $profile")
        assertTrue(
            profile.midWidth >= profile.apexWidth * MIN_APEX_EXPANSION,
            "R peak must widen below a narrow apex instead of forming a rounded bump: $profile",
        )
        assertTrue(
            activeAlpha >= idleAlpha * MIN_FLASH_ALPHA_RATIO,
            "typing must produce a conspicuous brightness flash: active=$activeAlpha idle=$idleAlpha",
        )
        assertEquals(
            0,
            pixelDifference(base, active.image, Rectangle(region.x, 0, region.width, 1)),
            "R peak must not clip against the overlay edge",
        )
    }

    @Test
    fun `idle monitor preserves configured peak geometry while typing changes luminance`() {
        val config = WaveformConfig(amplitude = MAX_WAVEFORM_AMPLITUDE, intensity = MAX_WAVEFORM_INTENSITY)
        val engine = WaveformEngine(config, Random(42))
        val activated = requireNotNull(engine.handle(WaveformEvent.Activate(powerSaveEnabled = false)).frame)
        val trackLength = renderEditorScale(activated).result.track.length
        val idleFrame = requireNotNull(engine.handle(WaveformEvent.Tick(0L, trackLength)).frame)
        val idle = renderEditorScale(idleFrame)
        engine.handle(WaveformEvent.Keystroke(0L))
        val activeFrame = requireNotNull(engine.handle(WaveformEvent.Tick(80L, trackLength)).frame)
        val active = renderEditorScale(activeFrame)
        val base = renderEditorScale(idleFrame.copy(config = config.copy(intensity = 0)))
        val anchor = idle.result.track.sampleNearest(idle.result.track.signalAnchorDistance)
        val topRegion = Rectangle(0, 0, idle.image.width, anchor.y.roundToInt() + 1)
        val idleBounds =
            visibleSignalBounds(
                base = base.image,
                active = idle.image,
                region = topRegion,
            )
        val activeBounds = visibleSignalBounds(base.image, active.image, topRegion)
        val idlePeakHeight = anchor.y.roundToInt() - idleBounds.y
        val activePeakHeight = anchor.y.roundToInt() - activeBounds.y

        assertTrue(
            idlePeakHeight >= config.amplitude * MIN_ACTIVE_HEIGHT_FRACTION,
            "monitor amplitude must not collapse while idle: height=$idlePeakHeight signal=$idleBounds",
        )
        assertTrue(
            abs(activePeakHeight - idlePeakHeight) <= MAX_MONITOR_PEAK_DELTA,
            "typing must not resize monitor geometry: idle=$idlePeakHeight active=$activePeakHeight",
        )
        assertTrue(
            addedAlpha(base.image, active.image, topRegion) > addedAlpha(base.image, idle.image, topRegion),
            "typing must brighten the monitor without changing its amplitude",
        )
        assertTrue(
            idleBounds.width >= EDITOR_CONTENT_WIDTH * MIN_EDITOR_SIGNAL_WIDTH_FRACTION,
            "editor ECG must occupy a substantial visible span: $idleBounds",
        )
    }

    @Test
    fun `monitor trace evolves independently of perimeter travel`() {
        val config = WaveformConfig(loopSeconds = 30f, amplitude = 18, intensity = 100)
        val engine = WaveformEngine(config, Random(43))
        val activated = requireNotNull(engine.handle(WaveformEvent.Activate(powerSaveEnabled = false)).frame)
        val trackLength = renderEditorScale(activated).result.track.length
        val initial = requireNotNull(engine.handle(WaveformEvent.Tick(0L, trackLength)).frame)
        val evolved = requireNotNull(engine.handle(WaveformEvent.Tick(160L, trackLength)).frame)
        val initialOffset = requireNotNull(initial.trace).anchorOffset
        val aligned =
            evolved.copy(
                trace = requireNotNull(evolved.trace).copy(anchorOffset = initialOffset),
            )

        val initialImage = renderEditorScale(initial).image
        val evolvedImage = renderEditorScale(aligned).image

        assertTrue(
            pixelDifference(initialImage, evolvedImage) > MIN_PIXEL_DIFFERENCE,
            "the ECG geometry must scroll inside its moving perimeter window",
        )
    }

    @Test
    fun `production static pulse keeps its compact span after monitor render`() {
        val monitorConfig = WaveformConfig(amplitude = MAX_WAVEFORM_AMPLITUDE, intensity = MAX_WAVEFORM_INTENSITY)
        val monitor = renderEditorScale(WaveformFrame(config = monitorConfig))
        val staticPulse =
            renderEditorScale(
                WaveformFrame(
                    config = monitorConfig.copy(motion = WaveformMotion.STATIC_PULSE),
                    energy = 1f,
                    brightness = 1f,
                ),
            )

        assertTrue(monitor.result.track.signalSpan > staticPulse.result.track.signalSpan)
        assertEquals(STATIC_SIGNAL_SPAN, staticPulse.result.track.signalSpan, 0.1f)
    }

    @Test
    fun `comet alpha cuts ahead of the head and decays monotonically behind it`() {
        assertEquals(1f, WaveformPainter.cometAlpha(WaveformPainter.HEAD_PHASE), 0.001f)
        assertEquals(0f, WaveformPainter.cometAlpha(WaveformPainter.HEAD_PHASE + WaveformPainter.HEAD_LEAD + 0.01f))
        assertEquals(0f, WaveformPainter.cometAlpha(-0.01f))
        assertTrue(
            WaveformPainter.cometAlpha(WaveformPainter.R_PEAK_PHASE) >= 0.6f,
            "R complex must stay conspicuous inside the comet tail",
        )

        var previous = 0f
        for (step in 0..COMET_PROFILE_STEPS) {
            val phase = WaveformPainter.HEAD_PHASE * step / COMET_PROFILE_STEPS
            val alpha = WaveformPainter.cometAlpha(phase)
            assertTrue(alpha >= previous, "comet must brighten toward the head: alpha($phase)=$alpha < $previous")
            previous = alpha
        }
    }

    @Test
    fun `toward white lerps every channel to white`() {
        assertEquals(accent, WaveformPainter.towardWhite(accent, 0f))
        assertEquals(Color.WHITE, WaveformPainter.towardWhite(accent, 1f))
        val half = WaveformPainter.towardWhite(accent, 0.5f)
        assertTrue(half.red > accent.red && half.red < Color.WHITE.red)
    }

    @Test
    fun `comet trail paints behind the head and cuts off ahead of it`() {
        for (direction in WaveformDirection.entries) {
            val config = WaveformConfig(direction = direction, amplitude = 14, intensity = 100)
            val base = renderSolidBase()
            val flat = render(WaveformFrame(config = config))
            val track = flat.result.track
            val sign = direction.travelSign
            val beatSample = track.samples.first { it.normalY < -0.999f && abs(it.x - BEAT_X) < 2f }
            val rendered = render(frame(config, beatSample.distance - track.signalAnchorDistance))
            val headX =
                beatSample.x + sign * (WaveformPainter.HEAD_PHASE - WaveformPainter.R_PEAK_PHASE) * track.signalSpan

            val ahead =
                addedAlpha(base, rendered.image, topStrip(headX + sign * AHEAD_NEAR, headX + sign * AHEAD_FAR))
            val nearTail =
                addedAlpha(base, rendered.image, topStrip(headX - sign * TAIL_NEAR_END, headX - sign * TAIL_NEAR_START))
            val farTail =
                addedAlpha(base, rendered.image, topStrip(headX - sign * TAIL_FAR_END, headX - sign * TAIL_FAR_START))

            assertTrue(nearTail > farTail, "$direction trail must decay with distance: near=$nearTail far=$farTail")
            assertTrue(
                ahead < nearTail / MIN_TRAIL_CONTRAST,
                "$direction comet must cut off ahead of the head: ahead=$ahead near=$nearTail",
            )
        }
    }

    @Test
    fun `white-hot head leads while the tail keeps the accent color`() {
        val config = WaveformConfig(amplitude = 14, intensity = 100)
        val base = renderSolidBase()
        val flat = render(WaveformFrame(config = config))
        val track = flat.result.track
        val beatSample = track.samples.first { it.normalY < -0.999f && abs(it.x - BEAT_X) < 2f }
        val rendered = render(frame(config, beatSample.distance - track.signalAnchorDistance))
        val headX = beatSample.x + (WaveformPainter.HEAD_PHASE - WaveformPainter.R_PEAK_PHASE) * track.signalSpan

        val headColor =
            strongestChangedColorIn(base, rendered.image, topStrip(headX - HEAD_PROBE_HALF, headX + HEAD_PROBE_HALF))
        val tailColor =
            strongestChangedColorIn(
                base,
                rendered.image,
                topStrip(beatSample.x + TAIL_PROBE_START, beatSample.x + TAIL_PROBE_END),
            )

        assertTrue(
            headColor.red >= accent.red + MIN_HEAD_WHITENESS,
            "head core must overheat toward white: head=$headColor accent=$accent",
        )
        assertEquals(accent.red.toDouble(), tailColor.red.toDouble(), COLOR_TOLERANCE, "tail must stay accent")
        assertEquals(accent.green.toDouble(), tailColor.green.toDouble(), COLOR_TOLERANCE, "tail must stay accent")
        assertEquals(accent.blue.toDouble(), tailColor.blue.toDouble(), COLOR_TOLERANCE, "tail must stay accent")
    }

    @Test
    fun `trace history paints distinct complexes inside one moving window`() {
        val config = WaveformConfig(amplitude = 16, intensity = 100)
        val morphology = BeatMorphology.standard()
        val repeated =
            render(
                WaveformFrame(
                    config = config,
                    trace =
                        FrameTrace(
                            anchorOffset = 0f,
                            history = List(4) { morphology },
                        ),
                    brightness = 1f,
                    energy = 1f,
                ),
            )
        val varied =
            render(
                WaveformFrame(
                    config = config,
                    trace =
                        FrameTrace(
                            anchorOffset = 0f,
                            history = List(4) { seed -> BeatMorphology.random(Random(seed)) },
                        ),
                    brightness = 1f,
                    energy = 1f,
                ),
            )

        assertTrue(pixelDifference(repeated.image, varied.image) > MIN_PIXEL_DIFFERENCE)
    }

    @Test
    fun `signal offset preserves QRS notches outside content`() {
        val baseline = WaveformPainter.signalOffset(0f)
        val q = WaveformPainter.signalOffset(-0.09f)
        val s = WaveformPainter.signalOffset(-0.22f)
        val r = WaveformPainter.signalOffset(0.96f)
        val shortR = WaveformPainter.signalOffset(0.82f)
        val tallR = WaveformPainter.signalOffset(1f)

        assertTrue(q in 0f..<baseline, "Q must dip below the local outward baseline")
        assertEquals(0f, s, "S must return to the Solid frame without entering content")
        assertTrue(shortR < r && r < tallR, "different R amplitudes must produce different displacements")
        assertEquals(1f, tallR, "the tallest R variation may use the full configured amplitude")
    }

    private fun frame(
        config: WaveformConfig,
        center: Float,
        morphology: BeatMorphology = BeatMorphology.random(Random(42)),
    ): WaveformFrame =
        WaveformFrame(
            config = config,
            trace = FrameTrace(anchorOffset = center, history = listOf(morphology)),
            brightness = 1f,
            energy = 1f,
        )

    private fun render(
        frame: WaveformFrame,
        occupiedTopSpans: List<IntRange> = emptyList(),
        renderBounds: Rectangle = bounds,
        solidFrame: SolidFrameSpec = solidFrame(),
    ): Rendered {
        val image = BufferedImage(renderBounds.width, renderBounds.height, BufferedImage.TYPE_INT_ARGB)
        val graphics = image.createGraphics()
        val result =
            try {
                painter.paint(
                    graphics = graphics,
                    request =
                        WaveformPaintRequest(
                            bounds = renderBounds,
                            arcWidth = ARC_WIDTH,
                            accent = accent,
                            frame = frame,
                            occupiedTopSpans = occupiedTopSpans,
                            solidFrame = solidFrame,
                        ),
                )
            } finally {
                graphics.dispose()
            }
        return Rendered(image, result)
    }

    private fun renderEditorScale(frame: WaveformFrame): Rendered {
        val margin = WaveformPainter.marginFor(frame.config.amplitude, EDITOR_SOLID_WIDTH).toInt()
        val renderBounds =
            Rectangle(
                0,
                0,
                EDITOR_CONTENT_WIDTH + margin * 2,
                EDITOR_CONTENT_HEIGHT + margin * 2,
            )
        return render(
            frame = frame,
            occupiedTopSpans = listOf(0..EDITOR_LEFT_CHROME_END, EDITOR_RIGHT_CHROME_START until EDITOR_CONTENT_WIDTH),
            renderBounds = renderBounds,
            solidFrame =
                SolidFrameSpec(
                    bounds = Rectangle(margin, margin, EDITOR_CONTENT_WIDTH, EDITOR_CONTENT_HEIGHT),
                    style = GlowStyle.SHARP_NEON,
                    intensity = MAX_WAVEFORM_INTENSITY,
                    width = EDITOR_SOLID_WIDTH,
                ),
        )
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

    private fun visibleSignalBounds(
        base: BufferedImage,
        active: BufferedImage,
        region: Rectangle,
    ): Rectangle {
        var left = region.x + region.width
        var top = region.y + region.height
        var right = region.x - 1
        var bottom = region.y - 1
        for (y in region.y until region.y + region.height) {
            for (x in region.x until region.x + region.width) {
                if (alphaAt(active, x, y) - alphaAt(base, x, y) < VISIBLE_SIGNAL_ALPHA_DELTA) continue
                left = minOf(left, x)
                top = minOf(top, y)
                right = maxOf(right, x)
                bottom = maxOf(bottom, y)
            }
        }
        check(left <= right && top <= bottom) { "expected visible ECG pixels in $region" }
        return Rectangle(left, top, right - left + 1, bottom - top + 1)
    }

    private fun peakProfile(
        base: BufferedImage,
        active: BufferedImage,
        region: Rectangle,
        baselineY: Int,
    ): PeakProfile {
        val brightRows =
            (region.y until region.y + region.height).mapNotNull { y ->
                val width =
                    (region.x until region.x + region.width).count { x ->
                        alphaAt(active, x, y) - alphaAt(base, x, y) >= FLASH_ALPHA_DELTA
                    }
                if (width > 0) y to width else null
            }
        val top = brightRows.first()
        val height = baselineY - top.first
        val apexY = (top.first + APEX_DEPTH).coerceAtMost(baselineY)
        val middleY = top.first + height / 2
        return PeakProfile(
            height = height,
            apexWidth = brightRows.firstOrNull { it.first == apexY }?.second ?: 0,
            midWidth = brightRows.firstOrNull { it.first == middleY }?.second ?: 0,
        )
    }

    private fun addedAlpha(
        base: BufferedImage,
        active: BufferedImage,
        region: Rectangle,
    ): Long =
        (region.y until region.y + region.height).sumOf { y ->
            (region.x until region.x + region.width).sumOf { x ->
                (alphaAt(active, x, y) - alphaAt(base, x, y)).coerceAtLeast(0).toLong()
            }
        }

    private fun topStrip(
        firstX: Float,
        secondX: Float,
    ): Rectangle {
        val left = min(firstX, secondX).roundToInt().coerceAtLeast(0)
        val right =
            kotlin.math
                .max(firstX, secondX)
                .roundToInt()
                .coerceAtMost(WIDTH)
        return Rectangle(left, 0, (right - left).coerceAtLeast(0), HEIGHT / 3)
    }

    private fun strongestChangedColorIn(
        base: BufferedImage,
        active: BufferedImage,
        region: Rectangle,
    ): Color {
        val pixel =
            (region.y until region.y + region.height)
                .flatMap { y ->
                    (region.x until region.x + region.width).mapNotNull { x ->
                        active.getRGB(x, y).takeIf { it != base.getRGB(x, y) }
                    }
                }.maxBy { it ushr ALPHA_SHIFT and MAX_ALPHA }
        return Color(pixel, true)
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

    private data class PeakProfile(
        val height: Int,
        val apexWidth: Int,
        val midWidth: Int,
    )

    private fun WaveformTrack.sampleNearest(distance: Float): WaveformSample =
        samples.minBy { sample -> kotlin.math.abs(sample.distance - distance) }

    private companion object {
        const val WIDTH = 240
        const val HEIGHT = 160
        const val ARC_WIDTH = 16
        const val OUTWARD_PROBE = 11f
        const val RIGHT_FALLBACK_BAND = 40
        const val MIN_PIXEL_DIFFERENCE = 100
        const val SOLID_INTENSITY = 80
        const val SOLID_WIDTH = 4
        const val ALPHA_SHIFT = 24
        const val MAX_ALPHA = 0xFF
        const val COLOR_TOLERANCE = 2.0
        const val FLASH_HALF_WIDTH = 32
        const val FLASH_ALPHA_DELTA = 48
        const val APEX_DEPTH = 2
        const val MAX_APEX_WIDTH = 5
        const val MIN_APEX_EXPANSION = 2
        const val MIN_ACTIVE_HEIGHT_FRACTION = 0.75
        const val MIN_FLASH_ALPHA_RATIO = 2.5
        const val COMET_PROFILE_STEPS = 72
        const val BEAT_X = 120f
        const val AHEAD_NEAR = 10f
        const val AHEAD_FAR = 30f
        const val TAIL_NEAR_START = 10f
        const val TAIL_NEAR_END = 30f
        const val TAIL_FAR_START = 100f
        const val TAIL_FAR_END = 120f
        const val MIN_TRAIL_CONTRAST = 5
        const val HEAD_PROBE_HALF = 8f
        const val TAIL_PROBE_START = 30f
        const val TAIL_PROBE_END = 50f
        const val MIN_HEAD_WHITENESS = 40
        const val EDITOR_CONTENT_WIDTH = 1331
        const val EDITOR_CONTENT_HEIGHT = 800
        const val EDITOR_SOLID_WIDTH = 3
        const val EDITOR_LEFT_CHROME_END = 240
        const val EDITOR_RIGHT_CHROME_START = 1190
        const val VISIBLE_SIGNAL_ALPHA_DELTA = 24
        const val MIN_EDITOR_SIGNAL_WIDTH_FRACTION = 0.33
        const val MAX_MONITOR_PEAK_DELTA = 1
        const val STATIC_SIGNAL_SPAN = 220f
    }
}
