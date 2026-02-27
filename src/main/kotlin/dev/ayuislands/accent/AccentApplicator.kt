package dev.ayuislands.accent

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.colors.ColorKey
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.editor.markup.TextAttributes
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
        "Component.focusColor",
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

    private data class SelectionKey(val key: String, val alpha: Int)

    private val SELECTION_KEYS = listOf(
        SelectionKey("List.selectionBackground", 0x26),
        SelectionKey("List.selectionInactiveBackground", 0x1A),
        SelectionKey("Tree.selectionBackground", 0x26),
        SelectionKey("Tree.selectionInactiveBackground", 0x1A),
        SelectionKey("Table.selectionBackground", 0x26),
        SelectionKey("Table.selectionInactiveBackground", 0x1A),
    )

    private data class ColorKeyEntry(val name: String, val alpha: Int = 0xFF)

    private val EDITOR_COLOR_KEYS = listOf(
        ColorKeyEntry("CARET_COLOR"),
        ColorKeyEntry("CARET_ROW_COLOR", alpha = 0x1A),
        ColorKeyEntry("LINE_NUMBER_ON_CARET_ROW_COLOR"),
        ColorKeyEntry("MATCHED_TEXT"),
        ColorKeyEntry("HYPERLINK_COLOR"),
        ColorKeyEntry("LINK_FOREGROUND"),
        ColorKeyEntry("BUTTON_BACKGROUND"),
        ColorKeyEntry("PROGRESS_BAR_TRACK"),
        ColorKeyEntry("WARNING_FOREGROUND"),
    )

    private data class AttrOverride(
        val key: String,
        val foreground: Boolean = false,
        val effectColor: Boolean = false,
        val errorStripe: Boolean = false,
    )

    private val EDITOR_ATTR_OVERRIDES = listOf(
        AttrOverride("BOOKMARKS_ATTRIBUTES", errorStripe = true),
        AttrOverride("CTRL_CLICKABLE", foreground = true, effectColor = true),
        AttrOverride("DEBUGGER_INLINED_VALUES_MODIFIED", foreground = true),
        AttrOverride("FOLLOWED_HYPERLINK_ATTRIBUTES", foreground = true, effectColor = true),
        AttrOverride("HYPERLINK_ATTRIBUTES", foreground = true, effectColor = true),
        AttrOverride("LIVE_TEMPLATE_ATTRIBUTES", effectColor = true),
        AttrOverride("LOG_INFO_OUTPUT", foreground = true),
        AttrOverride("RUNTIME_ERROR", effectColor = true),
        AttrOverride("SMART_COMPLETION_STATISTICAL_MATCHED_ITEM", foreground = true),
        AttrOverride("TEXT_STYLE_WARNING", effectColor = true),
        AttrOverride("TODO_DEFAULT_ATTRIBUTES", foreground = true),
        AttrOverride("WARNING_ATTRIBUTES", effectColor = true, errorStripe = true),
    )

    fun apply(accentHex: String) {
        val accent = Color.decode(accentHex)

        // UIManager.put is thread-safe, do it immediately
        for (key in ACCENT_FULL_KEYS) {
            UIManager.put(key, accent)
        }

        val scrollbarColor = Color(accent.red, accent.green, accent.blue, SCROLLBAR_ALPHA)
        for (key in SCROLLBAR_ALPHA_KEYS) {
            UIManager.put(key, scrollbarColor)
        }

        for (sel in SELECTION_KEYS) {
            UIManager.put(sel.key, Color(accent.red, accent.green, accent.blue, sel.alpha))
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

        // EDT-only operations: editor scheme + repaint
        val edtWork = Runnable {
            applyToEditorScheme(accent)
            repaintAllWindows()
        }
        if (SwingUtilities.isEventDispatchThread()) {
            edtWork.run()
        } else {
            SwingUtilities.invokeLater(edtWork)
        }
    }

    private fun applyToEditorScheme(accent: Color) {
        val scheme = EditorColorsManager.getInstance().globalScheme

        // ColorKey entries
        for (entry in EDITOR_COLOR_KEYS) {
            val color = if (entry.alpha == 0xFF) {
                accent
            } else {
                Color(accent.red, accent.green, accent.blue, entry.alpha)
            }
            scheme.setColor(ColorKey.find(entry.name), color)
        }

        // TextAttributesKey entries — clone existing, override only accent properties
        for (override in EDITOR_ATTR_OVERRIDES) {
            val attrKey = TextAttributesKey.find(override.key)
            val existing = scheme.getAttributes(attrKey)
            val updated = existing?.clone() ?: TextAttributes()
            if (override.foreground) updated.foregroundColor = accent
            if (override.effectColor) updated.effectColor = accent
            if (override.errorStripe) updated.errorStripeColor = accent
            scheme.setAttributes(attrKey, updated)
        }

        // Notify editors to repaint with updated scheme
        ApplicationManager.getApplication().messageBus
            .syncPublisher(EditorColorsManager.TOPIC)
            .globalSchemeChange(null)
    }

    private fun repaintAllWindows() {
        for (window in Window.getWindows()) {
            window.repaint()
        }
    }
}
