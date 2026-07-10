package dev.ayuislands.settings

import dev.ayuislands.glow.GlowShape
import dev.ayuislands.glow.GlowStyle
import dev.ayuislands.glow.waveform.WaveformConfig
import java.awt.Color
import java.awt.Rectangle
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
    fun `waveform preview paints only outside the solid content bounds`() {
        val hidden = GlowGroupPanel()
        hidden.setSize(WIDTH, HEIGHT)
        hidden.updatePreview(preview(GlowShape.WAVEFORM).copy(visible = false))
        val withoutWaveform = render(hidden)

        val visible = GlowGroupPanel()
        visible.setSize(WIDTH, HEIGHT)
        visible.updatePreview(preview(GlowShape.WAVEFORM))
        val withWaveform = render(visible)
        val insets = visible.insets
        val contentBounds =
            Rectangle(
                insets.left,
                insets.top,
                WIDTH - insets.left - insets.right,
                HEIGHT - insets.top - insets.bottom,
            )

        assertEquals(0, pixelDifference(withoutWaveform, withWaveform, contentBounds))
        assertTrue(pixelDifference(withoutWaveform, withWaveform) > MIN_PIXEL_DIFFERENCE)
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
        bounds: Rectangle = Rectangle(0, 0, first.width, first.height),
    ): Int =
        (bounds.y until bounds.y + bounds.height).sumOf { y ->
            (bounds.x until bounds.x + bounds.width).count { x -> first.getRGB(x, y) != second.getRGB(x, y) }
        }

    private companion object {
        const val WIDTH = 420
        const val HEIGHT = 300
        const val MIN_PIXEL_DIFFERENCE = 100
    }
}
