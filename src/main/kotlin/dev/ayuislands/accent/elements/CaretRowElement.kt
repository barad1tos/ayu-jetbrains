package dev.ayuislands.accent.elements

import com.intellij.openapi.editor.colors.ColorKey
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.ui.ColorUtil
import dev.ayuislands.accent.AccentElement
import dev.ayuislands.accent.AccentElementId
import dev.ayuislands.accent.AyuVariant
import dev.ayuislands.theme.AyuEditorSchemeScope
import java.awt.Color

class CaretRowElement : AccentElement {
    override val id = AccentElementId.CARET_ROW
    override val displayName = "Caret Row"

    private val caretRowKey = ColorKey.find("CARET_ROW_COLOR")
    private val caretKey = ColorKey.find("CARET_COLOR")
    private val lineNumberKey = ColorKey.find("LINE_NUMBER_ON_CARET_ROW_COLOR")

    override fun apply(color: Color) {
        val caretRowColor = ColorUtil.toAlpha(color, CARET_ROW_ALPHA)
        val scheme = AyuEditorSchemeScope.claimActiveScheme() ?: return
        scheme.setColor(caretRowKey, caretRowColor)
        scheme.setColor(caretKey, color)
        scheme.setColor(lineNumberKey, color)
    }

    override fun applyNeutral(variant: AyuVariant) {
        val scheme = AyuEditorSchemeScope.claimActiveScheme() ?: return
        val parentScheme = EditorColorsManager.getInstance().getScheme(variant.parentSchemeName)
        for (colorKey in listOf(caretRowKey, caretKey, lineNumberKey)) {
            scheme.setColor(colorKey, parentScheme?.getColor(colorKey))
        }
    }

    companion object {
        private const val CARET_ROW_ALPHA = 0x1A
    }

    override fun revert() {
        AyuEditorSchemeScope.cleanClaimedAccentSchemes { scheme ->
            scheme.setColor(caretRowKey, null)
            scheme.setColor(caretKey, null)
            scheme.setColor(lineNumberKey, null)
        }
    }
}
