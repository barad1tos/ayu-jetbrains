package dev.ayuislands.settings

import com.intellij.ui.dsl.builder.Panel
import com.intellij.util.ui.JBUI
import dev.ayuislands.accent.AccentElementId
import dev.ayuislands.accent.AyuVariant
import dev.ayuislands.glow.GlowRenderer
import dev.ayuislands.glow.GlowStyle
import java.awt.AlphaComposite
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Rectangle
import java.awt.RenderingHints
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.UIManager

/** Visual preview mockup of all 8 accent elements, reacts to color/toggle/glow/highlight changes. */
class AyuIslandsPreviewPanel : AyuIslandsSettingsPanel {
    var previewAccentHex: String = "#FFCC66"
    var previewToggles: Map<AccentElementId, Boolean> = emptyMap()
    var previewGlowEnabled: Boolean = true
    var previewConflicts: Set<AccentElementId> = emptySet()
    var highlightedElement: AccentElementId? = null

    // Glow style properties for contextual preview
    var previewGlowStyle: GlowStyle = GlowStyle.SOFT
    var previewGlowIntensity: Int = PREVIEW_GLOW_DEFAULT_INTENSITY
    var previewGlowWidth: Int = GlowRenderer.DEFAULT_GLOW_WIDTH

    private var mockupComponent: AccentPreviewComponent? = null

