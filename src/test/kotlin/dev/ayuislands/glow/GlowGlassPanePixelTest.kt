package dev.ayuislands.glow

import dev.ayuislands.glow.waveform.BeatMorphology
import dev.ayuislands.glow.waveform.FrameTrace
import dev.ayuislands.glow.waveform.TravelDirection
import dev.ayuislands.glow.waveform.WaveformConfig
import dev.ayuislands.glow.waveform.WaveformEdge
import dev.ayuislands.glow.waveform.WaveformFrame
import dev.ayuislands.glow.waveform.WaveformPaintRequest
import dev.ayuislands.glow.waveform.WaveformPainter
import dev.ayuislands.glow.waveform.WaveformRenderPlan
import dev.ayuislands.glow.waveform.brightnessAt
import java.awt.Color
import java.awt.event.ActionEvent
import java.awt.image.BufferedImage
import javax.swing.SwingUtilities
import javax.swing.Timer
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pixel-level locks for glow placements rendered through the real
 * [GlowGlassPane.paint] path: SIDE_EDGES must light BOTH vertical edges as
 * straight full-height strips — uniform from top to bottom, with no rounded
 * corner hooks — and nothing else.
 */
class GlowGlassPanePixelTest {
    private companion object {
        const val WIDTH = 400
        const val HEIGHT = 300
        const val ALPHA_FLOOR = 8
        const val ALPHA_SHIFT = 24
    }

    private fun paintedImage(
        placement: GlowPlacement,
        width: Int = WIDTH,
        height: Int = HEIGHT,
    ): BufferedImage {
        val pane =
            GlowGlassPane(
                glowColor = Color(0xFF8F40),
                glowStyle = GlowStyle.SOFT,
                glowIntensity = 80,
                glowWidth = 12,
                isEditorOverlay = false,
                glowPlacement = placement,
            )
        pane.setSize(width, height)
        // paintComponent guards on fade-in alpha; tests bypass the animation timer.
        GlowGlassPane::class.java.getDeclaredField("fadeAlpha").apply {
            isAccessible = true
            setFloat(pane, 1.0f)
        }

        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        val graphics = image.createGraphics()
        try {
            pane.paint(graphics)
        } finally {
            graphics.dispose()
        }
        return image
    }

    private fun BufferedImage.columnHasGlow(x: Int): Boolean =
        (0 until height).any { y -> (getRGB(x, y) ushr 24) and 0xFF > ALPHA_FLOOR }

    private fun BufferedImage.rowHasGlow(y: Int): Boolean =
        (0 until width).any { x -> (getRGB(x, y) ushr 24) and 0xFF > ALPHA_FLOOR }

    @Test
    fun `side edges paints both left and right vertical strips`() {
        val image = paintedImage(GlowPlacement.SIDE_EDGES)

        assertTrue(image.columnHasGlow(2), "left edge must glow")
        assertTrue(image.columnHasGlow(WIDTH - 3), "right edge must glow")
    }

    @Test
    fun `side edges leaves the horizontal middle unpainted`() {
        val image = paintedImage(GlowPlacement.SIDE_EDGES)

        val middleGlow = (0 until HEIGHT).any { y -> (image.getRGB(WIDTH / 2, y) ushr 24) and 0xFF > ALPHA_FLOOR }
        assertTrue(!middleGlow, "center column must stay clear so only side edges light up")
    }

