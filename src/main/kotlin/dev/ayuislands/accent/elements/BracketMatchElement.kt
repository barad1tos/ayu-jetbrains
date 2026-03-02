package dev.ayuislands.accent.elements

import com.intellij.openapi.editor.colors.ColorKey
import com.intellij.openapi.editor.colors.EditorColorsManager
import dev.ayuislands.accent.AccentElement
import dev.ayuislands.accent.AccentElementId
import dev.ayuislands.accent.AyuVariant
import java.awt.Color
import javax.swing.SwingUtilities

class BracketMatchElement : AccentElement {

    override val id = AccentElementId.BRACKET_MATCH
    override val displayName = "Bracket Match"

    private val matchedTextKey = ColorKey.find("MATCHED_TEXT")

    override fun apply(color: Color) {
        val edtWork = Runnable {
            val scheme = EditorColorsManager.getInstance().globalScheme
            scheme.setColor(matchedTextKey, color)
        }
        if (SwingUtilities.isEventDispatchThread()) {
            edtWork.run()
        } else {
            SwingUtilities.invokeLater(edtWork)
        }
    }

    override fun applyNeutral(variant: AyuVariant) {
        val edtWork = Runnable {
            val parentScheme = EditorColorsManager.getInstance().getScheme(variant.parentSchemeName)
            val scheme = EditorColorsManager.getInstance().globalScheme
            scheme.setColor(matchedTextKey, parentScheme?.getColor(matchedTextKey))
        }
        if (SwingUtilities.isEventDispatchThread()) {
            edtWork.run()
        } else {
            SwingUtilities.invokeLater(edtWork)
        }
    }

    override fun revert() {
        val edtWork = Runnable {
            val scheme = EditorColorsManager.getInstance().globalScheme
            scheme.setColor(matchedTextKey, null)
        }
        if (SwingUtilities.isEventDispatchThread()) {
            edtWork.run()
        } else {
            SwingUtilities.invokeLater(edtWork)
        }
    }
}
