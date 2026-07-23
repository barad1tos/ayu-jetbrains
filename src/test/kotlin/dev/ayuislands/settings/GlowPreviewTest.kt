package dev.ayuislands.settings

import dev.ayuislands.glow.GlowShape
import dev.ayuislands.glow.GlowStyle
import dev.ayuislands.glow.waveform.WaveformBaseline
import dev.ayuislands.glow.waveform.WaveformConfig
import dev.ayuislands.glow.waveform.WaveformFrame
import dev.ayuislands.glow.waveform.WaveformMovement
import java.awt.Color
import java.awt.Rectangle
import java.awt.image.BufferedImage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GlowPreviewTest {
    @Test
    fun `waveform preview is deterministic and differs from solid preview`() {
        val panel = GlowGroupPanel()
        panel.setSize(WIDTH, HEIGHT)
        panel.updatePreview(preview(GlowShape.SOLID))
        val solid = render(panel)

        panel.updatePreview(preview(GlowShape.WAVEFORM))
        val firstWaveform = render(panel)
        val secondWaveform = render(panel)

        assertTrue(pixelDifference(solid, firstWaveform) > MIN_PIXEL_DIFFERENCE)
        assertEquals(0, pixelDifference(firstWaveform, secondWaveform))
    }

    @Test
    fun `shape changes preserve preview content insets`() {
        val amplitude = 14
        val panel = GlowGroupPanel()
        panel.updatePreview(preview(GlowShape.SOLID))
        val solidInsets = panel.insets
        panel.updatePreview(preview(GlowShape.WAVEFORM).copy(waveformConfig = WaveformConfig(amplitude = amplitude)))

        assertEquals(
            solidInsets,
            panel.insets,
            "shape changes must not move settings content",
        )
    }

    @Test
    fun `waveform preview keeps the central settings content clear`() {
        val hidden = GlowGroupPanel()
        hidden.setSize(WIDTH, HEIGHT)
        hidden.updatePreview(preview(GlowShape.WAVEFORM).copy(visible = false))
        val withoutWaveform = render(hidden)

        val visible = GlowGroupPanel()
        visible.setSize(WIDTH, HEIGHT)
        visible.updatePreview(preview(GlowShape.WAVEFORM))
        val withWaveform = render(visible)
        val contentBounds =
            Rectangle(
                PREVIEW_SAFE_INSET,
                PREVIEW_SAFE_INSET,
                WIDTH - PREVIEW_SAFE_INSET * 2,
                HEIGHT - PREVIEW_SAFE_INSET * 2,
            )

        assertEquals(0, pixelDifference(withoutWaveform, withWaveform, contentBounds))
        assertTrue(pixelDifference(withoutWaveform, withWaveform) > MIN_PIXEL_DIFFERENCE)
    }

    @Test
    fun `centered waveform remains visible inside the preview border`() {
        val panel = GlowGroupPanel()
        panel.setSize(WIDTH, HEIGHT)
        val signalOnly =
            preview(GlowShape.WAVEFORM).copy(
                intensity = 0,
                waveformConfig = preview(GlowShape.WAVEFORM).waveformConfig.copy(baseline = WaveformBaseline.OUTSIDE),
            )
        panel.updatePreview(signalOnly.copy(visible = false))
        val hidden = render(panel)

        panel.updatePreview(signalOnly)
        val outside = render(panel)
        panel.updatePreview(
            signalOnly.copy(
                waveformConfig =
                    WaveformConfig(
                        amplitude = 16,
                        intensity = 100,
                        baseline = WaveformBaseline.CENTERED,
                        traceDensity = 4,
                    ),
            ),
        )
        val centered = render(panel)
        val outsidePixels = pixelDifference(hidden, outside)
        val centeredPixels = pixelDifference(hidden, centered)
        val contentBounds =
            Rectangle(
                PREVIEW_SAFE_INSET,
                PREVIEW_SAFE_INSET,
                WIDTH - PREVIEW_SAFE_INSET * 2,
                HEIGHT - PREVIEW_SAFE_INSET * 2,
            )

        assertTrue(pixelDifference(outside, centered) > MIN_PIXEL_DIFFERENCE)
        assertEquals(0, pixelDifference(hidden, centered, contentBounds))
        assertTrue(
            centeredPixels >= outsidePixels * MIN_CENTERED_VISIBILITY,
            "centered preview must remain visible: " +
                "outside=$outsidePixels centered=$centeredPixels",
        )
    }

    @Test
    fun `maximum density changes the live waveform preview before apply`() {
        val panel = GlowGroupPanel()
        panel.setSize(WIDTH, HEIGHT)
        val waveform = preview(GlowShape.WAVEFORM)
        panel.updatePreview(waveform)
        val defaultDensity = render(panel)

        panel.updatePreview(waveform.copy(waveformConfig = waveform.waveformConfig.copy(traceDensity = 4)))
        val maximumDensity = render(panel)

        assertTrue(pixelDifference(defaultDensity, maximumDensity) > MIN_PIXEL_DIFFERENCE)
    }

    @Test
    fun `trace length changes the live waveform preview before apply`() {
        val panel = GlowGroupPanel()
        panel.setSize(WIDTH, HEIGHT)
        val waveform = preview(GlowShape.WAVEFORM)
        panel.updatePreview(waveform)
        val compactTrace = render(panel)

        panel.updatePreview(waveform.copy(waveformConfig = waveform.waveformConfig.copy(traceLength = 640)))
        val longTrace = render(panel)

        assertTrue(pixelDifference(compactTrace, longTrace) > MIN_PIXEL_DIFFERENCE)
    }

    @Test
    fun `waveform preview visibly advances with the configured perimeter loop`() {
        val panel = GlowGroupPanel()
        panel.setSize(WIDTH, HEIGHT)
        panel.updatePreview(
            preview(GlowShape.WAVEFORM).copy(
                waveformConfig =
                    WaveformConfig(
                        baseline = WaveformBaseline.CENTERED,
                        amplitude = 24,
                        intensity = 100,
                        loopSeconds = 2f,
                    ),
            ),
        )
        panel.advanceWaveformPreview(0L)
        val initial = render(panel)

        panel.advanceWaveformPreview(500L)
        val advanced = render(panel)

        assertTrue(
            pixelDifference(initial, advanced) > MIN_PIXEL_DIFFERENCE,
            "The Settings ECG preview must move before Apply",
        )
    }

    @Test
    fun `chaotic preview keeps its seeded direction when animation starts`() {
        val panel = GlowGroupPanel()
        panel.setSize(WIDTH, HEIGHT)
        val chaotic =
            preview(GlowShape.WAVEFORM).copy(
                waveformConfig = WaveformConfig(movement = WaveformMovement.CHAOTIC),
            )
        panel.updatePreview(chaotic.copy(shape = GlowShape.SOLID))
        val restingDirection = waveformFrame(panel).direction

        panel.updatePreview(chaotic)
        panel.advanceWaveformPreview(0L)
        val activatedDirection = waveformFrame(panel).direction
        panel.advanceWaveformPreview(500L)
        val tickDirection = waveformFrame(panel).direction

        assertEquals(restingDirection, activatedDirection)
        assertEquals(restingDirection, tickDirection)
    }

    @Test
    fun `waveform preview timer stops while hidden or in power save mode`() {
        val panel = GlowGroupPanel()
        panel.updatePreview(preview(GlowShape.WAVEFORM))

        try {
            panel.syncPreviewAnimation(showing = true, powerSaveEnabled = false)
            assertTrue(panel.isPreviewAnimating)

            panel.syncPreviewAnimation(showing = false, powerSaveEnabled = false)
            assertFalse(panel.isPreviewAnimating)

            panel.syncPreviewAnimation(showing = true, powerSaveEnabled = true)
            assertFalse(panel.isPreviewAnimating)
        } finally {
            panel.syncPreviewAnimation(showing = false, powerSaveEnabled = false)
        }
    }

    private fun preview(shape: GlowShape): GlowPreview =
        GlowPreview(
            shape = shape,
            style = GlowStyle.SHARP_NEON,
            intensity = 80,
            width = 10,
            color = Color(0x5CCFE6),
            visible = true,
            waveformConfig = WaveformConfig(amplitude = 16, intensity = 100),
        )

    private fun render(panel: GlowGroupPanel): BufferedImage {
        val image = BufferedImage(panel.width, panel.height, BufferedImage.TYPE_INT_ARGB)
        val graphics = image.createGraphics()
        try {
            panel.paint(graphics)
        } finally {
            graphics.dispose()
        }
        return image
    }

    private fun waveformFrame(panel: GlowGroupPanel): WaveformFrame {
        val field = GlowGroupPanel::class.java.getDeclaredField("waveformFrame")
        field.isAccessible = true
        return field.get(panel) as WaveformFrame
    }

    private fun pixelDifference(
        first: BufferedImage,
        second: BufferedImage,
        bounds: Rectangle = Rectangle(0, 0, first.width, first.height),
    ): Int =
        (bounds.y until bounds.y + bounds.height).sumOf { y ->
            (bounds.x until bounds.x + bounds.width).count { x -> first.getRGB(x, y) != second.getRGB(x, y) }
        }

    private companion object {
        const val WIDTH = 420
        const val HEIGHT = 300
        const val MIN_PIXEL_DIFFERENCE = 100
        const val MIN_CENTERED_VISIBILITY = 0.75
        const val PREVIEW_SAFE_INSET = 72
    }
}
