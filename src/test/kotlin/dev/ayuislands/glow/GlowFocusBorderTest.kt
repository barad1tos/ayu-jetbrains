package dev.ayuislands.glow

import com.intellij.util.ui.JBUI
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import java.awt.Color
import java.awt.Font
import java.awt.Rectangle
import java.awt.geom.AffineTransform
import java.awt.image.BufferedImage
import javax.swing.JTextField
import javax.swing.SwingUtilities
import javax.swing.UIManager
import javax.swing.border.Border
import javax.swing.border.MatteBorder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class GlowFocusBorderTest {
    @Test
    fun `unfocused rendering is exactly the original border`() =
        onBorderEdt {
            val field = BorderFocusField()
            val original = MatteBorder(2, 3, 4, 5, Color.MAGENTA)
            assertEquals(
                paintBorder(field, original).pixels(),
                paintBorder(field, GlowFocusBorder(original, Color.ORANGE, GlowStyle.SOFT, 50)).pixels(),
            )
        }

    @Test
    fun `focused rendering adds edge glow without changing the protected interior`() =
        onBorderEdt {
            val field = BorderFocusField().apply { isFocusSimulated = true }
            val original = MatteBorder(2, 3, 4, 5, Color.MAGENTA)
            val originalImage = paintBorder(field, original)
            val glowImage = paintBorder(field, GlowFocusBorder(original, Color.ORANGE, GlowStyle.SOFT, 70))
            assertTrue(pixelDifference(originalImage, glowImage, EDGE_REGION) > 50)
            assertEquals(originalImage.pixels(INTERIOR_REGION), glowImage.pixels(INTERIOR_REGION))
        }

    @Test
    fun `border preserves original insets and uses fallback without an original`() =
        onBorderEdt {
            val field = BorderFocusField()
            val original = MatteBorder(2, 3, 4, 5, Color.MAGENTA)
            val wrapped = GlowFocusBorder(original, Color.ORANGE, GlowStyle.SOFT, 50)
            val borderless = GlowFocusBorder(null, Color.ORANGE, GlowStyle.SOFT, 50)
            assertEquals(original.getBorderInsets(field), wrapped.getBorderInsets(field))
            assertEquals(JBUI.insets(1), borderless.getBorderInsets(field))
            assertFalse(GlowFocusBorder(original, Color.ORANGE, GlowStyle.SOFT, 50).isBorderOpaque)
        }

    @Test
    fun `painting leaves caller graphics state unchanged`() =
        onBorderEdt {
            val field = BorderFocusField().apply { isFocusSimulated = true }
            val image = BufferedImage(IMAGE_WIDTH, IMAGE_HEIGHT, BufferedImage.TYPE_INT_ARGB)
            val graphics = image.createGraphics()
            try {
                val color = Color(12, 34, 56)
                val font = Font(Font.MONOSPACED, Font.BOLD, 13)
                val clip = Rectangle(1, 2, IMAGE_WIDTH - 3, IMAGE_HEIGHT - 4)
                val transform = AffineTransform.getTranslateInstance(2.0, 3.0)
                graphics.color = color
                graphics.font = font
                graphics.clip = clip
                graphics.transform = transform
                val expectedClip = graphics.clipBounds
                GlowFocusBorder(null, Color.ORANGE, GlowStyle.SOFT, 50)
                    .paintBorder(field, graphics, 0, 0, IMAGE_WIDTH, IMAGE_HEIGHT)
                assertEquals(color, graphics.color)
                assertEquals(font, graphics.font)
                assertEquals(expectedClip, graphics.clipBounds)
                assertEquals(transform, graphics.transform)
            } finally {
                graphics.dispose()
            }
        }

    @Test
    fun `supplied color and style produce distinct focused pixels`() =
        onBorderEdt {
            val field = BorderFocusField().apply { isFocusSimulated = true }
            val softOrange = paintBorder(field, GlowFocusBorder(null, Color.ORANGE, GlowStyle.SOFT, 80))
            val softCyan = paintBorder(field, GlowFocusBorder(null, Color.CYAN, GlowStyle.SOFT, 80))
            val neonOrange = paintBorder(field, GlowFocusBorder(null, Color.ORANGE, GlowStyle.SHARP_NEON, 80))
            assertTrue(pixelDifference(softOrange, softCyan) > 100)
            assertTrue(pixelDifference(softOrange, neonOrange) > 100)
        }

    @Test
    fun `normal intensity changes output while clamp pairs remain identical`() =
        onBorderEdt {
            val field = BorderFocusField().apply { isFocusSimulated = true }
            val lowNormal = paintBorder(field, GlowFocusBorder(null, Color.ORANGE, GlowStyle.SOFT, 20))
            val highNormal = paintBorder(field, GlowFocusBorder(null, Color.ORANGE, GlowStyle.SOFT, 100))
            val zeroIntensity = paintBorder(field, GlowFocusBorder(null, Color.ORANGE, GlowStyle.SOFT, 0))
            val subminimumIntensity = paintBorder(field, GlowFocusBorder(null, Color.ORANGE, GlowStyle.SOFT, 7))
            val aboveMaximumIntensity = paintBorder(field, GlowFocusBorder(null, Color.ORANGE, GlowStyle.SOFT, 150))
            val farAboveMaximumIntensity =
                paintBorder(field, GlowFocusBorder(null, Color.ORANGE, GlowStyle.SOFT, 1_000))
            assertTrue(pixelDifference(lowNormal, highNormal) > 100)
            assertEquals(zeroIntensity.pixels(), subminimumIntensity.pixels())
            assertEquals(aboveMaximumIntensity.pixels(), farAboveMaximumIntensity.pixels())
        }

    @Test
    fun `configured arc rounds the corner more than the fallback arc`() =
        onBorderEdt {
            val field = BorderFocusField().apply { isFocusSimulated = true }
            mockkStatic(UIManager::class)
            try {
                every { UIManager.getInt("Component.arc") } returns 0
                val fallback = paintBorder(field, GlowFocusBorder(null, Color.ORANGE, GlowStyle.SOFT, 100))
                every { UIManager.getInt("Component.arc") } returns 24
                val rounded = paintBorder(field, GlowFocusBorder(null, Color.ORANGE, GlowStyle.SOFT, 100))
                val corner = Rectangle(0, 0, 9, 9)
                assertNotEquals(fallback.pixels(corner), rounded.pixels(corner))
                assertTrue(opaquePixelCount(fallback, corner) > opaquePixelCount(rounded, corner))
                assertEquals(fallback.pixels(INTERIOR_REGION), rounded.pixels(INTERIOR_REGION))
            } finally {
                unmockkStatic(UIManager::class)
            }
        }
}

