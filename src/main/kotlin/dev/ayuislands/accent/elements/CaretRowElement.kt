package dev.ayuislands.accent.elements

import com.intellij.openapi.editor.colors.ColorKey
import com.intellij.openapi.editor.colors.EditorColorsManager
import dev.ayuislands.accent.AccentElement
import dev.ayuislands.accent.AccentElementId
import dev.ayuislands.accent.AyuVariant
import java.awt.Color
import javax.swing.SwingUtilities

class CaretRowElement : AccentElement {
    override val id = AccentElementId.CARET_ROW
    override val displayName = "Caret Row"

    private val caretRowKey = ColorKey.find("CARET_ROW_COLOR")
    private val caretKey = ColorKey.find("CARET_COLOR")
    private val lineNumberKey = ColorKey.find("LINE_NUMBER_ON_CARET_ROW_COLOR")

    override fun apply(color: Color) {
        val caretRowColor = Color(color.red, color.green, color.blue, CARET_ROW_ALPHA)
        val edtWork =
            Runnable {
                val scheme = EditorColorsManager.getInstance().globalScheme
                scheme.setColor(caretRowKey, caretRowColor)
                scheme.setColor(caretKey, color)
                scheme.setColor(lineNumberKey, color)
            }
        if (SwingUtilities.isEventDispatchThread()) {
            edtWork.run()
        } else {
            SwingUtilities.invokeLater(edtWork)
        }
    }

    override fun applyNeutral(variant: AyuVariant) {
        val edtWork =
            Runnable {
                val parentScheme = EditorColorsManager.getInstance().getScheme(variant.parentSchemeName)
                val scheme = EditorColorsManager.getInstance().globalScheme
                for (colorKey in listOf(caretRowKey, caretKey, lineNumberKey)) {
                    scheme.setColor(colorKey, parentScheme?.getColor(colorKey))
                }
            }
        if (SwingUtilities.isEventDispatchThread()) {
            edtWork.run()
        } else {
            SwingUtilities.invokeLater(edtWork)
        }
    }

    companion object {
        private const val CARET_ROW_ALPHA = 0x1A
    }

    override fun revert() {
        val edtWork =
            Runnable {
                val scheme = EditorColorsManager.getInstance().globalScheme
                scheme.setColor(caretRowKey, null)
                scheme.setColor(caretKey, null)
                scheme.setColor(lineNumberKey, null)
            }
        if (SwingUtilities.isEventDispatchThread()) {
            edtWork.run()
        } else {
            SwingUtilities.invokeLater(edtWork)
        }
    }
}
