package dev.ayuislands.accent.elements

import com.intellij.openapi.editor.colors.ColorKey
import com.intellij.ui.ColorUtil
import dev.ayuislands.accent.AccentElement
import dev.ayuislands.accent.AccentElementId
import dev.ayuislands.accent.AyuVariant
import dev.ayuislands.theme.AyuEditorSchemeScope
import dev.ayuislands.theme.EditorSchemeOwner
import java.awt.Color

class CaretRowElement : AccentElement {
    override val id = AccentElementId.CARET_ROW
    override val displayName = "Caret Row"

    private val caretRowKey = ColorKey.find("CARET_ROW_COLOR")
    private val caretKey = ColorKey.find("CARET_COLOR")
    private val lineNumberKey = ColorKey.find("LINE_NUMBER_ON_CARET_ROW_COLOR")
    private val schemeOwner = EditorSchemeOwner.Element(id)

    override fun apply(color: Color) {
        val scheme = AyuEditorSchemeScope.activeScheme() ?: return
        val caretRowColor = ColorUtil.toAlpha(color, CARET_ROW_ALPHA)
        AyuEditorSchemeScope.writeColor(scheme, schemeOwner, caretRowKey, caretRowColor)
        AyuEditorSchemeScope.writeColor(scheme, schemeOwner, caretKey, color)
        AyuEditorSchemeScope.writeColor(scheme, schemeOwner, lineNumberKey, color)
    }

    override fun applyNeutral(variant: AyuVariant) {
        AyuEditorSchemeScope.restore(schemeOwner)
    }

    companion object {
        private const val CARET_ROW_ALPHA = 0x1A
    }

    override fun revert() {
        AyuEditorSchemeScope.restore(schemeOwner)
    }
}