    @Test
    fun `side edges strips run straight from top to bottom without corner hooks`() {
        val image = paintedImage(GlowPlacement.SIDE_EDGES)

        // A straight strip means the edge column carries the same alpha at the
        // very top, the middle, and the very bottom — a clipped rounded frame
        // would bend away from the edge near the corners.
        val top = (image.getRGB(2, 0) ushr 24) and 0xFF
        val middle = (image.getRGB(2, HEIGHT / 2) ushr 24) and 0xFF
        val bottom = (image.getRGB(2, HEIGHT - 1) ushr 24) and 0xFF
        assertTrue(top == middle && middle == bottom, "edge column alpha must be uniform: $top/$middle/$bottom")
        assertTrue(middle > ALPHA_FLOOR, "edge column must actually glow")

        // No horizontal spill beyond the strip at the corners (the old clipped
        // frame leaked arc segments there).
        val cornerSpill = (image.getRGB(WIDTH / 2, 1) ushr 24) and 0xFF
        assertTrue(cornerSpill <= ALPHA_FLOOR, "top row must stay clear outside the vertical strips")
    }

    @Test
    fun `narrow odd-width overlay paints its center column once, not as a bright seam`() {
        // Strips meet in the middle on a 7px-wide overlay; the shared center
        // column must carry single-pass alpha, so the falloff stays monotonic
        // toward the middle instead of spiking where the passes overlap.
        val image = paintedImage(GlowPlacement.SIDE_EDGES, width = 7, height = 50)

        val nearEdge = (image.getRGB(2, 25) ushr 24) and 0xFF
        val center = (image.getRGB(3, 25) ushr 24) and 0xFF
        assertTrue(
            center <= nearEdge,
            "center column ($center) must not outshine the column nearer the edge ($nearEdge)",
        )
    }

    @Test
    fun `island placement paints the full frame including top and bottom`() {
        val image = paintedImage(GlowPlacement.ISLAND)

        assertTrue(image.rowHasGlow(2), "top edge must glow on the full frame")
        assertTrue(image.rowHasGlow(HEIGHT - 3), "bottom edge must glow on the full frame")
    }

    @Test
    fun `waveform shape dispatch paints its frame through the glass pane`() {
        val config = WaveformConfig(amplitude = 16, intensity = 100)
        val flatPane = waveformPane(config)
        val flat = paint(flatPane)
        val trackLength = flatPane.waveformTrackLength
        flatPane.showWaveformFrame(
            WaveformFrame(
                direction = TravelDirection.CLOCKWISE,
                config = config,
                trace =
                    FrameTrace(
                        anchorOffset = trackLength * 0.25f,
                        history = listOf(BeatMorphology.random(Random(42))),
                    ),
            ),
        )

        val beat = paint(flatPane)

        assertTrue(pixelDifference(flat, beat) > 100, "waveform frame must change production glass-pane pixels")
    }

    @Test
    fun `waveform paint refreshes editor top spans from its host provider`() {
        val pane = waveformPane(WaveformConfig())
        var currentSpans = listOf(0..80)
        var nowMs = 0L
        pane.topSpansProvider = { currentSpans }
        pane.timeSource = { nowMs }
        val captured = mutableListOf<List<IntRange>>()
        installWaveformPainter(
            pane,
            object : WaveformPainter() {
                override fun prepare(request: WaveformPaintRequest): WaveformRenderPlan {
                    captured += request.occupiedTopSpans
                    return super.prepare(request)
                }
            },
        )

        paint(pane)
        currentSpans = listOf(0..160)
        nowMs = 249L
        paint(pane)
        nowMs = 250L
        paint(pane)

        assertEquals(listOf(listOf(0..80), listOf(0..160)), captured)
    }

    @Test
    fun `waveform paint passes inward edges to the painter`() {
        val pane = waveformPane(WaveformConfig())
        val expected = setOf(WaveformEdge.TOP, WaveformEdge.RIGHT)
        pane.waveformInwardEdges = expected
        var captured: Set<WaveformEdge>? = null
        installWaveformPainter(
            pane,
            object : WaveformPainter() {
                override fun prepare(request: WaveformPaintRequest): WaveformRenderPlan {
                    captured = request.inwardEdges
                    return super.prepare(request)
                }
            },
        )

        paint(pane)

        assertEquals(expected, captured)
    }

