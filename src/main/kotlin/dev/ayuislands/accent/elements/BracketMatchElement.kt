package dev.ayuislands.accent.elements

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
        val scheme = AyuEditorSchemeScope.claimActiveScheme() ?: return
        val existing = scheme.getAttributes(braceAttrKey)
        val updated = existing?.clone() ?: TextAttributes()
        updated.foregroundColor = color
        updated.fontType = Font.BOLD
        AyuEditorSchemeScope.writeAttributes(schemeOwner, braceAttrKey, updated)
        BracketFadeManager.activate(color)
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
