package dev.ayuislands.settings

import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import dev.ayuislands.font.FontDetector
import dev.ayuislands.font.FontPreset
import dev.ayuislands.font.FontWeight
import java.awt.Color
import java.awt.Dimension
import java.awt.Font
import java.awt.FontMetrics
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Rectangle
import java.awt.RenderingHints.KEY_ANTIALIASING
import java.awt.RenderingHints.KEY_TEXT_ANTIALIASING
import java.awt.RenderingHints.VALUE_ANTIALIAS_ON
import java.awt.RenderingHints.VALUE_TEXT_ANTIALIAS_ON
import java.awt.font.TextAttribute
import javax.swing.JComponent
import javax.swing.UIManager
import kotlin.math.roundToInt

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

    override fun getPreferredSize(): Dimension = Dimension(JBUI.scale(PANEL_WIDTH), preferredPreviewHeight())

    override fun getMinimumSize(): Dimension = Dimension(JBUI.scale(MIN_PANEL_WIDTH), preferredPreviewHeight())

    override fun paintComponent(graphics: Graphics) {
        super.paintComponent(graphics)
        val g2 = graphics.create() as Graphics2D
        try {
            g2.setRenderingHint(KEY_ANTIALIASING, VALUE_ANTIALIAS_ON)
            g2.setRenderingHint(KEY_TEXT_ANTIALIASING, VALUE_TEXT_ANTIALIAS_ON)

            val arc = JBUI.scale(ARC)
            g2.color = surface()
            g2.fillRoundRect(0, 0, width, height, arc, arc)
            g2.color = JBColor.border()
            g2.drawRoundRect(0, 0, width - 1, height - 1, arc, arc)

            val padding = JBUI.scale(PADDING)
            val gap = JBUI.scale(COLUMN_GAP)
            val showProjectPanel = width >= JBUI.scale(SPLIT_MIN_WIDTH)
            val projectWidth =
                if (showProjectPanel) {
                    (width / PROJECT_WIDTH_DIVISOR)
                        .coerceIn(JBUI.scale(PROJECT_MIN_WIDTH), JBUI.scale(PROJECT_MAX_WIDTH))
                } else {
                    0
                }
            if (showProjectPanel) {
                drawProjectPanel(g2, padding, padding, projectWidth, height - padding * 2)
            }
            val editorX = padding + projectWidth + if (showProjectPanel) gap else 0
            val editorWidth = (width - editorX - padding).coerceAtLeast(1)
            drawEditorPanel(g2, editorX, padding, editorWidth, height - padding * 2)
            if (fontInstalled) {
                drawCodePreview(g2, editorX + JBUI.scale(GUTTER_WIDTH), padding)
            } else {
                drawFallbackMessage(g2, editorX, padding, editorWidth, height - padding * 2)
            }
        } finally {
            g2.dispose()
        }
    }

    private fun drawNestedSurface(
        g2: Graphics2D,
        bounds: Rectangle,
        fill: Color,
    ) {
        val arc = JBUI.scale(INNER_ARC)
        val shadowOffset = JBUI.scale(SHADOW_OFFSET)
        g2.color = shadowColor()
        g2.fillRoundRect(bounds.x + shadowOffset, bounds.y + shadowOffset, bounds.width, bounds.height, arc, arc)
        g2.color = fill
        g2.fillRoundRect(bounds.x, bounds.y, bounds.width, bounds.height, arc, arc)
        g2.color = nestedBorder()
        g2.drawRoundRect(bounds.x, bounds.y, bounds.width - 1, bounds.height - 1, arc, arc)
        g2.color = nestedHighlight()
        g2.drawLine(
            bounds.x + JBUI.scale(HIGHLIGHT_INSET),
            bounds.y + 1,
            bounds.x + bounds.width - JBUI.scale(HIGHLIGHT_INSET),
            bounds.y + 1,
        )
    }

    private fun drawProjectPanel(
        g2: Graphics2D,
        x: Int,
        y: Int,
        width: Int,
        height: Int,
    ) {
        drawNestedSurface(g2, Rectangle(x, y, width, height), panelSurface())

        g2.font = JBUI.Fonts.smallFont()
        val rowHeight = JBUI.scale(PROJECT_ROW_HEIGHT)
        var rowY = y + JBUI.scale(PROJECT_TOP_PADDING)
        for (row in PROJECT_ROWS) {
            val baseline = rowY + (rowHeight - g2.fontMetrics.height) / 2 + g2.fontMetrics.ascent
            g2.color = row.color
            g2.fillRect(
                x + JBUI.scale(FILE_DOT_X),
                rowY + JBUI.scale(FILE_DOT_Y),
                JBUI.scale(FILE_DOT),
                JBUI.scale(FILE_DOT),
            )
            g2.drawString(row.text, x + JBUI.scale(FILE_TEXT_X), baseline)
            rowY += rowHeight
        }
    }

    private fun drawEditorPanel(
        g2: Graphics2D,
        x: Int,
        y: Int,
        width: Int,
        height: Int,
    ) {
        drawNestedSurface(g2, Rectangle(x, y, width, height), editorSurface())

        val gutterWidth = JBUI.scale(GUTTER_WIDTH)
        g2.color = panelSurface()
        g2.fillRect(x + 1, y + 1, gutterWidth - 1, height - 2)

        g2.font = JBUI.Fonts.smallFont()
        val smallMetrics = g2.fontMetrics
        val lineHeight = codeLineHeight(g2.getFontMetrics(codePreviewFont()))
        var rowY = y + JBUI.scale(CODE_TOP_PADDING)
        for (lineNumber in CODE_FIRST_LINE until CODE_FIRST_LINE + SAMPLE_LINES.size) {
            val baseline = rowY + (lineHeight - smallMetrics.height) / 2 + smallMetrics.ascent
            g2.color = mutedText()
            g2.drawString("$lineNumber", x + JBUI.scale(LINE_NUMBER_X), baseline)
            rowY += lineHeight
        }
    }

    private fun drawFallbackMessage(
        g2: Graphics2D,
        x: Int,
        y: Int,
        width: Int,
        height: Int,
    ) {
        val disabledFg =
            UIManager.getColor("Label.disabledForeground")
                ?: JBColor.GRAY
        g2.color = disabledFg
        g2.font = JBUI.Fonts.smallFont().deriveFont(Font.ITALIC)
        // Curated presets advertise their canonical family as the install
        // target; non-curated (CUSTOM) advertise whatever the user picked,
        // since the live preview would have rendered with that family.
        val displayedFamily =
            if (currentPreset.isCurated) currentPreset.fontFamily else resolvedFontFamily
        val message =
            if (displayedFamily.isBlank()) {
                "Choose a font family to preview"
            } else {
                "Install $displayedFamily to preview"
            }
        val metrics = g2.fontMetrics
        g2.drawString(
            message,
            x + (width - metrics.stringWidth(message)) / 2,
            y + height / 2 + metrics.ascent / 2,
        )
    }

    private fun drawCodePreview(
        g2: Graphics2D,
        x: Int,
        y: Int,
    ) {
        val codeWidth = (width - x - JBUI.scale(PADDING)).coerceAtLeast(1)
        val previousClip = g2.clip
        g2.clipRect(x, y, codeWidth, (height - y - JBUI.scale(PADDING)).coerceAtLeast(1))
        g2.font = codePreviewFont()
        val metrics = g2.fontMetrics
        val lineHeight = codeLineHeight(metrics)
        var rowY = y + JBUI.scale(CODE_TOP_PADDING)
        for ((index, line) in SAMPLE_LINES.withIndex()) {
            if (index in HIGHLIGHT_ROWS) {
                g2.color = highlightColor(index)
                g2.fillRect(x, rowY, codeWidth, lineHeight)
            }
            val baseline = rowY + (lineHeight - metrics.height) / 2 + metrics.ascent
            g2.color = codeTextColor(index)
            g2.drawString(line, x + JBUI.scale(CODE_LEFT_PADDING), baseline)
            rowY += lineHeight
        }
        g2.clip = previousClip
    }

    private fun preferredPreviewHeight(): Int {
        val lineHeight = codeLineHeight(getFontMetrics(codePreviewFont()))
        val codeHeight = JBUI.scale(CODE_TOP_PADDING) * 2 + lineHeight * SAMPLE_LINES.size
        return (JBUI.scale(PADDING) * 2 + codeHeight).coerceAtLeast(JBUI.scale(PANEL_HEIGHT))
    }

    private fun codePreviewFont(): Font {
        val attributes =
            mutableMapOf<TextAttribute, Any>(
                TextAttribute.FAMILY to resolvedFontFamily,
                TextAttribute.SIZE to fontSize,
                TextAttribute.WEIGHT to fontWeight.textAttributeValue,
            )
        if (enableLigatures) {
            attributes[TextAttribute.LIGATURES] = TextAttribute.LIGATURES_ON
        }
        return Font(attributes)
    }

    private fun codeLineHeight(metrics: FontMetrics): Int =
        maxOf(
            (metrics.height * lineSpacing).roundToInt(),
            metrics.height,
        )

    private fun surface(): Color = JBColor(FALLBACK_SURFACE_COLOR, FALLBACK_SURFACE_COLOR)

    private fun panelSurface(): Color = JBColor(FALLBACK_PANEL_COLOR, FALLBACK_PANEL_COLOR)

    private fun editorSurface(): Color = JBColor(FALLBACK_EDITOR_COLOR, FALLBACK_EDITOR_COLOR)

    private fun shadowColor(): Color = JBColor(SHADOW_COLOR, SHADOW_COLOR)

    private fun nestedBorder(): Color = JBColor(NESTED_BORDER_COLOR, NESTED_BORDER_COLOR)

    private fun nestedHighlight(): Color = JBColor(NESTED_HIGHLIGHT_COLOR, NESTED_HIGHLIGHT_COLOR)

    private fun mutedText(): Color =
        UIManager.getColor("Label.disabledForeground")
            ?: JBColor.GRAY

    private fun codeTextColor(index: Int): Color =
        when (index) {
            ADDED_LINE_INDEX -> JBColor(ADDED_TEXT_COLOR, ADDED_TEXT_COLOR)
            RETURN_LINE_INDEX -> JBColor(RETURN_TEXT_COLOR, RETURN_TEXT_COLOR)
            else -> UIManager.getColor("Label.foreground") ?: JBColor.WHITE
        }

    private fun highlightColor(index: Int): Color =
        when (index) {
            ADDED_LINE_INDEX -> JBColor(ADDED_HIGHLIGHT_COLOR, ADDED_HIGHLIGHT_COLOR)
            RETURN_LINE_INDEX -> JBColor(RETURN_HIGHLIGHT_COLOR, RETURN_HIGHLIGHT_COLOR)
            else -> panelSurface()
        }

    private companion object {
        const val PANEL_WIDTH = 760
        const val MIN_PANEL_WIDTH = 360
        const val PANEL_HEIGHT = 136
        const val SPLIT_MIN_WIDTH = 430
        const val PROJECT_WIDTH_DIVISOR = 5
        const val PROJECT_MIN_WIDTH = 120
        const val PROJECT_MAX_WIDTH = 170
        const val PADDING = 8
        const val COLUMN_GAP = 8
        const val ARC = 8
        const val INNER_ARC = 6
        const val PROJECT_ROW_HEIGHT = 22
        const val PROJECT_TOP_PADDING = 12
        const val FILE_DOT_X = 14
        const val FILE_DOT_Y = 9
        const val FILE_DOT = 6
        const val FILE_TEXT_X = 28
        const val GUTTER_WIDTH = 42
        const val LINE_NUMBER_X = 12
        const val CODE_FIRST_LINE = 12
        const val CODE_ROW_HEIGHT = 22
        const val CODE_TOP_PADDING = 8
        const val CODE_LEFT_PADDING = 14
        const val SHADOW_OFFSET = 2
        const val HIGHLIGHT_INSET = 6
        const val ADDED_LINE_INDEX = 1
        const val RETURN_LINE_INDEX = 3
        const val FALLBACK_SURFACE_COLOR = 0x1F2430
        const val FALLBACK_PANEL_COLOR = 0x252B38
        const val FALLBACK_EDITOR_COLOR = 0x171C28
        const val SHADOW_COLOR = 0x10141C
        const val NESTED_BORDER_COLOR = 0x31394A
        const val NESTED_HIGHLIGHT_COLOR = 0x3A4354
        const val ADDED_TEXT_COLOR = 0xC3E88D
        const val RETURN_TEXT_COLOR = 0xFF757F
        const val ADDED_HIGHLIGHT_COLOR = 0x253E27
        const val RETURN_HIGHLIGHT_COLOR = 0x4A2637
        val HIGHLIGHT_ROWS = setOf(ADDED_LINE_INDEX, RETURN_LINE_INDEX)
        val PROJECT_ROWS =
            listOf(
                ProjectRow("PresetPreview.kt", JBColor(0x73D0FF, 0x73D0FF)),
                ProjectRow("FontTokens.xml", JBColor(0xBAE67E, 0xBAE67E)),
                ProjectRow("editor.icls", JBColor(0xFFAD66, 0xFFAD66)),
                ProjectRow("build/", JBColor(0xFFCC66, 0xFFCC66)),
            )
        val SAMPLE_LINES =
            listOf(
                "fun renderPreset(font: EditorFont): Preview {",
                "  val selected = font.family != fallback",
                "  val glyphs = sample.map { char -> shape(char) }",
                "  return Preview(glyphs, selected && font.ligatures)",
                "}",
            )

        data class ProjectRow(
            val text: String,
            val color: Color,
        )
    }
}
