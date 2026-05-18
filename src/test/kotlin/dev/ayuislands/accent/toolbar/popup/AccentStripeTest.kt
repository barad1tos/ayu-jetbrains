package dev.ayuislands.accent.toolbar.popup

import com.intellij.util.ui.JBUI
import java.awt.Color
import java.awt.Graphics2D
import java.awt.image.BufferedImage
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Locks the 2-px accent stripe contract for the popup top edge:
 *  - preferred height is exactly `JBUI.scale(2)`,
 *  - `paintForTest` fills the whole bar with the provided hex via `ColorUtil.fromHex`.
 *
 * The stripe is the single accent-tinted chrome element in the popup container
 * (Locked Answer #2 in `48-REDESIGN-SPEC.md`). Color reads via the accent supplier
 * lambda at paint time — never cached at construction — so a mid-LAF-swap repaint
 * picks up the resolved accent immediately.
 */
class AccentStripeTest {
    @Test
    fun `preferred height is 2 px JBUI scaled`() {
        val stripe = AccentStripe { "#FFB454" }
        assertEquals(JBUI.scale(2), stripe.preferredSize.height)
    }

    @Test
    fun `paintForTest fills bar with provided hex`() {
        val stripe = AccentStripe { "#FFB454" }
        stripe.setSize(SAMPLE_WIDTH, JBUI.scale(2))
        val image = BufferedImage(SAMPLE_WIDTH, JBUI.scale(2), BufferedImage.TYPE_INT_ARGB)
        val g2 = image.createGraphics()
        try {
            stripe.paintForTest(g2)
        } finally {
            g2.dispose()
        }
        val expected = Color(0xFF, 0xB4, 0x54)
        val sampled = Color(image.getRGB(SAMPLE_WIDTH / 2, 0))
        assertEquals(expected, sampled)
    }

    @Test
    fun `paintForTest re-reads supplier on each invocation (lazy resolution)`() {
        var hex = "#AAAAAA"
        val stripe = AccentStripe { hex }
        stripe.setSize(SAMPLE_WIDTH, JBUI.scale(2))

        val firstImage = paintInto(stripe)
        assertEquals(Color(0xAA, 0xAA, 0xAA), Color(firstImage.getRGB(0, 0)))

        hex = "#BBBBBB"
        val secondImage = paintInto(stripe)
        assertEquals(Color(0xBB, 0xBB, 0xBB), Color(secondImage.getRGB(0, 0)))
    }

    private fun paintInto(stripe: AccentStripe): BufferedImage {
        val image = BufferedImage(SAMPLE_WIDTH, JBUI.scale(2), BufferedImage.TYPE_INT_ARGB)
        val g2: Graphics2D = image.createGraphics()
        try {
            stripe.paintForTest(g2)
        } finally {
            g2.dispose()
        }
        return image
    }

    private companion object {
        const val SAMPLE_WIDTH: Int = 80
    }
}