private class BorderFocusField : JTextField() {
    var isFocusSimulated = false

    override fun hasFocus(): Boolean = isFocusSimulated
}

private fun paintBorder(
    field: BorderFocusField,
    border: Border,
): BufferedImage {
    val image = BufferedImage(IMAGE_WIDTH, IMAGE_HEIGHT, BufferedImage.TYPE_INT_ARGB)
    val graphics = image.createGraphics()
    try {
        border.paintBorder(field, graphics, 0, 0, image.width, image.height)
    } finally {
        graphics.dispose()
    }
    return image
}

private fun BufferedImage.pixels(region: Rectangle = Rectangle(0, 0, width, height)): List<Int> =
    getRGB(region.x, region.y, region.width, region.height, null, 0, region.width).toList()

private fun pixelDifference(
    first: BufferedImage,
    second: BufferedImage,
    region: Rectangle = Rectangle(0, 0, first.width, first.height),
): Int = first.pixels(region).zip(second.pixels(region)).count { (left, right) -> left != right }

private fun opaquePixelCount(
    image: BufferedImage,
    region: Rectangle,
): Int = image.pixels(region).count { pixel -> pixel ushr ALPHA_SHIFT != 0 }

private fun onBorderEdt(block: () -> Unit) {
    if (SwingUtilities.isEventDispatchThread()) block() else SwingUtilities.invokeAndWait(block)
}

private const val IMAGE_WIDTH = 100
private const val IMAGE_HEIGHT = 50
private const val ALPHA_SHIFT = 24
private val EDGE_REGION = Rectangle(0, 0, IMAGE_WIDTH, 5)
private val INTERIOR_REGION = Rectangle(12, 12, IMAGE_WIDTH - 24, IMAGE_HEIGHT - 24)
