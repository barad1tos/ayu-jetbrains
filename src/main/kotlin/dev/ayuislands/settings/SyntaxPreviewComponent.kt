package dev.ayuislands.settings

import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import dev.ayuislands.accent.AyuVariant
import dev.ayuislands.syntax.PrimitiveCategory
import dev.ayuislands.syntax.RgbBlend
import dev.ayuislands.syntax.SyntaxCategoryRegistry
import dev.ayuislands.syntax.SyntaxIntensityApplicator
import dev.ayuislands.syntax.SyntaxOverlayLoader
import dev.ayuislands.syntax.SyntaxPreset
import dev.ayuislands.syntax.SyntaxReadabilityOptions
import org.jetbrains.annotations.TestOnly
import java.awt.Color
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import javax.swing.JComponent

/**
 * Live preview of syntax-intensity colors rendered as a mini IDE scene.
 *
 * Mirrors [VcsColorPreviewComponent]'s structure: left project-panel with
 * colored file dots, right editor-panel with gutter line numbers and
 * token-highlighted Kotlin code. Colors are resolved through the real
 * [SyntaxIntensityApplicator] pipeline so the preview exactly matches what
 * the editor will display.
 */
internal class SyntaxPreviewComponent(
    private var variant: AyuVariant,
) : JComponent() {
    private var categoryColorMap: Map<PrimitiveCategory, Color> = emptyMap()

    init {
        isOpaque = false
        toolTipText = "Syntax color preview"
    }

    fun updatePreview(
        variant: AyuVariant,
        preset: SyntaxPreset,
        customOverrides: Map<String, Map<String, Int>>,
        subordinatePreset: SyntaxPreset,
        readabilityOptions: SyntaxReadabilityOptions,
    ) {
        this.variant = variant
        val loader = SyntaxOverlayLoader.getInstance()
        val baseline = loader.loadBaselineForVariant(variant.name)
        val overlay = loader.loadOverlayForVariant(variant.name)
        val editorBg = RgbBlend.fallbackEditorBgFor(variant.name)
        val computed =
            SyntaxIntensityApplicator.compute(
                SyntaxIntensityApplicator.Request(
                    preset = preset,
                    variantName = variant.name,
                    editorBg = editorBg,
                    baseline = baseline,
                    overlay = overlay,
                    customOverrides = customOverrides,
                    subordinatePreset = subordinatePreset,
                    readabilityOptions = readabilityOptions,
                ),
            )
        categoryColorMap = buildCategoryColorMap(computed)
        revalidate()
        repaint()
    }

    override fun getPreferredSize(): Dimension = Dimension(JBUI.scale(PREVIEW_WIDTH), JBUI.scale(PREVIEW_HEIGHT))

    override fun getMinimumSize(): Dimension = Dimension(JBUI.scale(MIN_PREVIEW_WIDTH), JBUI.scale(PREVIEW_HEIGHT))

    override fun paintComponent(graphics: Graphics) {
        super.paintComponent(graphics)
        val g2 = graphics.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)

            val surface = editorSurface()
            val arc = JBUI.scale(ARC)
            g2.color = surface
            g2.fillRoundRect(0, 0, width, height, arc, arc)
            g2.color = JBColor.border()
            g2.drawRoundRect(0, 0, width - 1, height - 1, arc, arc)

            val padding = JBUI.scale(PADDING)
            val columnGap = JBUI.scale(COLUMN_GAP)
            val contentHeight = (height - padding * 2).coerceAtLeast(1)
            val projectWidth = JBUI.scale(PROJECT_WIDTH)
            val editorX = padding + projectWidth + columnGap
            val editorWidth = (width - editorX - padding).coerceAtLeast(1)

            paintProjectPanel(g2, padding, padding, projectWidth, contentHeight)
            paintEditorPanel(g2, editorX, padding, editorWidth, contentHeight)
        } finally {
            g2.dispose()
        }
    }

    private fun paintProjectPanel(
        g2: Graphics2D,
        x: Int,
        y: Int,
        width: Int,
        height: Int,
    ) {
        val arc = JBUI.scale(INNER_ARC)
        g2.color = panelSurface()
        g2.fillRoundRect(x, y, width, height, arc, arc)
        g2.color = JBColor.border()
        g2.drawRoundRect(x, y, width - 1, height - 1, arc, arc)

        g2.font = JBUI.Fonts.smallFont()
        val rowHeight = JBUI.scale(PROJECT_ROW_HEIGHT)
        var rowY = y + JBUI.scale(PROJECT_TOP_PADDING)
        for (row in PROJECT_ROWS) {
            val baseline = rowY + (rowHeight - g2.fontMetrics.height) / 2 + g2.fontMetrics.ascent
            g2.color = row.dotColor
            g2.fillOval(
                x + JBUI.scale(FILE_DOT_X),
                rowY + JBUI.scale(FILE_DOT_Y),
                JBUI.scale(FILE_DOT),
                JBUI.scale(FILE_DOT),
            )
            g2.color = UIUtil.getLabelForeground()
            g2.drawString(row.text, x + JBUI.scale(FILE_TEXT_X), baseline)
            rowY += rowHeight
        }
    }

    private fun paintEditorPanel(
        g2: Graphics2D,
        x: Int,
        y: Int,
        width: Int,
        height: Int,
    ) {
        val arc = JBUI.scale(INNER_ARC)
        g2.color = editorSurface()
        g2.fillRoundRect(x, y, width, height, arc, arc)
        g2.color = JBColor.border()
        g2.drawRoundRect(x, y, width - 1, height - 1, arc, arc)

        val gutterWidth = JBUI.scale(GUTTER_WIDTH)
        g2.color = panelSurface()
        g2.fillRect(x + 1, y + 1, gutterWidth - 1, height - 2)

        g2.font = JBUI.Fonts.smallFont()
        val geometry =
            EditorGeometry(
                gutterX = x,
                codeX = x + gutterWidth + JBUI.scale(CODE_LEFT_PADDING),
                codeWidth = (width - gutterWidth - JBUI.scale(CODE_LEFT_PADDING)).coerceAtLeast(1),
                panelY = y,
                panelHeight = height,
            )
        val rowHeight = JBUI.scale(CODE_ROW_HEIGHT)
        var rowY = y + JBUI.scale(CODE_TOP_PADDING)
        for (line in CODE_LINES) {
            paintCodeLine(g2, line, geometry, rowY, rowHeight)
            rowY += rowHeight
        }
    }

    private fun paintCodeLine(
        g2: Graphics2D,
        line: CodeLine,
        geometry: EditorGeometry,
        y: Int,
        height: Int,
    ) {
        g2.color = UIUtil.getContextHelpForeground()
        val baseline = y + (height - g2.fontMetrics.height) / 2 + g2.fontMetrics.ascent
        g2.drawString(line.lineNumber, geometry.gutterX + JBUI.scale(LINE_NUMBER_X), baseline)

        val previousClip = g2.clip
        g2.clipRect(geometry.codeX, geometry.panelY, geometry.codeWidth, geometry.panelHeight)
        try {
            var x = geometry.codeX
            for (token in line.tokens) {
                g2.color = token.category?.let { categoryColorMap[it] } ?: UIUtil.getLabelForeground()
                g2.drawString(token.text, x, baseline)
                x += g2.fontMetrics.stringWidth(token.text)
            }
        } finally {
            g2.clip = previousClip
        }
    }

    private data class EditorGeometry(
        val gutterX: Int,
        val codeX: Int,
        val codeWidth: Int,
        val panelY: Int,
        val panelHeight: Int,
    )

    private fun editorSurface(): Color =
        when (variant) {
            AyuVariant.DARK -> DARK_SURFACE
            AyuVariant.MIRAGE -> MIRAGE_SURFACE
            AyuVariant.LIGHT -> LIGHT_SURFACE
        }

    private fun panelSurface(): Color =
        when (variant) {
            AyuVariant.DARK -> DARK_PANEL_SURFACE
            AyuVariant.MIRAGE -> MIRAGE_PANEL_SURFACE
            AyuVariant.LIGHT -> LIGHT_PANEL_SURFACE
        }

    @TestOnly
    internal fun categoryColorForTest(category: PrimitiveCategory): Color? = categoryColorMap[category]

    @TestOnly
    internal fun variantForTest(): AyuVariant = variant

    private data class Token(
        val text: String,
        val category: PrimitiveCategory?,
    )

    private data class CodeLine(
        val lineNumber: String,
        val tokens: List<Token>,
    )

    private data class ProjectRow(
        val dotColor: Color,
        val text: String,
    )

    private companion object {
        private const val PREVIEW_WIDTH = 560
        private const val MIN_PREVIEW_WIDTH = 320
        private const val PREVIEW_HEIGHT = 180
        private const val PADDING = 10
        private const val COLUMN_GAP = 10
        private const val PROJECT_WIDTH = 154
        private const val PROJECT_TOP_PADDING = 12
        private const val PROJECT_ROW_HEIGHT = 22
        private const val FILE_DOT_X = 11
        private const val FILE_DOT_Y = 8
        private const val FILE_DOT = 7
        private const val FILE_TEXT_X = 26
        private const val GUTTER_WIDTH = 34
        private const val CODE_TOP_PADDING = 12
        private const val CODE_ROW_HEIGHT = 20
        private const val LINE_NUMBER_X = 9
        private const val CODE_LEFT_PADDING = 8
        private const val ARC = 8
        private const val INNER_ARC = 6

        private val DARK_SURFACE = Color(0x0D1017)
        private val MIRAGE_SURFACE = Color(0x1F2430)
        private val LIGHT_SURFACE = Color(0xFAFAFA)
        private val DARK_PANEL_SURFACE = Color(0x141923)
        private val MIRAGE_PANEL_SURFACE = Color(0x252B38)
        private val LIGHT_PANEL_SURFACE = Color(0xEFF2F5)

        private val PROJECT_ROWS =
            listOf(
                ProjectRow(Color(0x59C2FF), "PresetPreview.kt"),
                ProjectRow(Color(0x7FD17F), "Config.java"),
                ProjectRow(Color(0xFFA759), "Types.kt"),
                ProjectRow(Color(0xFFD580), "build/"),
            )

        private val CODE_LINES =
            listOf(
                CodeLine(
                    "1",
                    listOf(
                        Token("fun ", PrimitiveCategory.KEYWORD),
                        Token("main", PrimitiveCategory.FUNCTION_DECL),
                        Token("() ", PrimitiveCategory.OPERATOR),
                        Token("{", PrimitiveCategory.OPERATOR),
                    ),
                ),
                CodeLine(
                    "2",
                    listOf(
                        Token("  val ", PrimitiveCategory.KEYWORD),
                        Token("msg", PrimitiveCategory.LOCAL_VAR),
                        Token(" = ", PrimitiveCategory.OPERATOR),
                        Token("\"hello\"", PrimitiveCategory.STRING_LITERAL),
                    ),
                ),
                CodeLine(
                    "3",
                    listOf(
                        Token("  val ", PrimitiveCategory.KEYWORD),
                        Token("count", PrimitiveCategory.LOCAL_VAR),
                        Token(" = ", PrimitiveCategory.OPERATOR),
                        Token("42", PrimitiveCategory.NUMBER_LITERAL),
                    ),
                ),
                CodeLine(
                    "4",
                    listOf(
                        Token("  // print greeting", PrimitiveCategory.COMMENT),
                    ),
                ),
                CodeLine(
                    "5",
                    listOf(
                        Token("  @JvmStatic", PrimitiveCategory.ANNOTATION),
                    ),
                ),
                CodeLine(
                    "6",
                    listOf(
                        Token("  if ", PrimitiveCategory.KEYWORD),
                        Token("(", PrimitiveCategory.OPERATOR),
                        Token("msg", PrimitiveCategory.INSTANCE_FIELD),
                        Token(".", PrimitiveCategory.OPERATOR),
                        Token("isNotEmpty", PrimitiveCategory.FUNCTION_DECL),
                        Token(")", PrimitiveCategory.OPERATOR),
                    ),
                ),
                CodeLine(
                    "7",
                    listOf(
                        Token("    ", null),
                        Token("println", PrimitiveCategory.FUNCTION_DECL),
                        Token("(", PrimitiveCategory.OPERATOR),
                        Token("msg", PrimitiveCategory.PARAMETER),
                        Token(")", PrimitiveCategory.OPERATOR),
                    ),
                ),
                CodeLine(
                    "8",
                    listOf(
                        Token("}", PrimitiveCategory.OPERATOR),
                    ),
                ),
            )
    }
}

private fun buildCategoryColorMap(computed: Map<TextAttributesKey, TextAttributes>): Map<PrimitiveCategory, Color> {
    val map = mutableMapOf<PrimitiveCategory, Color>()
    for ((key, attrs) in computed) {
        val category = SyntaxCategoryRegistry.classify(key.externalName) ?: continue
        if (category in map) continue
        val fg = attrs.foregroundColor ?: continue
        map[category] = fg
    }
    return map
}
