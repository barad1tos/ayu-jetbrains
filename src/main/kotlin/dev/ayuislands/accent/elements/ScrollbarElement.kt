package dev.ayuislands.accent.elements

import com.intellij.openapi.editor.colors.ColorKey
import com.intellij.openapi.editor.colors.EditorColorsManager
import dev.ayuislands.accent.AccentElement
import dev.ayuislands.accent.AccentElementId
import java.awt.Color
import javax.swing.UIManager

class ScrollbarElement : AccentElement {
    override val id = AccentElementId.SCROLLBAR
    override val displayName = "Scrollbar"

    private val hoverKeys =
        listOf(
            "ScrollBar.hoverThumbBorderColor",
            "ScrollBar.hoverThumbColor",
            "ScrollBar.Transparent.hoverThumbBorderColor",
            "ScrollBar.Transparent.hoverThumbColor",
            "ScrollBar.Mac.hoverThumbBorderColor",
            "ScrollBar.Mac.hoverThumbColor",
            "ScrollBar.Mac.Transparent.hoverThumbBorderColor",
            "ScrollBar.Mac.Transparent.hoverThumbColor",
        )

    private val defaultKeys =
        listOf(
            "ScrollBar.thumbBorderColor",
            "ScrollBar.thumbColor",
            "ScrollBar.Transparent.thumbBorderColor",
            "ScrollBar.Transparent.thumbColor",
            "ScrollBar.Mac.thumbBorderColor",
            "ScrollBar.Mac.thumbColor",
            "ScrollBar.Mac.Transparent.thumbBorderColor",
            "ScrollBar.Mac.Transparent.thumbColor",
        )

    private companion object {
        const val HOVER_ALPHA = 0x8C
        const val DEFAULT_ALPHA = 0x59
    }

    override fun apply(color: Color) {
        val hoverColor = Color(color.red, color.green, color.blue, HOVER_ALPHA)
        val defaultColor = Color(color.red, color.green, color.blue, DEFAULT_ALPHA)

        // UIManager keys (non-editor scrollbars: Project tree, tool windows, etc.)
        for (key in hoverKeys) {
            UIManager.put(key, hoverColor)
        }
        for (key in defaultKeys) {
            UIManager.put(key, defaultColor)
        }

        // EditorColorsScheme keys (editor scrollbars via OpaqueAwareScrollBar).
        // Editor scrollbars bypass UIManager entirely — OpaqueAwareScrollBar installs
        // a ColorKey.FUNCTION_KEY that resolves colors from EditorColorsScheme.getColor().
        val scheme = EditorColorsManager.getInstance().globalScheme
        for (key in hoverKeys) {
            scheme.setColor(ColorKey.find(key), hoverColor)
        }
        for (key in defaultKeys) {
            scheme.setColor(ColorKey.find(key), defaultColor)
        }
    }

    override fun revert() {
        for (key in hoverKeys + defaultKeys) {
            UIManager.put(key, null)
        }
        val scheme = EditorColorsManager.getInstance().globalScheme
        for (key in hoverKeys + defaultKeys) {
            scheme.setColor(ColorKey.find(key), null)
        }
    }
}
