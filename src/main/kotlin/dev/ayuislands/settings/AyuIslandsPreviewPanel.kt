package dev.ayuislands.settings

import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.Panel
import com.intellij.util.ui.JBUI
import dev.ayuislands.accent.AccentElementId
import dev.ayuislands.accent.AyuVariant
import dev.ayuislands.glow.GlowRenderer
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Rectangle
import java.awt.RenderingHints
import javax.swing.JComponent
import javax.swing.UIManager

/** Visual preview mockup of all 8 accent elements, reacts to color/toggle/glow changes. */
class AyuIslandsPreviewPanel : AyuIslandsSettingsPanel() {

    var previewAccentHex: String = "#FFCC66"
    var previewToggles: Map<AccentElementId, Boolean> = emptyMap()
    var previewGlowEnabled: Boolean = true
    var previewConflicts: Set<AccentElementId> = emptySet()

    private var mockupComponent: AccentPreviewComponent? = null

    override fun buildPanel(panel: Panel, variant: AyuVariant) {
        previewAccentHex = variant.defaultAccent

        val mockup = AccentPreviewComponent(variant)
        mockupComponent = mockup

        panel.group("Preview") {
            row { cell(mockup).resizableColumn().align(Align.FILL) }
        }
    }

    fun updatePreview() {
        mockupComponent?.repaint()
    }

    override fun isModified(): Boolean = false
    override fun apply() {}
    override fun reset() {}

