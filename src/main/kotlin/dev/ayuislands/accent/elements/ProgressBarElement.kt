package dev.ayuislands.accent.elements

import com.intellij.openapi.editor.colors.ColorKey
import com.intellij.openapi.editor.colors.EditorColorsManager
import dev.ayuislands.accent.AccentElement
import dev.ayuislands.accent.AccentElementId
import dev.ayuislands.accent.AyuVariant
import java.awt.Color
import javax.swing.SwingUtilities
import javax.swing.UIManager

class ProgressBarElement : AccentElement {
    override val id = AccentElementId.PROGRESS_BAR
    override val displayName = "Progress Bar"

    private val uiKeys =
        listOf(
            "ProgressBar.foreground",
            "ProgressBar.progressCounterBackground",
        )

    private val editorKey = ColorKey.find("PROGRESS_BAR_TRACK")

    override fun apply(color: Color) {
        for (key in uiKeys) {
            UIManager.put(key, color)
        }
        runOnEdt {
            val scheme = EditorColorsManager.getInstance().globalScheme
            scheme.setColor(editorKey, color)
        }
    }

    override fun applyNeutral(variant: AyuVariant) {
        for (key in uiKeys) {
            UIManager.put(key, null)
        }
        runOnEdt {
            val parentScheme = EditorColorsManager.getInstance().getScheme(variant.parentSchemeName)
            val scheme = EditorColorsManager.getInstance().globalScheme
            scheme.setColor(editorKey, parentScheme?.getColor(editorKey))
        }
    }

    override fun revert() {
        for (key in uiKeys) {
            UIManager.put(key, null)
        }
        runOnEdt {
            val scheme = EditorColorsManager.getInstance().globalScheme
            scheme.setColor(editorKey, null)
        }
    }

    private inline fun runOnEdt(crossinline block: () -> Unit) {
        if (SwingUtilities.isEventDispatchThread()) {
            block()
        } else {
            SwingUtilities.invokeLater { block() }
        }
    }
}
