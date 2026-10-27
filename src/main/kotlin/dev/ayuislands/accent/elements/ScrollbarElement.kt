package dev.ayuislands.accent.elements

import com.intellij.openapi.editor.colors.ColorKey
import com.intellij.ui.ColorUtil
import dev.ayuislands.accent.AccentElement
import dev.ayuislands.accent.AccentElementId
import dev.ayuislands.accent.putAccentUiDefault
import dev.ayuislands.theme.AyuEditorSchemeScope
import dev.ayuislands.theme.EditorSchemeOwner
import java.awt.Color

class ScrollbarElement : AccentElement {
    override val id = AccentElementId.SCROLLBAR
    override val displayName = "Scrollbar"
    private val schemeOwner = EditorSchemeOwner.Element(id)

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
        val hoverColor = ColorUtil.toAlpha(color, HOVER_ALPHA)
        val defaultColor = ColorUtil.toAlpha(color, DEFAULT_ALPHA)

        // UIManager keys (non-editor scrollbars: Project tree, tool windows, etc.)
        for (key in hoverKeys) {
            putAccentUiDefault(key, hoverColor)
        }
        for (key in defaultKeys) {
            putAccentUiDefault(key, defaultColor)
        }

        // EditorColorsScheme keys (editor scrollbars via OpaqueAwareScrollBar).
        // Editor scrollbars bypass UIManager entirely — OpaqueAwareScrollBar installs
        // a ColorKey.FUNCTION_KEY that resolves colors from EditorColorsScheme.getColor().
        val scheme = AyuEditorSchemeScope.activeScheme() ?: return
        for (key in hoverKeys) {
            AyuEditorSchemeScope.writeColor(scheme, schemeOwner, ColorKey.find(key), hoverColor)
        }
        for (key in defaultKeys) {
            AyuEditorSchemeScope.writeColor(scheme, schemeOwner, ColorKey.find(key), defaultColor)
        }
    }

    override fun revert() {
        for (key in hoverKeys + defaultKeys) {
            putAccentUiDefault(key, null)
        }
        AyuEditorSchemeScope.restore(schemeOwner)
    }
}