    private inner class AccentPreviewComponent(private val variant: AyuVariant) : JComponent() {

        private val glowRenderer = GlowRenderer()

        init {
            preferredSize = Dimension(0, JBUI.scale(80))
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
                val dimmedAccent = Color(accent.red, accent.green, accent.blue, 77) // ~30% alpha

                val width = width
                val height = height
                val tabHeight = JBUI.scale(20)
                val scrollbarWidth = JBUI.scale(6)
                val progressHeight = JBUI.scale(3)

                // Background
                g2.color = panelBackground
                g2.fillRect(0, 0, width, height)

                // Tab strip area
                g2.color = editorBackground
                g2.fillRect(0, 0, width, tabHeight)

                // Mock tabs
                val tabNames = arrayOf("main.kt", "build.gradle", "README.md")
                val tabWidth = JBUI.scale(80)
                for (index in tabNames.indices) {
                    val tabX = index * (tabWidth + JBUI.scale(2))
                    val tabForeground = if (index == 0) accent else mutedForeground
                    g2.color = tabForeground
                    g2.font = JBUI.Fonts.smallFont()
                    g2.drawString(tabNames[index], tabX + JBUI.scale(8), tabHeight - JBUI.scale(5))
                }

                // Tab underline (first tab)
                val tabUnderlineColor = elementColor(AccentElementId.TAB_UNDERLINES, accent, dimmedAccent, mutedForeground)
                g2.color = tabUnderlineColor
                g2.fillRect(0, tabHeight - JBUI.scale(2), tabWidth, JBUI.scale(2))

                // Editor area
                val editorTop = tabHeight
                val editorBottom = height - progressHeight
                g2.color = editorBackground
                g2.fillRect(0, editorTop, width - scrollbarWidth, editorBottom - editorTop)

                // Caret row highlight
                val caretRowY = editorTop + JBUI.scale(8)
                val caretRowHeight = JBUI.scale(16)
                val caretRowColor = elementColor(AccentElementId.CARET_ROW, accent, dimmedAccent, mutedForeground)
                g2.color = Color(caretRowColor.red, caretRowColor.green, caretRowColor.blue, 26)
                g2.fillRect(0, caretRowY, width - scrollbarWidth, caretRowHeight)

                // Mock code text on caret row
                g2.color = mutedForeground
                g2.font = JBUI.Fonts.smallFont()
                g2.drawString("fun main() {", JBUI.scale(12), caretRowY + JBUI.scale(12))

                // Bracket match highlights
                val bracketColor = elementColor(AccentElementId.BRACKET_MATCH, accent, dimmedAccent, mutedForeground)
                g2.color = Color(bracketColor.red, bracketColor.green, bracketColor.blue, 60)
                val bracketX1 = JBUI.scale(90)
                g2.fillRect(bracketX1, caretRowY, JBUI.scale(8), caretRowHeight)

                // Second line with search result
                val searchY = caretRowY + caretRowHeight + JBUI.scale(2)
                g2.color = mutedForeground
                g2.drawString("    println(\"Hello\")", JBUI.scale(12), searchY + JBUI.scale(12))

                // Search result highlight
                val searchColor = elementColor(AccentElementId.SEARCH_RESULTS, accent, dimmedAccent, mutedForeground)
                g2.color = Color(searchColor.red, searchColor.green, searchColor.blue, 40)
                g2.fillRect(JBUI.scale(60), searchY, JBUI.scale(40), JBUI.scale(14))

                // Link text
                val linkColor = elementColor(AccentElementId.LINKS, accent, dimmedAccent, mutedForeground)
                val linkY = searchY + JBUI.scale(18)
                g2.color = linkColor
                g2.font = JBUI.Fonts.smallFont()
                g2.drawString("See docs", JBUI.scale(12), linkY + JBUI.scale(10))
                // Underline
                val linkTextWidth = g2.fontMetrics.stringWidth("See docs")
                g2.drawLine(JBUI.scale(12), linkY + JBUI.scale(12), JBUI.scale(12) + linkTextWidth, linkY + JBUI.scale(12))

                // Checkbox icon (bottom-left area)
                val checkboxColor = elementColor(AccentElementId.CHECKBOXES, accent, dimmedAccent, mutedForeground)
                val checkboxX = JBUI.scale(12)
                val checkboxY = editorBottom - JBUI.scale(18)
                val checkboxSize = JBUI.scale(12)
                g2.color = checkboxColor
                g2.fillRoundRect(checkboxX, checkboxY, checkboxSize, checkboxSize, 2, 2)
                // Checkmark
                g2.color = Color.WHITE
                g2.stroke = BasicStroke(1.5f)
                g2.drawLine(
                    checkboxX + JBUI.scale(3), checkboxY + JBUI.scale(6),
                    checkboxX + JBUI.scale(5), checkboxY + JBUI.scale(9)
                )
                g2.drawLine(
                    checkboxX + JBUI.scale(5), checkboxY + JBUI.scale(9),
                    checkboxX + JBUI.scale(9), checkboxY + JBUI.scale(3)
                )

                // Scrollbar thumb (right edge)
                val scrollbarColor = elementColor(AccentElementId.SCROLLBAR, accent, dimmedAccent, mutedForeground)
                g2.color = Color(scrollbarColor.red, scrollbarColor.green, scrollbarColor.blue, 140)
                val scrollThumbY = editorTop + JBUI.scale(4)
                val scrollThumbHeight = JBUI.scale(20)
                g2.fillRoundRect(width - scrollbarWidth, scrollThumbY, scrollbarWidth, scrollThumbHeight, 4, 4)

                // Progress bar (bottom)
                val progressColor = elementColor(AccentElementId.PROGRESS_BAR, accent, dimmedAccent, mutedForeground)
                g2.color = progressColor
                val progressWidth = (width * 0.6).toInt()
                g2.fillRect(0, editorBottom, progressWidth, progressHeight)
                // Track
                g2.color = Color(progressColor.red, progressColor.green, progressColor.blue, 40)
                g2.fillRect(progressWidth, editorBottom, width - progressWidth, progressHeight)

                // Glow effect around editor area using GlowRenderer
                if (previewGlowEnabled) {
                    val editorBounds = Rectangle(0, editorTop, width, editorBottom - editorTop)
                    glowRenderer.ensureCache(accent)
                    glowRenderer.paintGlow(g2, editorBounds)
                }
            } finally {
                g2.dispose()
            }
        }

        private fun elementColor(id: AccentElementId, accent: Color, dimmed: Color, muted: Color): Color {
            if (id in previewConflicts) return dimmed
            return if (isEnabled(id)) accent else muted
        }

        private fun isEnabled(id: AccentElementId): Boolean {
            return previewToggles[id] ?: true
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
