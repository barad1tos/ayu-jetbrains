package dev.ayuislands.accent.elements

import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.ui.ColorUtil
import com.intellij.ui.JBColor
import dev.ayuislands.accent.AccentElement
import dev.ayuislands.accent.AccentElementId
import dev.ayuislands.accent.AyuVariant
import dev.ayuislands.theme.AyuEditorSchemeScope
import java.awt.Color

private const val MUTED_ALPHA = 140
private val INLAY_KEY = TextAttributesKey.find("INLAY_TEXT_WITHOUT_BACKGROUND")

class InlayHintsElement : AccentElement {
    override val id = AccentElementId.INLAY_HINTS
    override val displayName = "Inlay Hints"

    override fun apply(color: Color) {
        val mutedAccent = ColorUtil.toAlpha(color, MUTED_ALPHA)
        val muted = JBColor(mutedAccent, mutedAccent)
        val scheme = AyuEditorSchemeScope.activeScheme() ?: return
        val existing = scheme.getAttributes(INLAY_KEY)
        val updated = existing?.clone() ?: TextAttributes()
        updated.foregroundColor = muted
        scheme.setAttributes(INLAY_KEY, updated)
    }

    override fun applyNeutral(variant: AyuVariant) {
        val scheme = AyuEditorSchemeScope.activeScheme() ?: return
        val parentScheme = EditorColorsManager.getInstance().getScheme(variant.parentSchemeName)
        scheme.setAttributes(
            INLAY_KEY,
            parentScheme?.getAttributes(INLAY_KEY),
        )
    }

    override fun revert() {
        val scheme = AyuEditorSchemeScope.activeScheme() ?: return
        scheme.setAttributes(INLAY_KEY, null)
    }
}
