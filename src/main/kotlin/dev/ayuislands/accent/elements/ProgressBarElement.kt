package dev.ayuislands.accent.elements

import com.intellij.openapi.editor.colors.ColorKey
import dev.ayuislands.accent.AccentElement
import dev.ayuislands.accent.AccentElementId
import dev.ayuislands.accent.AyuVariant
import dev.ayuislands.accent.putAccentUiDefault
import dev.ayuislands.theme.AyuEditorSchemeScope
import dev.ayuislands.theme.EditorSchemeOwner
import java.awt.Color
import javax.swing.SwingUtilities

class ProgressBarElement : AccentElement {
    override val id = AccentElementId.PROGRESS_BAR
    override val displayName = "Progress Bar"
    private val schemeOwner = EditorSchemeOwner.Element(id)

    internal val uiKeys =
        listOf(
            "ProgressBar.foreground",
            "ProgressBar.progressCounterBackground",
        )

    private val editorKey = ColorKey.find("PROGRESS_BAR_TRACK")

    internal fun uiColors(color: Color): Map<String, Color> = uiKeys.associateWith { color }

    internal fun editorColors(color: Color): Map<ColorKey, Color> = mapOf(editorKey to color)

    override fun apply(color: Color) {
        val scheme = AyuEditorSchemeScope.activeScheme()
        for ((key, value) in uiColors(color)) {
            putAccentUiDefault(key, value)
        }
        if (scheme == null) return
        runOnEdt {
            for ((key, value) in editorColors(color)) {
                AyuEditorSchemeScope.writeColor(scheme, schemeOwner, key, value)
            }
        }
    }

    override fun applyNeutral(variant: AyuVariant) {
        for (key in uiKeys) {
            putAccentUiDefault(key, null)
        }
        runOnEdt {
            AyuEditorSchemeScope.restore(schemeOwner)
        }
    }

    override fun revert() {
        for (key in uiKeys) {
            putAccentUiDefault(key, null)
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
