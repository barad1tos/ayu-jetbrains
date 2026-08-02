package dev.ayuislands.accent.elements

import com.intellij.openapi.editor.colors.ColorKey
import dev.ayuislands.accent.AccentElement
import dev.ayuislands.accent.AccentElementId
import dev.ayuislands.accent.AyuVariant
import dev.ayuislands.theme.AyuEditorSchemeScope
import dev.ayuislands.theme.EditorSchemeOwner
import java.awt.Color
import javax.swing.SwingUtilities
import javax.swing.UIManager

class ProgressBarElement : AccentElement {
    override val id = AccentElementId.PROGRESS_BAR
    override val displayName = "Progress Bar"
    private val schemeOwner = EditorSchemeOwner.Element(id)

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
            AyuEditorSchemeScope.writeColor(schemeOwner, editorKey, color)
        }
    }

    override fun applyNeutral(variant: AyuVariant) {
        for (key in uiKeys) {
            UIManager.put(key, null)
        }
        runOnEdt {
            AyuEditorSchemeScope.restore(schemeOwner)
        }
    }

    override fun revert() {
        for (key in uiKeys) {
            UIManager.put(key, null)
        }
        runOnEdt {
            AyuEditorSchemeScope.restore(schemeOwner)
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
