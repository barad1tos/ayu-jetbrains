package dev.ayuislands.accent.elements

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
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
        val scheme = AyuEditorSchemeScope.activeScheme()
        for (key in uiKeys) {
            UIManager.put(key, color)
        }
        if (scheme == null) return
        runOnEdt {
            AyuEditorSchemeScope.writeColor(scheme, schemeOwner, editorKey, color)
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
            ApplicationManager.getApplication().invokeLater(
                { block() },
                ModalityState.nonModal(),
            )
        }
    }
}
