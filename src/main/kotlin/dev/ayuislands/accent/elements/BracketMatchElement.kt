package dev.ayuislands.accent.elements

import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.editor.markup.TextAttributes
import dev.ayuislands.accent.AccentElement
import dev.ayuislands.accent.AccentElementId
import dev.ayuislands.accent.AyuVariant
import java.awt.Color
import java.awt.Font

class BracketMatchElement : AccentElement {
    override val id = AccentElementId.BRACKET_MATCH
    override val displayName = "Bracket Match"

    private val braceAttrKey = TextAttributesKey.find("MATCHED_BRACE_ATTRIBUTES")

    override fun apply(color: Color) {
        val scheme = EditorColorsManager.getInstance().globalScheme
        val existing = scheme.getAttributes(braceAttrKey)
        val updated = existing?.clone() ?: TextAttributes()
        updated.foregroundColor = color
        updated.fontType = Font.BOLD
        scheme.setAttributes(braceAttrKey, updated)
    }

    override fun applyNeutral(variant: AyuVariant) {
        val scheme = EditorColorsManager.getInstance().globalScheme
        val parentScheme = EditorColorsManager.getInstance().getScheme(variant.parentSchemeName)
        val parentAttrs = parentScheme?.getAttributes(braceAttrKey)
        scheme.setAttributes(braceAttrKey, parentAttrs ?: TextAttributes())
    }

    override fun revert() {
        val scheme = EditorColorsManager.getInstance().globalScheme
        val fallback = braceAttrKey.fallbackAttributeKey
        val defaultAttrs = if (fallback != null) scheme.getAttributes(fallback) else null
        scheme.setAttributes(braceAttrKey, defaultAttrs ?: TextAttributes())
    }
}
