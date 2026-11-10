package dev.ayuislands.accent.elements

import com.intellij.openapi.editor.colors.EditorColorsScheme
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.editor.markup.TextAttributes
import dev.ayuislands.accent.AccentElement
import dev.ayuislands.accent.AccentElementId
import dev.ayuislands.accent.AyuVariant
import dev.ayuislands.theme.AyuEditorSchemeScope
import dev.ayuislands.theme.EditorSchemeOwner
import java.awt.Color
import java.awt.Font

class BracketMatchElement : AccentElement {
    override val id = AccentElementId.BRACKET_MATCH
    override val displayName = "Bracket Match"
    private val schemeOwner = EditorSchemeOwner.Element(id)

    private val braceAttrKey = TextAttributesKey.find("MATCHED_BRACE_ATTRIBUTES")

    override fun apply(color: Color) {
        val scheme = AyuEditorSchemeScope.activeScheme() ?: return
        for ((key, value) in editorAttributes(scheme, color)) {
            AyuEditorSchemeScope.writeAttributes(scheme, schemeOwner, key, value)
        }
        BracketFadeManager.activate(color)
    }

    internal fun editorAttributes(
        scheme: EditorColorsScheme,
        color: Color,
    ): Map<TextAttributesKey, TextAttributes> {
        val existing = scheme.getAttributes(braceAttrKey)
        val updated = existing?.clone() ?: TextAttributes()
        updated.foregroundColor = color
        updated.fontType = Font.BOLD
        return mapOf(braceAttrKey to updated)
    }

    override fun applyNeutral(variant: AyuVariant) {
        AyuEditorSchemeScope.restore(schemeOwner)
        BracketFadeManager.deactivate()
    }

    override fun revert() {
        AyuEditorSchemeScope.restore(schemeOwner)
        BracketFadeManager.deactivate()
    }
}
