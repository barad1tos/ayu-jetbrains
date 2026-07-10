package dev.ayuislands.settings

import dev.ayuislands.glow.GlowShape
import dev.ayuislands.glow.GlowStyle
import dev.ayuislands.glow.waveform.WaveformConfig
import dev.ayuislands.glow.waveform.WaveformPainter
import java.awt.Color
import java.awt.image.BufferedImage
import kotlin.test.Test
import kotlin.test.assertEquals
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
    fun `waveform preview reserves its paint band outside settings content`() {
        val amplitude = 14
        val panel = GlowGroupPanel()
        panel.updatePreview(preview(GlowShape.WAVEFORM).copy(waveformConfig = WaveformConfig(amplitude = amplitude)))
        val safeInset = WaveformPainter.marginFor(amplitude).toInt() + CONTENT_CLEARANCE

        assertTrue(
            panel.insets.top >= safeInset,
            "settings content must start below the waveform baseline and inward bloom",
        )
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

    private fun pixelDifference(
        first: BufferedImage,
        second: BufferedImage,
    ): Int =
        (0 until first.height).sumOf { y ->
            (0 until first.width).count { x -> first.getRGB(x, y) != second.getRGB(x, y) }
        }

    private companion object {
        const val WIDTH = 420
        const val HEIGHT = 300
        const val MIN_PIXEL_DIFFERENCE = 100
        const val CONTENT_CLEARANCE = 13
    }
}
