package dev.ayuislands.syntax

import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.ui.JBColor
import java.awt.Font

internal object IgnoreSyntaxColors {
    fun resolve(
        computed: Map<TextAttributesKey, TextAttributes>,
        variantTag: String,
        isEnabled: Boolean,
    ): Map<TextAttributesKey, TextAttributes> {
        if (isEnabled) return computed

        val stock = if (variantTag == "Light") LIGHT_STOCK else DARK_STOCK
        return LinkedHashMap(computed).apply {
            stock.forEach { (name, style) -> put(TextAttributesKey.find(name), style.toTextAttributes()) }
        }
    }

    private const val IGNORE_COMMENT_KEY = "IGNORE.COMMENT"
    private const val IGNORE_SECTION_KEY = "IGNORE.SECTION"
    private const val IGNORE_HEADER_KEY = "IGNORE.HEADER"
    private const val IGNORE_NEGATION_KEY = "IGNORE.NEGATION"
    private const val IGNORE_BRACKET_KEY = "IGNORE.BRACKET"
    private const val IGNORE_SLASH_KEY = "IGNORE.SLASH"
    private const val IGNORE_SYNTAX_KEY = "IGNORE.SYNTAX"
    private const val IGNORE_VALUE_KEY = "IGNORE.VALUE"
    private const val IGNORE_UNUSED_ENTRY_KEY = "IGNORE.UNUSED_ENTRY"

    private val DARK_STOCK =
        mapOf(
            IGNORE_COMMENT_KEY to StockStyle(foregroundRgb = 0x808080),
            IGNORE_SECTION_KEY to StockStyle(foregroundRgb = 0x8C8C8C, backgroundRgb = 0x3A3A3A),
            IGNORE_HEADER_KEY to
                StockStyle(
                    foregroundRgb = 0x8C8C8C,
                    backgroundRgb = 0x3A3A3A,
                    fontType = Font.BOLD,
                ),
            IGNORE_NEGATION_KEY to StockStyle(foregroundRgb = 0xCC7832, fontType = Font.BOLD),
            IGNORE_BRACKET_KEY to StockStyle(foregroundRgb = 0xCC7832, fontType = Font.BOLD),
            IGNORE_SLASH_KEY to StockStyle(foregroundRgb = 0x808080),
            IGNORE_SYNTAX_KEY to
                StockStyle(
                    foregroundRgb = 0xACACAC,
                    backgroundRgb = 0x4A4A4A,
                    fontType = Font.BOLD,
                ),
            IGNORE_VALUE_KEY to StockStyle(foregroundRgb = 0x629755),
            IGNORE_UNUSED_ENTRY_KEY to StockStyle(foregroundRgb = 0x808080, fontType = Font.ITALIC),
        )

    private val LIGHT_STOCK =
        mapOf(
            IGNORE_COMMENT_KEY to StockStyle(foregroundRgb = 0x808080),
            IGNORE_SECTION_KEY to StockStyle(foregroundRgb = 0x808080, backgroundRgb = 0xECFAEB),
            IGNORE_HEADER_KEY to
                StockStyle(
                    foregroundRgb = 0x808080,
                    backgroundRgb = 0xECFAEB,
                    fontType = Font.BOLD,
                ),
            IGNORE_NEGATION_KEY to StockStyle(foregroundRgb = 0xCC7832, fontType = Font.BOLD),
            IGNORE_BRACKET_KEY to StockStyle(foregroundRgb = 0xCC7832, fontType = Font.BOLD),
            IGNORE_SLASH_KEY to StockStyle(foregroundRgb = 0x808080),
            IGNORE_SYNTAX_KEY to
                StockStyle(
                    foregroundRgb = 0xACACAC,
                    backgroundRgb = 0x4A4A4A,
                    fontType = Font.BOLD,
                ),
            IGNORE_VALUE_KEY to StockStyle(foregroundRgb = 0x5C9F30),
            IGNORE_UNUSED_ENTRY_KEY to StockStyle(foregroundRgb = 0x808080, fontType = Font.ITALIC),
        )

    private data class StockStyle(
        val foregroundRgb: Int,
        val backgroundRgb: Int? = null,
        val fontType: Int = Font.PLAIN,
    ) {
        // Same value in both themes on purpose: these replicate the .ignore plugin's
        // own stock colours, which do not vary with the IDE theme.
        fun toTextAttributes(): TextAttributes =
            TextAttributes().also { attributes ->
                attributes.foregroundColor = JBColor(foregroundRgb, foregroundRgb)
                attributes.backgroundColor = backgroundRgb?.let { JBColor(it, it) }
                attributes.fontType = fontType
            }
    }
}