    @Test
    fun `waveform top span refresh failure is contained and recorded once`() {
        val pane = waveformPane(WaveformConfig())
        pane.topSpansProvider = { error("tab hierarchy changed") }

        assertTrue(alphaSum(paint(pane)) > 0L)
        assertTrue(readTopSpansFailureLogged(pane))
        assertTrue(alphaSum(paint(pane)) > 0L)
    }

    @Test
    fun `waveform timer follows engine start stop and resume directives`() {
        val pane = waveformPane(WaveformConfig())
        pane.activateWaveform(powerSaveEnabled = false)

        pane.onWaveformKeystroke(nowMs = 0L)
        assertNotNull(readWaveformTimer(pane))

        pane.deactivateWaveform()
        assertNull(readWaveformTimer(pane))
        assertNull(readWaveformFrame(pane))

        pane.activateWaveform(powerSaveEnabled = false)
        assertNotNull(readWaveformTimer(pane))
        pane.deactivateWaveform()
    }

    @Test
    fun `hidden waveform timer advances without preparing render plans`() {
        SwingUtilities.invokeAndWait {
            val pane = waveformPane(WaveformConfig(loopSeconds = 1.6f))
            var nowMs = 0L
            pane.timeSource = { nowMs }
            pane.activateWaveform(powerSaveEnabled = false)
            val timer = assertNotNull(readWaveformTimer(pane) as? Timer)
            timer.stop()
            var prepareCount = 0
            installWaveformPainter(
                pane,
                object : WaveformPainter() {
                    override fun prepare(request: WaveformPaintRequest): WaveformRenderPlan {
                        prepareCount++
                        return super.prepare(request)
                    }
                },
            )

            fireTimer(timer)
            val firstHiddenOffset =
                assertNotNull((readWaveformFrame(pane) as? WaveformFrame)?.trace).anchorOffset
            nowMs = 16L
            fireTimer(timer)
            val secondHiddenOffset =
                assertNotNull((readWaveformFrame(pane) as? WaveformFrame)?.trace).anchorOffset

            assertEquals(0, prepareCount, "hidden ticks must update state without preparing paint paths")

            pane.addNotify()
            try {
                assertTrue(pane.isShowing)
                nowMs = 32L
                fireTimer(timer)
                val visibleOffset =
                    assertNotNull((readWaveformFrame(pane) as? WaveformFrame)?.trace).anchorOffset

                assertEquals(1, prepareCount, "the first visible tick must prepare the current frame")
                assertEquals(
                    secondHiddenOffset - firstHiddenOffset,
                    visibleOffset - secondHiddenOffset,
                    0.001f,
                    "visibility changes must not create a delayed-time phase jump",
                )
            } finally {
                pane.removeNotify()
            }
        }
    }

    @Test
    fun `configuring waveform keeps an inactive overlay hidden`() {
        val pane =
            GlowGlassPane(
                glowColor = Color(0xFF8F40),
                glowStyle = GlowStyle.SOFT,
                glowIntensity = 80,
                glowWidth = 12,
            ).also { it.setSize(WIDTH, HEIGHT) }

        pane.configureWaveform(GlowShape.WAVEFORM, WaveformConfig())

        assertEquals(0L, alphaSum(paint(pane)))
    }

    @Test
    fun `resting waveform brightness is stable before and after an envelope`() {
        val config = WaveformConfig()
        val pane = waveformPane(config)
        val initial = paint(pane)

        pane.showWaveformFrame(
            WaveformFrame(
                direction = TravelDirection.CLOCKWISE,
                config = config,
                energy = 0f,
                brightness = config.brightnessAt(0f),
            ),
        )

        assertEquals(0, pixelDifference(initial, paint(pane)))
    }

    @Test
    fun `reconfigure clears a frame rendered with old geometry`() {
        val oldConfig = WaveformConfig(amplitude = 6)
        val pane = waveformPane(oldConfig)
        pane.showWaveformFrame(WaveformFrame(direction = TravelDirection.CLOCKWISE, config = oldConfig))

        pane.configureWaveform(GlowShape.WAVEFORM, WaveformConfig(amplitude = 16))

        val frame = assertNotNull(readWaveformFrame(pane) as? WaveformFrame)
        assertEquals(16, frame.config.amplitude)
    }