    override fun buildPanel(
        panel: Panel,
        variant: AyuVariant,
    ) {
        previewAccentHex = variant.defaultAccent

        val mockup = AccentPreviewComponent(variant)
        mockupComponent = mockup

        val wrapper = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0))
        wrapper.isOpaque = false
        wrapper.add(mockup)

        panel.row { cell(wrapper) }
    }

    fun createComponent(variant: AyuVariant): JComponent {
        previewAccentHex = variant.defaultAccent

        val mockup = AccentPreviewComponent(variant)
        mockupComponent = mockup

        val wrapper = JPanel()
        wrapper.layout = BoxLayout(wrapper, BoxLayout.X_AXIS)
        wrapper.isOpaque = false
        wrapper.add(Box.createHorizontalStrut(JBUI.scale(HEADER_INDENT)))
        wrapper.add(mockup)
        return wrapper
    }

    fun updatePreview() {
        mockupComponent?.repaint()
    }

    override fun isModified(): Boolean = false

    override fun apply() { /* Read-only preview — no settings to apply */ }

    override fun reset() { /* Read-only preview — no settings to reset */ }

    private data class EditorLayout(
        val width: Int,
        val height: Int,
        val tabHeight: Int,
        val editorTop: Int,
        val editorBottom: Int,
        val line1Y: Int,
        val line2Y: Int,
        val line3Y: Int,
        val scrollbarWidth: Int,
    )

    private inner class AccentPreviewComponent(
        private val variant: AyuVariant,
    ) : JComponent() {
        private val glowRenderer = GlowRenderer()

        init {
            preferredSize = Dimension(JBUI.scale(PANEL_WIDTH), JBUI.scale(PANEL_HEIGHT))
        }

        override fun paintComponent(graphics: Graphics) {
            val g2 = graphics.create() as Graphics2D
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)

                val panelBackground =
                    UIManager.getColor("Panel.background")
                        ?: Color(FALLBACK_BG_RED, FALLBACK_BG_GREEN, FALLBACK_BG_BLUE)
                val editorBackground = darken(panelBackground, DARKEN_FACTOR)
                val mutedForeground =
                    UIManager.getColor("Label.disabledForeground")
                        ?: Color(MUTED_FG_CHANNEL, MUTED_FG_CHANNEL, MUTED_FG_CHANNEL)
                val accent = parseColor(previewAccentHex)
                val dimmedAccent = Color(accent.red, accent.green, accent.blue, DIMMED_ALPHA)

                val width = width
                val height = height
                val tabHeight = JBUI.scale(TAB_HEIGHT)
                val scrollbarWidth = JBUI.scale(SCROLLBAR_WIDTH)
                val progressHeight = JBUI.scale(PROGRESS_HEIGHT)
                val lineHeight = JBUI.scale(LINE_HEIGHT)
                val codeFont = JBUI.Fonts.smallFont()
                val textOffsetY = JBUI.scale(TEXT_OFFSET_Y)
                val codeIndent = JBUI.scale(CODE_INDENT)

                // Background
                g2.color = panelBackground
                g2.fillRect(0, 0, width, height)

                // Tab strip area
                g2.color = editorBackground
                g2.fillRect(0, 0, width, tabHeight)

                // Mock tabs
                val tabNames = arrayOf("main.kt", "build.gradle")
                val tabWidth = JBUI.scale(TAB_WIDTH)
                g2.font = codeFont
                for (index in tabNames.indices) {
                    val tabX = index * (tabWidth + JBUI.scale(TAB_GAP))
                    g2.color = if (index == 0) accent else mutedForeground
                    g2.drawString(
                        tabNames[index],
                        tabX + JBUI.scale(TAB_PADDING),
                        tabHeight - JBUI.scale(TAB_BOTTOM_MARGIN),
                    )
                }

                // Tab underline (first tab)
                drawWithAlpha(g2, AccentElementId.TAB_UNDERLINES) {
                    g2.color = elementColor(AccentElementId.TAB_UNDERLINES, accent, dimmedAccent, mutedForeground)
                    g2.fillRect(0, tabHeight - JBUI.scale(UNDERLINE_HEIGHT), tabWidth, JBUI.scale(UNDERLINE_HEIGHT))
                }

                // Editor area
                val editorTop = tabHeight
                val editorBottom = height - progressHeight
                g2.color = editorBackground
                g2.fillRect(0, editorTop, width - scrollbarWidth, editorBottom - editorTop)

                // Code line positions
                val line1Y = editorTop + JBUI.scale(LINE_START_OFFSET)
                val line2Y = line1Y + lineHeight + JBUI.scale(LINE_GAP)
                val line3Y = line2Y + lineHeight + JBUI.scale(LINE_GAP)
                val line4Y = line3Y + lineHeight + JBUI.scale(LINE_GAP)

                val fm = g2.getFontMetrics(codeFont)

                // Caret row highlight -- line 1
                drawWithAlpha(g2, AccentElementId.CARET_ROW) {
                    val caretColor = elementColor(AccentElementId.CARET_ROW, accent, dimmedAccent, mutedForeground)
                    g2.color = Color(caretColor.red, caretColor.green, caretColor.blue, CARET_ROW_ALPHA)
                    g2.fillRect(0, line1Y, width - scrollbarWidth, lineHeight)
                }

                // Line 1 text: fun main() {
                g2.color = mutedForeground
                g2.font = codeFont
                g2.drawString("fun main() {", codeIndent, line1Y + textOffsetY)

                // Line 2: "  val x = (1 + 2)"
                g2.color = mutedForeground
                g2.drawString("  val x = (1 + 2)", codeIndent, line2Y + textOffsetY)

                // Search results highlight on "val"
                val prefixWidth = fm.stringWidth("  ")
                val valWidth = fm.stringWidth("val")
                drawWithAlpha(g2, AccentElementId.SEARCH_RESULTS) {
                    val searchColor =
                        elementColor(AccentElementId.SEARCH_RESULTS, accent, dimmedAccent, mutedForeground)
                    g2.color = Color(searchColor.red, searchColor.green, searchColor.blue, SEARCH_HIGHLIGHT_ALPHA)
                    g2.fillRect(
                        codeIndent + prefixWidth,
                        line2Y,
                        valWidth + JBUI.scale(SEARCH_PADDING),
                        lineHeight,
                    )
                }

                // Bracket match on (and)
                drawWithAlpha(g2, AccentElementId.BRACKET_MATCH) {
                    val bracketColor =
                        elementColor(AccentElementId.BRACKET_MATCH, accent, dimmedAccent, mutedForeground)
                    g2.color = Color(bracketColor.red, bracketColor.green, bracketColor.blue, BRACKET_ALPHA)
                    val openParenOffset = fm.stringWidth("  val x = ")
                    val openParenWidth = fm.stringWidth("(")
                    g2.fillRect(
                        codeIndent + openParenOffset,
                        line2Y,
                        openParenWidth + JBUI.scale(BRACKET_PADDING),
                        lineHeight,
                    )
                    val closeParenOffset = fm.stringWidth("  val x = (1 + 2")
                    val closeParenWidth = fm.stringWidth(")")
                    g2.fillRect(
                        codeIndent + closeParenOffset,
                        line2Y,
                        closeParenWidth + JBUI.scale(BRACKET_PADDING),
                        lineHeight,
                    )
                }

                // Line 3: "  println(link)  checkbox"
                val line3Prefix = "  println("
                g2.color = mutedForeground
                g2.font = codeFont
                g2.drawString(line3Prefix, codeIndent, line3Y + textOffsetY)

                // "link" text in accent color (underlined like a real link)
                val linkX = codeIndent + fm.stringWidth(line3Prefix)
                drawWithAlpha(g2, AccentElementId.LINKS) {
                    val linkColor = elementColor(AccentElementId.LINKS, accent, dimmedAccent, mutedForeground)
                    g2.color = linkColor
                    g2.drawString("link", linkX, line3Y + textOffsetY)
                    val linkTextWidth = fm.stringWidth("link")
                    g2.fillRect(
                        linkX,
                        line3Y + textOffsetY + JBUI.scale(LINK_UNDERLINE_OFFSET),
                        linkTextWidth,
                        JBUI.scale(LINK_UNDERLINE_HEIGHT),
                    )
                }

                // ")" after the link
                val afterLinkX = linkX + fm.stringWidth("link")
                g2.color = mutedForeground
                g2.drawString(")", afterLinkX, line3Y + textOffsetY)

                // Mini checkbox glyph
                val checkboxX = afterLinkX + fm.stringWidth(")") + JBUI.scale(CHECKBOX_MARGIN)
                drawWithAlpha(g2, AccentElementId.CHECKBOXES) {
                    val checkColor = elementColor(AccentElementId.CHECKBOXES, accent, dimmedAccent, mutedForeground)
                    g2.color = checkColor
                    val cbSize = JBUI.scale(CHECKBOX_SIZE)
                    val cbY = line3Y + JBUI.scale(CHECKBOX_TOP)
                    g2.stroke = BasicStroke(JBUI.scale(1).toFloat() + CHECKBOX_STROKE_EXTRA)
                    g2.drawRoundRect(checkboxX, cbY, cbSize, cbSize, CHECKBOX_ARC, CHECKBOX_ARC)
                    // Checkmark
                    g2.drawLine(
                        checkboxX + JBUI.scale(CHECKMARK_X1),
                        cbY + JBUI.scale(CHECKMARK_Y1),
                        checkboxX + JBUI.scale(CHECKMARK_X2),
                        cbY + JBUI.scale(CHECKMARK_Y2),
                    )
                    g2.drawLine(
                        checkboxX + JBUI.scale(CHECKMARK_X2),
                        cbY + JBUI.scale(CHECKMARK_Y2),
                        checkboxX + JBUI.scale(CHECKMARK_X3),
                        cbY + JBUI.scale(CHECKMARK_Y3),
                    )
                }

                // Line 4: "}"
                g2.color = mutedForeground
                g2.font = codeFont
                g2.drawString("}", codeIndent, line4Y + textOffsetY)

                // Scrollbar thumb
                drawWithAlpha(g2, AccentElementId.SCROLLBAR) {
                    val scrollColor = elementColor(AccentElementId.SCROLLBAR, accent, dimmedAccent, mutedForeground)
                    g2.color = Color(scrollColor.red, scrollColor.green, scrollColor.blue, SCROLLBAR_ALPHA)
                    val scrollThumbY = editorTop + JBUI.scale(SCROLL_THUMB_OFFSET)
                    val scrollThumbHeight = JBUI.scale(SCROLL_THUMB_HEIGHT)
                    g2.fillRoundRect(
                        width - scrollbarWidth + JBUI.scale(SCROLLBAR_PADDING),
                        scrollThumbY,
                        scrollbarWidth - JBUI.scale(SCROLLBAR_INSET),
                        scrollThumbHeight,
                        SCROLLBAR_ARC,
                        SCROLLBAR_ARC,
                    )
                }

                // Progress bar
                drawWithAlpha(g2, AccentElementId.PROGRESS_BAR) {
                    val progressColor =
                        elementColor(AccentElementId.PROGRESS_BAR, accent, dimmedAccent, mutedForeground)
                    g2.color = progressColor
                    val progressWidth = (width * PROGRESS_WIDTH_FRACTION).toInt()
                    g2.fillRect(0, editorBottom, progressWidth, progressHeight)
                    g2.color = Color(progressColor.red, progressColor.green, progressColor.blue, PROGRESS_TRACK_ALPHA)
                    g2.fillRect(progressWidth, editorBottom, width - progressWidth, progressHeight)
                }

                // Annotation label when an element is highlighted
                val target = highlightedElement
                if (target != null) {
                    val layout = EditorLayout(
                        width, height, tabHeight, editorTop, editorBottom,
                        line1Y, line2Y, line3Y, scrollbarWidth,
                    )
                    paintAnnotationLabel(g2, target, codeFont, accent, layout)
                }

                // Glow effect around editor area
                if (previewGlowEnabled) {
                    val editorBounds = Rectangle(0, editorTop, width, editorBottom - editorTop)
                    glowRenderer.ensureCache(accent, previewGlowStyle, previewGlowIntensity, previewGlowWidth)
                    glowRenderer.paintGlow(g2, editorBounds, previewGlowWidth, GLOW_ARC_RADIUS)
                }
            } finally {
                g2.dispose()
            }
        }

        private fun paintAnnotationLabel(
            g2: Graphics2D,
            target: AccentElementId,
            codeFont: Font,
            accent: Color,
            layout: EditorLayout,
        ) {
            val labelText = target.displayName
            val labelFont = codeFont.deriveFont(Font.BOLD)
            g2.font = labelFont
            val labelFm = g2.getFontMetrics(labelFont)
            val labelWidth = labelFm.stringWidth(labelText) + JBUI.scale(LABEL_PADDING_H)
            val labelHeight = labelFm.height + JBUI.scale(LABEL_PADDING_V)
            val (labelX, labelY) = annotationPosition(target, layout)
            val clampMargin = JBUI.scale(LABEL_CLAMP_MARGIN)
            val clampedX =
                labelX.coerceIn(clampMargin, layout.width - labelWidth - clampMargin)
            val clampedY =
                labelY.coerceIn(clampMargin, layout.height - labelHeight - clampMargin)
            g2.color = Color(accent.red, accent.green, accent.blue, LABEL_ALPHA)
            g2.fillRoundRect(clampedX, clampedY, labelWidth, labelHeight, LABEL_ARC, LABEL_ARC)
            g2.color = contrastTextColor(accent)
            g2.drawString(
                labelText,
                clampedX + JBUI.scale(LABEL_TEXT_OFFSET_X),
                clampedY + labelFm.ascent + JBUI.scale(LABEL_TEXT_OFFSET_Y),
            )
        }

        private inline fun drawWithAlpha(
            g2: Graphics2D,
            id: AccentElementId,
            block: () -> Unit,
        ) {
            val alpha = elementAlpha(id)
            if (alpha >= 1.0f) {
                block()
            } else {
                val saved = g2.composite
                g2.composite = AlphaComposite.SrcOver.derive(alpha)
                block()
                g2.composite = saved
            }
        }

        private fun elementAlpha(id: AccentElementId): Float {
            val target = highlightedElement ?: return 1.0f
            return if (id == target) 1.0f else DIMMED_ELEMENT_ALPHA
        }

        private fun elementColor(
            id: AccentElementId,
            accent: Color,
            dimmed: Color,
            muted: Color,
        ): Color {
            if (id in previewConflicts) return dimmed
            return if (isEnabled(id)) accent else muted
        }

        private fun isEnabled(id: AccentElementId): Boolean = previewToggles[id] ?: true

        private fun annotationPosition(
            id: AccentElementId,
            layout: EditorLayout,
        ): Pair<Int, Int> =
            when (id) {
                AccentElementId.TAB_UNDERLINES ->
                    Pair(JBUI.scale(LABEL_TAB_X), layout.tabHeight - JBUI.scale(LABEL_TAB_Y_OFFSET))
                AccentElementId.CARET_ROW ->
                    Pair(JBUI.scale(LABEL_CARET_X), layout.line1Y - JBUI.scale(LABEL_CLAMP_MARGIN))
                AccentElementId.SEARCH_RESULTS ->
                    Pair(JBUI.scale(LABEL_SEARCH_X), layout.line2Y - JBUI.scale(LABEL_LINE_Y_OFFSET))
                AccentElementId.BRACKET_MATCH ->
                    Pair(JBUI.scale(LABEL_BRACKET_X), layout.line2Y - JBUI.scale(LABEL_LINE_Y_OFFSET))
                AccentElementId.LINKS ->
                    Pair(JBUI.scale(LABEL_LINKS_X), layout.line3Y - JBUI.scale(LABEL_LINE_Y_OFFSET))
                AccentElementId.CHECKBOXES ->
                    Pair(JBUI.scale(LABEL_CHECKBOXES_X), layout.line3Y - JBUI.scale(LABEL_LINE_Y_OFFSET))
                AccentElementId.SCROLLBAR ->
                    Pair(
                        layout.width - layout.scrollbarWidth - JBUI.scale(LABEL_SCROLLBAR_X_OFFSET),
                        layout.editorTop + JBUI.scale(LABEL_SCROLLBAR_Y_OFFSET),
                    )
                AccentElementId.PROGRESS_BAR ->
                    Pair(
                        JBUI.scale(LABEL_PROGRESS_X),
                        layout.editorBottom - JBUI.scale(LABEL_PROGRESS_Y_OFFSET),
                    )
            }

        private fun contrastTextColor(background: Color): Color {
            val luminance =
                (RED_LUMINANCE_WEIGHT * background.red +
                    GREEN_LUMINANCE_WEIGHT * background.green +
                    BLUE_LUMINANCE_WEIGHT * background.blue) / MAX_COLOR_VALUE
            return if (luminance > LUMINANCE_THRESHOLD) {
                Color(DARK_TEXT_RGB, DARK_TEXT_RGB, DARK_TEXT_RGB)
            } else {
                Color(LIGHT_TEXT_RGB, LIGHT_TEXT_RGB, LIGHT_TEXT_RGB)
            }
        }

        private fun parseColor(hex: String): Color =
            try {
                Color.decode(hex)
            } catch (_: NumberFormatException) {
                Color(FALLBACK_RED, FALLBACK_GREEN, FALLBACK_BLUE)
            }

        private fun darken(
            color: Color,
            factor: Float,
        ): Color {
            val red = (color.red * (1 - factor)).toInt().coerceIn(0, MAX_COLOR_CHANNEL)
            val green = (color.green * (1 - factor)).toInt().coerceIn(0, MAX_COLOR_CHANNEL)
            val blue = (color.blue * (1 - factor)).toInt().coerceIn(0, MAX_COLOR_CHANNEL)
            return Color(red, green, blue)
        }

    }

    companion object {
        const val HEADER_INDENT = 32
        const val PREVIEW_GLOW_DEFAULT_INTENSITY = 40

        // Panel dimensions
        const val PANEL_WIDTH = 300
        const val PANEL_HEIGHT = 170

        // Tab dimensions
        const val TAB_HEIGHT = 22
        const val TAB_PADDING = 8
        const val TAB_GAP = 2
        const val TAB_BOTTOM_MARGIN = 6
        const val TAB_WIDTH = 90

        // Scrollbar
        const val SCROLLBAR_WIDTH = 8

        // Progress
        const val PROGRESS_HEIGHT = 4

        // Line layout
        const val LINE_HEIGHT = 20
        const val TEXT_OFFSET_Y = 14
        const val CODE_INDENT = 12
        const val LINE_GAP = 4
        const val LINE_START_OFFSET = 6

        // Tab underline
        const val UNDERLINE_HEIGHT = 3

        // Alpha values
        const val DIMMED_ALPHA = 77
        const val CARET_ROW_ALPHA = 30
        const val SEARCH_HIGHLIGHT_ALPHA = 60
        const val BRACKET_ALPHA = 70
        const val SCROLLBAR_ALPHA = 160
        const val PROGRESS_TRACK_ALPHA = 40

        // Scrollbar geometry
        const val SCROLLBAR_PADDING = 1
        const val SCROLLBAR_INSET = 2
        const val SCROLLBAR_ARC = 4
        const val SCROLL_THUMB_HEIGHT = 36
        const val SCROLL_THUMB_OFFSET = 6

        // Checkbox
        const val CHECKBOX_SIZE = 12
        const val CHECKBOX_MARGIN = 12
        const val CHECKBOX_TOP = 4
        const val CHECKBOX_ARC = 3
        const val CHECKMARK_X1 = 3
        const val CHECKMARK_Y1 = 6
        const val CHECKMARK_X2 = 5
        const val CHECKMARK_Y2 = 9
        const val CHECKMARK_X3 = 9
        const val CHECKMARK_Y3 = 3

        // Annotation label
        const val LABEL_PADDING_H = 10
        const val LABEL_PADDING_V = 6
        const val LABEL_TEXT_OFFSET_X = 5
        const val LABEL_TEXT_OFFSET_Y = 3
        const val LABEL_ARC = 8
        const val LABEL_ALPHA = 220
        const val LABEL_CLAMP_MARGIN = 2

        // Annotation label positions
        const val LABEL_TAB_X = 100
        const val LABEL_TAB_Y_OFFSET = 20
        const val LABEL_CARET_X = 170
        const val LABEL_SEARCH_X = 10
        const val LABEL_LINE_Y_OFFSET = 16
        const val LABEL_BRACKET_X = 130
        const val LABEL_LINKS_X = 100
        const val LABEL_CHECKBOXES_X = 180
        const val LABEL_SCROLLBAR_X_OFFSET = 80
        const val LABEL_SCROLLBAR_Y_OFFSET = 4
        const val LABEL_PROGRESS_X = 10
        const val LABEL_PROGRESS_Y_OFFSET = 20

        // Glow arc
        const val GLOW_ARC_RADIUS = 8

        // Luminance weights
        const val RED_LUMINANCE_WEIGHT = 0.299
        const val GREEN_LUMINANCE_WEIGHT = 0.587
        const val BLUE_LUMINANCE_WEIGHT = 0.114
        const val LUMINANCE_THRESHOLD = 0.5
        const val MAX_COLOR_VALUE = 255.0

        // Dark/light text RGB values
        const val DARK_TEXT_RGB = 0x1A
        const val LIGHT_TEXT_RGB = 0xFF

        // Fallback colors
        const val FALLBACK_RED = 0xFF
        const val FALLBACK_GREEN = 0xCC
        const val FALLBACK_BLUE = 0x66

        // Editor background factor
        const val DARKEN_FACTOR = 0.15f

        // Fallback panel background
        const val FALLBACK_BG_RED = 0x1F
        const val FALLBACK_BG_GREEN = 0x24
        const val FALLBACK_BG_BLUE = 0x30

        // Muted foreground
        const val MUTED_FG_CHANNEL = 0x60

        // Element alpha
        const val DIMMED_ELEMENT_ALPHA = 0.25f

        // Stroke
        const val CHECKBOX_STROKE_EXTRA = 0.5f
        const val LINK_UNDERLINE_OFFSET = 1
        const val LINK_UNDERLINE_HEIGHT = 1
        const val SEARCH_PADDING = 4
        const val BRACKET_PADDING = 2

        // Progress width
        const val PROGRESS_WIDTH_FRACTION = 0.6

        // Max color channel for darkening
        const val MAX_COLOR_CHANNEL = 255
    }
}
