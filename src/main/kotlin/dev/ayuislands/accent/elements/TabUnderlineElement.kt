package dev.ayuislands.accent.elements

import dev.ayuislands.accent.AccentElement
import dev.ayuislands.accent.AccentElementId
import java.awt.Color
import javax.swing.UIManager

class TabUnderlineElement : AccentElement {

    override val id = AccentElementId.TAB_UNDERLINES
    override val displayName = "Tab Underlines"

    private val keys = listOf(
        "ToolWindow.HeaderTab.underlineColor",
        "EditorTabs.underlinedBorderColor",
        "TabbedPane.underlineColor",
    )

    override fun apply(color: Color) {
        for (key in keys) {
            UIManager.put(key, color)
        }
    }

    override fun revert() {
        for (key in keys) {
            UIManager.put(key, null)
        }
    }
}
