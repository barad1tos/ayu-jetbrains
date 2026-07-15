package dev.ayuislands.glow

import dev.ayuislands.glow.waveform.BeatMorphology
import dev.ayuislands.glow.waveform.FrameTrace
import dev.ayuislands.glow.waveform.WaveformConfig
import dev.ayuislands.glow.waveform.WaveformEdge
import dev.ayuislands.glow.waveform.WaveformFrame
import dev.ayuislands.glow.waveform.WaveformMotion
import dev.ayuislands.glow.waveform.WaveformPaintRequest
import dev.ayuislands.glow.waveform.WaveformPaintResult
import dev.ayuislands.glow.waveform.WaveformPainter
import dev.ayuislands.glow.waveform.WaveformTrack
import dev.ayuislands.glow.waveform.brightnessAt
import java.awt.Color
import java.awt.image.BufferedImage
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
                override fun paint(
                    graphics: java.awt.Graphics2D,
                    request: WaveformPaintRequest,
                ): WaveformPaintResult {
                    captured += request.occupiedTopSpans
                    return WaveformPaintResult(WaveformTrack(emptyList(), 0f, 0f, 0f), emptyList())
                }
            },
        )

        paint(pane)
        currentSpans = listOf(0..160)
        nowMs = 249L
        paint(pane)
        nowMs = 250L
        paint(pane)

        assertEquals(listOf(listOf(0..80), listOf(0..80), listOf(0..160)), captured)
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
                override fun paint(
                    graphics: java.awt.Graphics2D,
                    request: WaveformPaintRequest,
                ): WaveformPaintResult {
                    captured = request.inwardEdges
                    return WaveformPaintResult(WaveformTrack(emptyList(), 0f, 0f, 0f), emptyList())
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
    fun `waveform timer follows engine start and stop directives`() {
        val pane = waveformPane(WaveformConfig())
        pane.activateWaveform(powerSaveEnabled = false)

        pane.onWaveformKeystroke(nowMs = 0L)
        assertNotNull(readWaveformTimer(pane))

        pane.deactivateWaveform()
        assertNull(readWaveformTimer(pane))
        assertNull(readWaveformFrame(pane))
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
    fun `static idle brightness is stable before and after an envelope`() {
        val config = WaveformConfig(motion = WaveformMotion.STATIC_PULSE)
        val pane = waveformPane(config)
        val initial = paint(pane)

        pane.showWaveformFrame(
            WaveformFrame(
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
        pane.showWaveformFrame(WaveformFrame(config = oldConfig))

        pane.configureWaveform(GlowShape.WAVEFORM, WaveformConfig(amplitude = 16))

        val frame = assertNotNull(readWaveformFrame(pane) as? WaveformFrame)
        assertEquals(16, frame.config.amplitude)
    }

    @Test
    fun `paint failure falls back to solid and stops waveform timer`() {
        val pane = waveformPane(WaveformConfig())
        installWaveformPainter(
            pane,
            object : WaveformPainter() {
                override fun paint(
                    graphics: java.awt.Graphics2D,
                    request: WaveformPaintRequest,
                ): WaveformPaintResult = throw UnsupportedOperationException("paint failed")
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
}
