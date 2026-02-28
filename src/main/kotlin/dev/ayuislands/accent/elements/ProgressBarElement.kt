package dev.ayuislands.accent.elements

import com.intellij.openapi.editor.colors.ColorKey
import com.intellij.openapi.editor.colors.EditorColorsManager
import dev.ayuislands.accent.AccentElement
import dev.ayuislands.accent.AccentElementId
import java.awt.Color
import javax.swing.SwingUtilities
import javax.swing.UIManager

class ProgressBarElement : AccentElement {

    override val id = AccentElementId.PROGRESS_BAR
    override val displayName = "Progress Bar"

    private val uiKeys = listOf(
        "ProgressBar.foreground",
        "ProgressBar.progressCounterBackground",
    )

    private val editorKey = ColorKey.find("PROGRESS_BAR_TRACK")

    override fun apply(color: Color) {
        for (key in uiKeys) {
            UIManager.put(key, color)
        }
        val edtWork = Runnable {
            val scheme = EditorColorsManager.getInstance().globalScheme
            scheme.setColor(editorKey, color)
        }
        if (SwingUtilities.isEventDispatchThread()) {
            edtWork.run()
        } else {
            SwingUtilities.invokeLater(edtWork)
        }
    }

    override fun revert() {
        for (key in uiKeys) {
            UIManager.put(key, null)
        }
        val edtWork = Runnable {
            val scheme = EditorColorsManager.getInstance().globalScheme
            scheme.setColor(editorKey, null)
        }
        if (SwingUtilities.isEventDispatchThread()) {
            edtWork.run()
        } else {
            SwingUtilities.invokeLater(edtWork)
        }
    }
}
