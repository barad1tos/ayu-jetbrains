package dev.ayuislands.settings

import com.intellij.ui.dsl.builder.Panel
import com.intellij.util.ui.JBUI
import java.awt.FlowLayout
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JPanel
import dev.ayuislands.accent.AccentElementId
import dev.ayuislands.accent.AyuVariant
import dev.ayuislands.glow.GlowAnimation
import dev.ayuislands.glow.GlowAnimator
import dev.ayuislands.glow.GlowRenderer
import dev.ayuislands.glow.GlowStyle
import java.awt.AlphaComposite
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Dimension
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Rectangle
import java.awt.RenderingHints
import javax.swing.JComponent
import javax.swing.UIManager

/** Visual preview mockup of all 8 accent elements, reacts to color/toggle/glow/highlight changes. */
class AyuIslandsPreviewPanel : AyuIslandsSettingsPanel() {

    var previewAccentHex: String = "#FFCC66"
    var previewToggles: Map<AccentElementId, Boolean> = emptyMap()
    var previewGlowEnabled: Boolean = true
    var previewConflicts: Set<AccentElementId> = emptySet()
    var highlightedElement: AccentElementId? = null

    // Glow style properties for contextual preview
    var previewGlowStyle: GlowStyle = GlowStyle.SOFT
    var previewGlowIntensity: Int = 40
    var previewGlowWidth: Int = GlowRenderer.DEFAULT_GLOW_WIDTH
    var previewIslandToggles: Map<String, Boolean> = emptyMap()

    // Animation preview
    private var previewAnimator: GlowAnimator? = null
    private var animationAlpha: Float = 1.0f

    private var mockupComponent: AccentPreviewComponent? = null

    override fun buildPanel(panel: Panel, variant: AyuVariant) {
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
        wrapper.add(Box.createHorizontalStrut(JBUI.scale(32)))
        wrapper.add(mockup)
        return wrapper
    }

    fun updatePreview() {
        mockupComponent?.repaint()
    }

    fun startAnimationPreview(animation: GlowAnimation) {
        stopAnimationPreview()
        if (animation == GlowAnimation.NONE) {
            animationAlpha = 1.0f
            updatePreview()
            return
        }
        previewAnimator = GlowAnimator().also { animator ->
            animator.start(animation) { alpha ->
                animationAlpha = alpha
                mockupComponent?.repaint()
            }
        }
    }

    fun stopAnimationPreview() {
        previewAnimator?.stop()
        previewAnimator = null
        animationAlpha = 1.0f
    }

    override fun isModified(): Boolean = false
    override fun apply() {}
    override fun reset() {}

    private inner class AccentPreviewComponent(private val variant: AyuVariant) : JComponent() {

        private val glowRenderer = GlowRenderer()

        init {
            preferredSize = Dimension(JBUI.scale(300), JBUI.scale(170))
        }

        override fun paintComponent(graphics: Graphics) {
            val g2 = graphics.create() as Graphics2D
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)

                val panelBackground = UIManager.getColor("Panel.background") ?: Color(0x1F, 0x24, 0x30)
                val editorBackground = darken(panelBackground, 0.15f)
                val mutedForeground = UIManager.getColor("Label.disabledForeground") ?: Color(0x60, 0x60, 0x60)
                val accent = parseColor(previewAccentHex)
                val dimmedAccent = Color(accent.red, accent.green, accent.blue, 77)

                val width = width
                val height = height
                val tabHeight = JBUI.scale(22)
                val scrollbarWidth = JBUI.scale(8)
                val progressHeight = JBUI.scale(4)
                val lineHeight = JBUI.scale(20)
                val codeFont = JBUI.Fonts.smallFont()
                val textOffsetY = JBUI.scale(14)
                val codeIndent = JBUI.scale(12)

                // Background
                g2.color = panelBackground
                g2.fillRect(0, 0, width, height)

                // Tab strip area
                g2.color = editorBackground
                g2.fillRect(0, 0, width, tabHeight)

                // Mock tabs
                val tabNames = arrayOf("main.kt", "build.gradle")
                val tabWidth = JBUI.scale(90)
                g2.font = codeFont
                for (index in tabNames.indices) {
                    val tabX = index * (tabWidth + JBUI.scale(2))
                    g2.color = if (index == 0) accent else mutedForeground
                    g2.drawString(tabNames[index], tabX + JBUI.scale(8), tabHeight - JBUI.scale(6))
                }

                // Tab underline (first tab)
                drawWithAlpha(g2, AccentElementId.TAB_UNDERLINES) {
                    g2.color = elementColor(AccentElementId.TAB_UNDERLINES, accent, dimmedAccent, mutedForeground)
                    g2.fillRect(0, tabHeight - JBUI.scale(3), tabWidth, JBUI.scale(3))
                }

                // Editor area
                val editorTop = tabHeight
                val editorBottom = height - progressHeight
                g2.color = editorBackground
                g2.fillRect(0, editorTop, width - scrollbarWidth, editorBottom - editorTop)

                // Code line positions
                val line1Y = editorTop + JBUI.scale(6)
                val line2Y = line1Y + lineHeight + JBUI.scale(4)
                val line3Y = line2Y + lineHeight + JBUI.scale(4)
                val line4Y = line3Y + lineHeight + JBUI.scale(4)

                val fm = g2.getFontMetrics(codeFont)

                // Caret row highlight — line 1
                drawWithAlpha(g2, AccentElementId.CARET_ROW) {
                    val caretColor = elementColor(AccentElementId.CARET_ROW, accent, dimmedAccent, mutedForeground)
                    g2.color = Color(caretColor.red, caretColor.green, caretColor.blue, 30)
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
                    val searchColor = elementColor(AccentElementId.SEARCH_RESULTS, accent, dimmedAccent, mutedForeground)
                    g2.color = Color(searchColor.red, searchColor.green, searchColor.blue, 60)
                    g2.fillRect(codeIndent + prefixWidth, line2Y, valWidth + JBUI.scale(4), lineHeight)
                }

                // Bracket match on ( and )
                drawWithAlpha(g2, AccentElementId.BRACKET_MATCH) {
                    val bracketColor = elementColor(AccentElementId.BRACKET_MATCH, accent, dimmedAccent, mutedForeground)
                    g2.color = Color(bracketColor.red, bracketColor.green, bracketColor.blue, 70)
                    val openParenOffset = fm.stringWidth("  val x = ")
                    val openParenWidth = fm.stringWidth("(")
                    g2.fillRect(codeIndent + openParenOffset, line2Y, openParenWidth + JBUI.scale(2), lineHeight)
                    val closeParenOffset = fm.stringWidth("  val x = (1 + 2")
                    val closeParenWidth = fm.stringWidth(")")
                    g2.fillRect(codeIndent + closeParenOffset, line2Y, closeParenWidth + JBUI.scale(2), lineHeight)
                }

                // Line 3: "  println(link)  ☑"
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
                    g2.fillRect(linkX, line3Y + textOffsetY + JBUI.scale(1), linkTextWidth, JBUI.scale(1))
                }

