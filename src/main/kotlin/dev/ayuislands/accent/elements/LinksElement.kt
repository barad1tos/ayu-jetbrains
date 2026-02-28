package dev.ayuislands.accent.elements

import com.intellij.openapi.editor.colors.ColorKey
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.editor.markup.TextAttributes
import dev.ayuislands.accent.AccentElement
import dev.ayuislands.accent.AccentElementId
import java.awt.Color
import javax.swing.SwingUtilities
import javax.swing.UIManager

class LinksElement : AccentElement {

    override val id = AccentElementId.LINKS
    override val displayName = "Links"

    private val uiKeys = listOf(
        "Link.activeForeground",
        "Link.hoverForeground",
        "Link.secondaryForeground",
        "Notification.linkForeground",
        "GotItTooltip.linkForeground",
        "Tooltip.Learning.linkForeground",
    )

    private val editorColorKeys = listOf(
        ColorKey.find("HYPERLINK_COLOR"),
        ColorKey.find("LINK_FOREGROUND"),
    )

    private val editorAttrKeys = listOf(
        TextAttributesKey.find("CTRL_CLICKABLE"),
        TextAttributesKey.find("FOLLOWED_HYPERLINK_ATTRIBUTES"),
        TextAttributesKey.find("HYPERLINK_ATTRIBUTES"),
    )

    override fun apply(color: Color) {
        for (key in uiKeys) {
            UIManager.put(key, color)
        }
        val edtWork = Runnable {
            val scheme = EditorColorsManager.getInstance().globalScheme
            for (key in editorColorKeys) {
                scheme.setColor(key, color)
            }
            for (attrKey in editorAttrKeys) {
                val existing = scheme.getAttributes(attrKey)
                val updated = existing?.clone() ?: TextAttributes()
                updated.foregroundColor = color
                updated.effectColor = color
                scheme.setAttributes(attrKey, updated)
            }
        }
        if (SwingUtilities.isEventDispatchThread()) {
            edtWork.run()
        } else {
            SwingUtilities.invokeLater(edtWork)
        }
    }

    override fun revert() {
        for (key in uiKeys) {
            UIManager.put(key, null)
        }
        val edtWork = Runnable {
            val scheme = EditorColorsManager.getInstance().globalScheme
            for (key in editorColorKeys) {
                scheme.setColor(key, null)
            }
            for (attrKey in editorAttrKeys) {
                scheme.setAttributes(attrKey, null)
            }
        }
        if (SwingUtilities.isEventDispatchThread()) {
            edtWork.run()
        } else {
            SwingUtilities.invokeLater(edtWork)
        }
    }
}
