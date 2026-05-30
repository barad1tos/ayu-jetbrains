package dev.ayuislands.settings

import dev.ayuislands.font.FontWeight
import java.awt.image.BufferedImage
import kotlin.test.Test
import kotlin.test.assertTrue

class FontPreviewComponentTest {
    @Test
    fun `font preview preferred height expands for large custom typography`() {
        val preview = FontPreviewComponent()
        val defaultHeight = preview.preferredSize.height

        preview.updateSettings(
            newFontSize = 30f,
            newLineSpacing = 2.0f,
            newLigatures = true,
            newWeight = FontWeight.SEMI_BOLD,
        )
        val expandedSize = preview.preferredSize

        assertTrue(
            expandedSize.height > defaultHeight,
            "large font settings must ask layout for more height instead of clipping preview rows",
        )
        preview.setSize(expandedSize)
        val image = BufferedImage(expandedSize.width, expandedSize.height, BufferedImage.TYPE_INT_ARGB)
        val graphics = image.createGraphics()
        try {
            preview.paint(graphics)
        } finally {
            graphics.dispose()
        }
    }
}