                // ")" after link
                val afterLinkX = linkX + fm.stringWidth("link")
                g2.color = mutedForeground
                g2.drawString(")", afterLinkX, line3Y + textOffsetY)

                // Mini checkbox glyph
                val checkboxX = afterLinkX + fm.stringWidth(")") + JBUI.scale(12)
                drawWithAlpha(g2, AccentElementId.CHECKBOXES) {
                    val checkColor = elementColor(AccentElementId.CHECKBOXES, accent, dimmedAccent, mutedForeground)
                    g2.color = checkColor
                    val cbSize = JBUI.scale(12)
                    val cbY = line3Y + JBUI.scale(4)
                    g2.stroke = BasicStroke(JBUI.scale(1).toFloat() + 0.5f)
                    g2.drawRoundRect(checkboxX, cbY, cbSize, cbSize, 3, 3)
                    // Checkmark
                    g2.drawLine(checkboxX + JBUI.scale(3), cbY + JBUI.scale(6),
                        checkboxX + JBUI.scale(5), cbY + JBUI.scale(9))
                    g2.drawLine(checkboxX + JBUI.scale(5), cbY + JBUI.scale(9),
                        checkboxX + JBUI.scale(9), cbY + JBUI.scale(3))
                }

                // Line 4: "}"
                g2.color = mutedForeground
                g2.font = codeFont
                g2.drawString("}", codeIndent, line4Y + textOffsetY)

                // Scrollbar thumb
                drawWithAlpha(g2, AccentElementId.SCROLLBAR) {
                    val scrollColor = elementColor(AccentElementId.SCROLLBAR, accent, dimmedAccent, mutedForeground)
                    g2.color = Color(scrollColor.red, scrollColor.green, scrollColor.blue, 160)
                    val scrollThumbY = editorTop + JBUI.scale(6)
                    val scrollThumbHeight = JBUI.scale(36)
                    g2.fillRoundRect(width - scrollbarWidth + JBUI.scale(1), scrollThumbY,
                        scrollbarWidth - JBUI.scale(2), scrollThumbHeight, 4, 4)
                }

                // Progress bar
                drawWithAlpha(g2, AccentElementId.PROGRESS_BAR) {
                    val progressColor = elementColor(AccentElementId.PROGRESS_BAR, accent, dimmedAccent, mutedForeground)
                    g2.color = progressColor
                    val progressWidth = (width * 0.6).toInt()
                    g2.fillRect(0, editorBottom, progressWidth, progressHeight)
                    g2.color = Color(progressColor.red, progressColor.green, progressColor.blue, 40)
                    g2.fillRect(progressWidth, editorBottom, width - progressWidth, progressHeight)
                }