    @Test
    fun `plan preparation failure falls back to solid and stops waveform timer`() {
        val pane = waveformPane(WaveformConfig())
        installWaveformPainter(
            pane,
            object : WaveformPainter() {
                override fun prepare(request: WaveformPaintRequest): WaveformRenderPlan =
                    throw UnsupportedOperationException("paint failed")
            },
        )
        pane.activateWaveform(powerSaveEnabled = false)
        pane.onWaveformKeystroke(nowMs = 0L)

        val fallback = paint(pane)

        assertNull(readWaveformTimer(pane))
        assertTrue(readWaveformFailed(pane))
        assertFalse(pane.isWaveform, "failed waveform must switch to the solid renderer")
        assertTrue(pane.usesWaveformBounds, "solid fallback must retain expanded bounds around the host perimeter")
        assertTrue(alphaSum(fallback) > 0L, "solid fallback must remain visible")
    }

    @Test
    fun `prepared plan paint failure falls back to solid`() {
        val pane = waveformPane(WaveformConfig())
        installWaveformPainter(
            pane,
            object : WaveformPainter() {
                override fun paint(
                    graphics: java.awt.Graphics2D,
                    plan: WaveformRenderPlan,
                ) = throw UnsupportedOperationException("paint failed")
            },
        )
        pane.activateWaveform(powerSaveEnabled = true)

        val fallback = paint(pane)

        assertTrue(readWaveformFailed(pane))
        assertFalse(pane.isWaveform)
        assertNull(readWaveformTimer(pane))
        assertTrue(alphaSum(fallback) > 0L, "solid fallback must remain visible")
    }

    @Test
    fun `unchanged waveform paint reuses its render plan`() {
        val pane = waveformPane(WaveformConfig())
        var prepareCount = 0
        installWaveformPainter(
            pane,
            object : WaveformPainter() {
                override fun prepare(request: WaveformPaintRequest): WaveformRenderPlan {
                    prepareCount++
                    return super.prepare(request)
                }
            },
        )

        paint(pane)
        paint(pane)
        assertEquals(1, prepareCount, "an unchanged Swing repaint must reuse the prepared paths")

        pane.showWaveformFrame(
            WaveformFrame(direction = TravelDirection.CLOCKWISE, config = WaveformConfig(), energy = 1f),
        )
        assertEquals(2, prepareCount, "a new frame must prepare its paths before scheduling the dirty region")
        paint(pane)
        assertEquals(2, prepareCount, "painting the scheduled frame must reuse the prepared paths")
    }

    @Test
    fun `failed waveform retries only after its configuration changes`() {
        val config = WaveformConfig(amplitude = 8)
        val pane = waveformPane(config)
        installFailingPainter(pane)

        pane.activateWaveform(powerSaveEnabled = false)
        assertTrue(readWaveformFailed(pane))

        installWaveformPainter(pane, WaveformPainter())
        pane.configureWaveform(GlowShape.WAVEFORM, config)
        assertFalse(pane.isWaveform, "an unchanged refresh must not spin on a deterministic renderer failure")

        pane.configureWaveform(GlowShape.WAVEFORM, config.copy(amplitude = 9))
        pane.activateWaveform(powerSaveEnabled = true)
        assertTrue(pane.isWaveform, "an explicit configuration change must recover the waveform renderer")
        assertFalse(readWaveformFailed(pane))
        assertTrue(alphaSum(paint(pane)) > 0L)
        pane.deactivateWaveform()
    }

