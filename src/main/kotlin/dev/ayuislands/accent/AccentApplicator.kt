package dev.ayuislands.accent

import com.intellij.ui.ColorUtil
import java.awt.Color
import java.awt.Window
import javax.swing.SwingUtilities
import javax.swing.UIManager

object AccentApplicator {

    private val ACCENT_FULL_KEYS = listOf(
        // Tab underlines
        "ToolWindow.HeaderTab.underlineColor",
        "EditorTabs.underlinedBorderColor",
        "TabbedPane.underlineColor",
        // GotItTooltip
        "GotItTooltip.background",
        "GotItTooltip.borderColor",
        // Default button
        "Button.default.startBackground",
        "Button.default.endBackground",
        // Focus border
        "Component.focusedBorderColor",
        // Links
        "Link.activeForeground",
        "Link.hoverForeground",
        "Link.secondaryForeground",
        "Notification.linkForeground",
        "GotItTooltip.linkForeground",
        "Tooltip.Learning.linkForeground",
        // Drag and drop
        "DragAndDrop.borderColor",
        // Trial widget
        "TrialWidget.Alert.borderColor",
        "TrialWidget.Alert.foreground",
        // Progress bar
        "ProgressBar.foreground",
        "ProgressBar.progressCounterBackground",
    )

    private val SCROLLBAR_ALPHA_KEYS = listOf(
        "ScrollBar.hoverThumbBorderColor",
        "ScrollBar.hoverThumbColor",
        "ScrollBar.Transparent.hoverThumbBorderColor",
        "ScrollBar.Transparent.hoverThumbColor",
        "ScrollBar.Mac.hoverThumbBorderColor",
        "ScrollBar.Mac.hoverThumbColor",
        "ScrollBar.Mac.Transparent.hoverThumbBorderColor",
        "ScrollBar.Mac.Transparent.hoverThumbColor",
    )

    private const val SCROLLBAR_ALPHA = 0x8C

    fun apply(accentHex: String) {
        val accent = Color.decode(accentHex)

        for (key in ACCENT_FULL_KEYS) {
            UIManager.put(key, accent)
        }

        val scrollbarColor = Color(accent.red, accent.green, accent.blue, SCROLLBAR_ALPHA)
        for (key in SCROLLBAR_ALPHA_KEYS) {
            UIManager.put(key, scrollbarColor)
        }

        // Contrast foreground for accent-background elements (GotItTooltip, buttons)
        val contrastForeground = if (ColorUtil.isDark(accent)) Color.WHITE else Color(0x1F, 0x24, 0x30)
        UIManager.put("GotItTooltip.foreground", contrastForeground)
        UIManager.put("GotItTooltip.Button.foreground", contrastForeground)
        UIManager.put("GotItTooltip.Header.foreground", contrastForeground)

        // Darkened accent for default button borders (~15% darker)
        val darkenedAccent = ColorUtil.darker(accent, 1)
        UIManager.put("Button.default.focusedBorderColor", darkenedAccent)
        UIManager.put("Button.default.startBorderColor", darkenedAccent)
        UIManager.put("Button.default.endBorderColor", darkenedAccent)

        repaintAllWindows()
    }

    private fun repaintAllWindows() {
        for (window in Window.getWindows()) {
            SwingUtilities.updateComponentTreeUI(window)
        }
    }
}
