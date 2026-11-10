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

    internal val uiKeys: List<String>
        get() = hoverKeys + defaultKeys

    internal fun uiColors(color: Color): Map<String, Color> {
        val hoverColor = ColorUtil.toAlpha(color, HOVER_ALPHA)
        val defaultColor = ColorUtil.toAlpha(color, DEFAULT_ALPHA)
        return buildMap {
            hoverKeys.forEach { put(it, hoverColor) }
            defaultKeys.forEach { put(it, defaultColor) }
        }
    }

    internal fun editorColors(colors: Map<String, Color>): Sequence<Pair<ColorKey, Color>> =
        colors.asSequence().map { (key, value) -> ColorKey.find(key) to value }

    override fun apply(color: Color) {
        val colors = uiColors(color)
        // UIManager keys (non-editor scrollbars: Project tree, tool windows, etc.)
        for ((key, value) in colors) {
            putAccentUiDefault(key, value)
        }

        // EditorColorsScheme keys (editor scrollbars via OpaqueAwareScrollBar).
        // Editor scrollbars bypass UIManager entirely — OpaqueAwareScrollBar installs
        // a ColorKey.FUNCTION_KEY that resolves colors from EditorColorsScheme.getColor().
        val scheme = AyuEditorSchemeScope.activeScheme() ?: return
        for ((key, value) in editorColors(colors)) {
            AyuEditorSchemeScope.writeColor(scheme, schemeOwner, key, value)
        }
    }

    override fun revert() {
        for (key in uiKeys) {
            putAccentUiDefault(key, null)
        }
        AyuEditorSchemeScope.restore(schemeOwner)
    }
}