                // Annotation label when an element is highlighted
                val target = highlightedElement
                if (target != null) {
                    val labelText = elementDisplayName(target)
                    val labelFont = codeFont.deriveFont(Font.BOLD)
                    g2.font = labelFont
                    val labelFm = g2.getFontMetrics(labelFont)
                    val labelWidth = labelFm.stringWidth(labelText) + JBUI.scale(10)
                    val labelHeight = labelFm.height + JBUI.scale(6)
                    val (labelX, labelY) = annotationPosition(target, width, tabHeight, editorTop,
                        editorBottom, line1Y, line2Y, line3Y, scrollbarWidth)
                    // Clamp to mockup bounds
                    val clampedX = labelX.coerceIn(JBUI.scale(2), width - labelWidth - JBUI.scale(2))
                    val clampedY = labelY.coerceIn(JBUI.scale(2), height - labelHeight - JBUI.scale(2))
                    // Pill background
                    g2.color = Color(accent.red, accent.green, accent.blue, 220)
                    g2.fillRoundRect(clampedX, clampedY, labelWidth, labelHeight, 8, 8)
                    // Text
                    g2.color = contrastTextColor(accent)
                    g2.drawString(labelText, clampedX + JBUI.scale(5), clampedY + labelFm.ascent + JBUI.scale(3))
                }

                // Glow effect around editor area
                if (previewGlowEnabled) {
                    val editorBounds = Rectangle(0, editorTop, width, editorBottom - editorTop)
                    glowRenderer.ensureCache(accent, previewGlowStyle, previewGlowIntensity, previewGlowWidth)

                    if (animationAlpha < 1.0f) {
                        val composite = g2.composite
                        g2.composite = AlphaComposite.SrcOver.derive(animationAlpha)
                        glowRenderer.paintGlow(g2, editorBounds, previewGlowWidth, 8)
                        g2.composite = composite
                    } else {
                        glowRenderer.paintGlow(g2, editorBounds, previewGlowWidth, 8)
                    }
                }
            } finally {
                g2.dispose()
            }
        }

        private inline fun drawWithAlpha(g2: Graphics2D, id: AccentElementId, block: () -> Unit) {
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
            return if (id == target) 1.0f else 0.25f
        }

        private fun elementColor(id: AccentElementId, accent: Color, dimmed: Color, muted: Color): Color {
            if (id in previewConflicts) return dimmed
            return if (isEnabled(id)) accent else muted
        }

        private fun isEnabled(id: AccentElementId): Boolean {
            return previewToggles[id] ?: true
        }

        @Suppress("LongParameterList")
        private fun annotationPosition(
            id: AccentElementId,
            width: Int,
            tabHeight: Int,
            editorTop: Int,
            editorBottom: Int,
            line1Y: Int,
            line2Y: Int,
            line3Y: Int,
            scrollbarWidth: Int,
        ): Pair<Int, Int> = when (id) {
            AccentElementId.TAB_UNDERLINES -> Pair(JBUI.scale(100), tabHeight - JBUI.scale(20))
            AccentElementId.CARET_ROW -> Pair(JBUI.scale(170), line1Y - JBUI.scale(2))
            AccentElementId.SEARCH_RESULTS -> Pair(JBUI.scale(10), line2Y - JBUI.scale(16))
            AccentElementId.BRACKET_MATCH -> Pair(JBUI.scale(130), line2Y - JBUI.scale(16))
            AccentElementId.LINKS -> Pair(JBUI.scale(100), line3Y - JBUI.scale(16))
            AccentElementId.CHECKBOXES -> Pair(JBUI.scale(180), line3Y - JBUI.scale(16))
            AccentElementId.SCROLLBAR -> Pair(width - scrollbarWidth - JBUI.scale(80), editorTop + JBUI.scale(4))
            AccentElementId.PROGRESS_BAR -> Pair(JBUI.scale(10), editorBottom - JBUI.scale(20))
        }

        private fun elementDisplayName(id: AccentElementId): String = when (id) {
            AccentElementId.TAB_UNDERLINES -> "Tab Underlines"
            AccentElementId.CARET_ROW -> "Caret Row"
            AccentElementId.PROGRESS_BAR -> "Progress Bar"
            AccentElementId.SCROLLBAR -> "Scrollbar"
            AccentElementId.LINKS -> "Links"
            AccentElementId.BRACKET_MATCH -> "Bracket Match"
            AccentElementId.SEARCH_RESULTS -> "Search Results"
            AccentElementId.CHECKBOXES -> "Checkboxes"
        }

        private fun contrastTextColor(background: Color): Color {
            val luminance = (0.299 * background.red + 0.587 * background.green + 0.114 * background.blue) / 255.0
            return if (luminance > 0.5) Color(0x1A, 0x1A, 0x1A) else Color(0xFF, 0xFF, 0xFF)
        }

        private fun parseColor(hex: String): Color {
            return try {
                Color.decode(hex)
            } catch (_: NumberFormatException) {
                Color(0xFF, 0xCC, 0x66)
            }
        }

        private fun darken(color: Color, factor: Float): Color {
            val red = (color.red * (1 - factor)).toInt().coerceIn(0, 255)
            val green = (color.green * (1 - factor)).toInt().coerceIn(0, 255)
            val blue = (color.blue * (1 - factor)).toInt().coerceIn(0, 255)
            return Color(red, green, blue)
        }
    }
}
