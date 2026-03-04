package dev.ayuislands.accent.elements

import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.editor.markup.TextAttributes
import dev.ayuislands.accent.AccentElement
import dev.ayuislands.accent.AccentElementId
import dev.ayuislands.accent.AyuVariant
import java.awt.Color

class InlayHintsElement : AccentElement {
    override val id = AccentElementId.INLAY_HINTS
    override val displayName = "Inlay Hints"

    override fun apply(color: Color) {
        val muted = Color(color.red, color.green, color.blue, MUTED_ALPHA)
        val scheme = EditorColorsManager.getInstance().globalScheme
        val existing = scheme.getAttributes(INLAY_KEY)
        val updated = existing?.clone() ?: TextAttributes()
        updated.foregroundColor = muted
        scheme.setAttributes(INLAY_KEY, updated)
    }

    override fun applyNeutral(variant: AyuVariant) {
        val parentScheme = EditorColorsManager.getInstance().getScheme(variant.parentSchemeName)
        val scheme = EditorColorsManager.getInstance().globalScheme
        scheme.setAttributes(
            INLAY_KEY,
            parentScheme?.getAttributes(INLAY_KEY),
        )
    }

    override fun revert() {
        val scheme = EditorColorsManager.getInstance().globalScheme
        scheme.setAttributes(INLAY_KEY, null)
    }

    companion object {
        private const val MUTED_ALPHA = 140
        private val INLAY_KEY = TextAttributesKey.find("INLAY_TEXT_WITHOUT_BACKGROUND")
    }
}
