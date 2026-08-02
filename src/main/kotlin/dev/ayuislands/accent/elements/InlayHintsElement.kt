package dev.ayuislands.accent.elements

import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.ui.ColorUtil
import com.intellij.ui.JBColor
import dev.ayuislands.accent.AccentElement
import dev.ayuislands.accent.AccentElementId
import dev.ayuislands.accent.AyuVariant
import dev.ayuislands.theme.AyuEditorSchemeScope
import dev.ayuislands.theme.EditorSchemeOwner
import java.awt.Color

private const val MUTED_ALPHA = 140
private val INLAY_KEY = TextAttributesKey.find("INLAY_TEXT_WITHOUT_BACKGROUND")

class InlayHintsElement : AccentElement {
    override val id = AccentElementId.INLAY_HINTS
    override val displayName = "Inlay Hints"
    private val schemeOwner = EditorSchemeOwner.Element(id)

    override fun apply(color: Color) {
        val mutedAccent = ColorUtil.toAlpha(color, MUTED_ALPHA)
        val muted = JBColor(mutedAccent, mutedAccent)
        val scheme = AyuEditorSchemeScope.activeScheme() ?: return
        val existing = scheme.getAttributes(INLAY_KEY)
        val updated = existing?.clone() ?: TextAttributes()
        updated.foregroundColor = muted
        AyuEditorSchemeScope.writeAttributes(scheme, schemeOwner, INLAY_KEY, updated)
    }

    override fun applyNeutral(variant: AyuVariant) {
        AyuEditorSchemeScope.restore(schemeOwner)
    }

    override fun revert() {
        AyuEditorSchemeScope.restore(schemeOwner)
    }
}