    @Test
    fun `solid to waveform transition retries a failed renderer`() {
        val config = WaveformConfig()
        val pane = waveformPane(config)
        installFailingPainter(pane)
        pane.activateWaveform(powerSaveEnabled = false)
        assertTrue(readWaveformFailed(pane))

        installWaveformPainter(pane, WaveformPainter())
        pane.configureWaveform(GlowShape.SOLID, config)
        pane.configureWaveform(GlowShape.WAVEFORM, config)
        pane.activateWaveform(powerSaveEnabled = true)

        assertTrue(pane.isWaveform, "turning waveform mode back on must create a fresh engine")
        assertFalse(readWaveformFailed(pane))
        assertTrue(alphaSum(paint(pane)) > 0L)
        pane.deactivateWaveform()
    }

    private fun waveformPane(config: WaveformConfig): GlowGlassPane =
        GlowGlassPane(
            glowColor = Color(0xFF8F40),
            glowStyle = GlowStyle.SOFT,
            glowIntensity = 80,
            glowWidth = 12,
            isEditorOverlay = false,
            glowPlacement = GlowPlacement.ISLAND,
        ).also { pane ->
            pane.setSize(WIDTH, HEIGHT)
            pane.configureWaveform(GlowShape.WAVEFORM, config)
            GlowGlassPane::class.java.getDeclaredField("fadeAlpha").apply {
                isAccessible = true
                setFloat(pane, 1.0f)
            }
        }

    private fun paint(pane: GlowGlassPane): BufferedImage {
        val image = BufferedImage(pane.width, pane.height, BufferedImage.TYPE_INT_ARGB)
        val graphics = image.createGraphics()
        try {
            pane.paint(graphics)
        } finally {
            graphics.dispose()
        }
        return image
    }

    private fun pixelDifference(
        first: BufferedImage,
        second: BufferedImage,
    ): Int =
        (0 until first.height).sumOf { y ->
            (0 until first.width).count { x -> first.getRGB(x, y) != second.getRGB(x, y) }
        }

    private fun alphaSum(image: BufferedImage): Long =
        (0 until image.height).sumOf { y ->
            (0 until image.width).sumOf { x -> (image.getRGB(x, y) ushr ALPHA_SHIFT).toLong() }
        }

    private fun installWaveformPainter(
        pane: GlowGlassPane,
        painter: WaveformPainter,
    ) {
        GlowGlassPane::class.java.getDeclaredField("waveformPainter").apply {
            isAccessible = true
            set(pane, painter)
        }
        GlowGlassPane::class.java.getDeclaredField("waveformPlan").apply {
            isAccessible = true
            set(pane, null)
        }
    }

    private fun installFailingPainter(pane: GlowGlassPane) {
        installWaveformPainter(
            pane,
            object : WaveformPainter() {
                override fun prepare(request: WaveformPaintRequest): WaveformRenderPlan =
                    throw UnsupportedOperationException("paint failed")
            },
        )
    }

    private fun readWaveformFailed(pane: GlowGlassPane): Boolean =
        GlowGlassPane::class.java.getDeclaredField("waveformFailed").let { field ->
            field.isAccessible = true
            field.getBoolean(pane)
        }

    private fun readTopSpansFailureLogged(pane: GlowGlassPane): Boolean =
        GlowGlassPane::class.java.getDeclaredField("topSpansFailureLogged").let { field ->
            field.isAccessible = true
            field.getBoolean(pane)
        }

    private fun readWaveformTimer(pane: GlowGlassPane): Any? =
        GlowGlassPane::class.java.getDeclaredField("waveformTimer").let { field ->
            field.isAccessible = true
            field.get(pane)
        }

    private fun readWaveformFrame(pane: GlowGlassPane): Any? =
        GlowGlassPane::class.java.getDeclaredField("waveformFrame").let { field ->
            field.isAccessible = true
            field.get(pane)
        }

    private fun fireTimer(timer: Timer) {
        timer.actionListeners.single().actionPerformed(
            ActionEvent(timer, ActionEvent.ACTION_PERFORMED, "tick"),
        )
    }
}
