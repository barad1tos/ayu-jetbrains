package dev.ayuislands.settings

import com.intellij.util.ui.JBUI
import dev.ayuislands.font.FontDetector
import dev.ayuislands.font.FontPreset
import dev.ayuislands.font.FontWeight
import java.awt.Dimension
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints.KEY_ANTIALIASING
import java.awt.RenderingHints.KEY_TEXT_ANTIALIASING
import java.awt.RenderingHints.VALUE_ANTIALIAS_ON
import java.awt.RenderingHints.VALUE_TEXT_ANTIALIAS_ON
import java.awt.font.TextAttribute
import javax.swing.JComponent
import javax.swing.UIManager

/** Renders a live font preview for the selected FontPreset using Graphics2D. */
class FontPreviewComponent : JComponent() {
    init {
        isOpaque = false
    }

    private var currentPreset: FontPreset = FontPreset.AMBIENT
    private var resolvedFontFamily: String = FontPreset.AMBIENT.fontFamily
    private var fontInstalled: Boolean = true
    private var fontSize: Float = FontPreset.AMBIENT.fontSize
    private var lineSpacing: Float = FontPreset.AMBIENT.lineSpacing
    private var enableLigatures: Boolean = FontPreset.AMBIENT.enableLigatures
    private var fontWeight: FontWeight = FontPreset.AMBIENT.defaultWeight

    fun updatePreset(
        preset: FontPreset,
        installed: Boolean,
    ) {
        currentPreset = preset
        fontInstalled = installed
        fontSize = preset.fontSize
        lineSpacing = preset.lineSpacing
        enableLigatures = preset.enableLigatures
        if (preset.isCurated) {
            resolvedFontFamily = FontDetector.resolveFamily(preset) ?: preset.fontFamily
        }
        revalidate()
        repaint()
    }

    /** Set an explicit font family (used for CUSTOM preset). */
    fun updateFontFamily(family: String) {
        resolvedFontFamily = family
        revalidate()
        repaint()
    }

    fun updateSettings(
        newFontSize: Float,
        newLineSpacing: Float,
        newLigatures: Boolean,
        newWeight: FontWeight,
    ) {
        fontSize = newFontSize
        lineSpacing = newLineSpacing
        enableLigatures = newLigatures
        fontWeight = newWeight
        revalidate()
        repaint()
    }

    override fun getPreferredSize(): Dimension {
        val lineHeight = (fontSize * lineSpacing).toInt()
        val codeHeight = lineHeight * SAMPLE_LINES.size
        val totalHeight = PADDING_TOP + codeHeight + PADDING_BOTTOM
        return Dimension(JBUI.scale(PANEL_WIDTH), JBUI.scale(totalHeight.coerceAtMost(MAX_HEIGHT)))
    }

    override fun paintComponent(graphics: Graphics) {
        super.paintComponent(graphics)
        val g2 = graphics.create() as Graphics2D
        try {
            g2.setRenderingHint(KEY_ANTIALIASING, VALUE_ANTIALIAS_ON)
            g2.setRenderingHint(KEY_TEXT_ANTIALIASING, VALUE_TEXT_ANTIALIAS_ON)

            val bg =
                UIManager.getColor("Panel.background")?.darker()
                    ?: java.awt.Color.DARK_GRAY
            g2.color = bg
            g2.fillRoundRect(0, 0, width, height, ARC, ARC)

            if (!fontInstalled) {
                drawFallbackMessage(g2)
                return
            }
            drawCodePreview(g2)
        } finally {
            g2.dispose()
        }
    }

    private fun drawFallbackMessage(g2: Graphics2D) {
        val disabledFg =
            UIManager.getColor("Label.disabledForeground")
                ?: java.awt.Color.GRAY
        g2.color = disabledFg
        g2.font = JBUI.Fonts.smallFont().deriveFont(Font.ITALIC)
        val message = "Install ${currentPreset.fontFamily} to preview"
        val metrics = g2.fontMetrics
        g2.drawString(
            message,
            (width - metrics.stringWidth(message)) / 2,
            height / 2 + metrics.ascent / 2,
        )
    }

    private fun drawCodePreview(g2: Graphics2D) {
        val attributes =
            mutableMapOf<TextAttribute, Any>(
                TextAttribute.FAMILY to resolvedFontFamily,
                TextAttribute.SIZE to fontSize,
                TextAttribute.WEIGHT to fontWeight.textAttributeValue,
            )
        if (enableLigatures) {
            attributes[TextAttribute.LIGATURES] = TextAttribute.LIGATURES_ON
        }
        g2.font = Font(attributes)
        g2.color = UIManager.getColor("Label.foreground") ?: java.awt.Color.WHITE
        val metrics = g2.fontMetrics
        val lineHeight = (metrics.height * lineSpacing).toInt()
        var y = JBUI.scale(PADDING_TOP) + metrics.ascent
        for (line in SAMPLE_LINES) {
            g2.drawString(line, JBUI.scale(PADDING_LEFT), y)
            y += lineHeight
        }
    }

    private companion object {
        const val PANEL_WIDTH = 440
        const val MAX_HEIGHT = 200
        const val PADDING_TOP = 12
        const val PADDING_BOTTOM = 12
        const val PADDING_LEFT = 16
        const val ARC = 10
        val SAMPLE_LINES =
            listOf(
                "fun processItems(items: List<Item>): Result {",
                "  val filtered = items.filter { it.isValid }",
                "    .map { item -> transform(item) }",
                "  return Result(filtered.size, filtered)",
                "}",
            )
    }
}
