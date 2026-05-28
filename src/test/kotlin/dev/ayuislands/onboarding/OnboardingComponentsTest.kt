package dev.ayuislands.onboarding

import com.intellij.ui.JBColor
import java.awt.Color
import java.awt.image.BufferedImage
import javax.swing.JPanel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class OnboardingComponentsTest {
    @Test
    fun `attachCardLabels keeps onboarding title theme-aware and readable`() {
        val card = JPanel()
        val content =
            CardLabelContent(
                title = "Ayu Islands Mirage",
                subtitle = "Balanced contrast",
                footnote = "Includes syntax tuning",
            )
        val style =
            CardLabelStyle(
                titleSizePx = 18,
                descSizePx = 12,
                descColor = JBColor(0xB0B8C4, 0xB0B8C4),
                titleSubtitleGapPx = 6,
                subtitleFootnoteGapPx = 4,
            )

        attachCardLabels(card, content, style)

        val titleLabel = assertIs<com.intellij.ui.components.JBLabel>(card.components[0])
        assertEquals("Ayu Islands Mirage", titleLabel.text)
        assertSame(
            JBColor.WHITE,
            titleLabel.foreground,
            "Onboarding card titles must keep the theme-aware JBColor foreground.",
        )

        val subtitleLabel = assertIs<com.intellij.ui.components.JBLabel>(card.components[2])
        assertEquals("Balanced contrast", subtitleLabel.text)
        assertEquals(style.descColor, subtitleLabel.foreground)
    }

    @Test
    fun `paintCardChrome paints the idle Ayu card fill instead of platform defaults`() {
        val image = BufferedImage(CARD_WIDTH, CARD_HEIGHT, BufferedImage.TYPE_INT_ARGB)
        val graphics = image.createGraphics()
        try {
            paintCardChrome(
                graphics,
                CARD_WIDTH,
                CARD_HEIGHT,
                hovered = false,
                tintColor = OnboardingColors.ACCENT,
            )
        } finally {
            graphics.dispose()
        }

        val center = Color(image.getRGB(CARD_WIDTH / 2, CARD_HEIGHT / 2), true)
        assertEquals(
            OnboardingCardChrome.CARD_BG_COLOR.rgb,
            center.rgb,
            "Idle onboarding cards must render with the Ayu card fill.",
        )
        assertEquals(255, center.alpha, "Idle card fill must be opaque for readable text.")
    }

    @Test
    fun `paintCardChrome uses translucent accent tint on hovered cards`() {
        val image = BufferedImage(CARD_WIDTH, CARD_HEIGHT, BufferedImage.TYPE_INT_ARGB)
        val graphics = image.createGraphics()
        try {
            paintCardChrome(
                graphics,
                CARD_WIDTH,
                CARD_HEIGHT,
                hovered = true,
                tintColor = OnboardingColors.ACCENT,
            )
        } finally {
            graphics.dispose()
        }

        val center = Color(image.getRGB(CARD_WIDTH / 2, CARD_HEIGHT / 2), true)
        assertNotEquals(
            OnboardingCardChrome.CARD_BG_COLOR.rgb,
            center.rgb,
            "Hovered onboarding cards must use the accent gradient instead of the idle fill.",
        )
        assertTrue(center.alpha in 1 until 255, "Hover tint must stay translucent over the card surface.")

        val topHighlight = Color(image.getRGB(CARD_WIDTH / 2, 2), true)
        assertTrue(topHighlight.alpha > 0, "The glass highlight must remain visible above the hover tint.")
        assertNotEquals(center.rgb, topHighlight.rgb, "The highlight strip must visibly differ from the card center.")
    }

    private companion object {
        const val CARD_WIDTH = 96
        const val CARD_HEIGHT = 64
    }
}
