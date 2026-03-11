package dev.ayuislands.accent.elements

import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.editor.markup.TextAttributes
import dev.ayuislands.accent.AccentElement
import dev.ayuislands.accent.AccentElementId
import dev.ayuislands.accent.AyuVariant
import java.awt.Color

class MatchingTagElement : AccentElement {
    override val id = AccentElementId.MATCHING_TAG
    override val displayName = "Matching Tag"

    private val tagAttrKey = TextAttributesKey.find("MATCHED_TAG_NAME")

    override fun apply(color: Color) {
        val scheme = EditorColorsManager.getInstance().globalScheme
        val existing = scheme.getAttributes(tagAttrKey)
        val updated = existing?.clone() ?: TextAttributes()
        updated.backgroundColor = Color(color.red, color.green, color.blue, TAG_BG_ALPHA)
        updated.foregroundColor = null
        scheme.setAttributes(tagAttrKey, updated)
    }

    override fun applyNeutral(variant: AyuVariant) {
        val scheme = EditorColorsManager.getInstance().globalScheme
        val parentScheme = EditorColorsManager.getInstance().getScheme(variant.parentSchemeName)
        val parentAttrs = parentScheme?.getAttributes(tagAttrKey)
        scheme.setAttributes(tagAttrKey, parentAttrs ?: TextAttributes())
    }

    override fun revert() {
        val scheme = EditorColorsManager.getInstance().globalScheme
        val fallback = tagAttrKey.fallbackAttributeKey
        val defaultAttrs = if (fallback != null) scheme.getAttributes(fallback) else null
        scheme.setAttributes(tagAttrKey, defaultAttrs ?: TextAttributes())
    }

    companion object {
        private const val TAG_BG_ALPHA = 0x30
    }
}
