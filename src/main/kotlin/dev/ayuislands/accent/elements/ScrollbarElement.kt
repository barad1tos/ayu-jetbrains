package dev.ayuislands.accent.elements

import dev.ayuislands.accent.AccentElement
import dev.ayuislands.accent.AccentElementId
import java.awt.Color
import javax.swing.UIManager

class ScrollbarElement : AccentElement {

    override val id = AccentElementId.SCROLLBAR
    override val displayName = "Scrollbar"

    private val keys = listOf(
        "ScrollBar.hoverThumbBorderColor",
        "ScrollBar.hoverThumbColor",
        "ScrollBar.Transparent.hoverThumbBorderColor",
        "ScrollBar.Transparent.hoverThumbColor",
        "ScrollBar.Mac.hoverThumbBorderColor",
        "ScrollBar.Mac.hoverThumbColor",
        "ScrollBar.Mac.Transparent.hoverThumbBorderColor",
        "ScrollBar.Mac.Transparent.hoverThumbColor",
    )

    private companion object {
        const val ALPHA = 0x8C
    }

    override fun apply(color: Color) {
        val alphaColor = Color(color.red, color.green, color.blue, ALPHA)
        for (key in keys) {
            UIManager.put(key, alphaColor)
        }
    }

    override fun revert() {
        for (key in keys) {
            UIManager.put(key, null)
        }
    }
}
