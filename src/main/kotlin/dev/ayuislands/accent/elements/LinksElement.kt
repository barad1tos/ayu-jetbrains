package dev.ayuislands.accent.elements

import com.intellij.openapi.editor.colors.ColorKey
import com.intellij.openapi.editor.colors.EditorColorsScheme
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.editor.markup.TextAttributes
import dev.ayuislands.accent.AccentElement
import dev.ayuislands.accent.AccentElementId
import dev.ayuislands.accent.AyuVariant
import dev.ayuislands.accent.putAccentUiDefault
import dev.ayuislands.theme.AyuEditorSchemeScope
import dev.ayuislands.theme.EditorSchemeOwner
import java.awt.Color

class LinksElement : AccentElement {
    override val id = AccentElementId.LINKS
    override val displayName = "Links"
    private val schemeOwner = EditorSchemeOwner.Element(id)

    internal val uiKeys =
        listOf(
            "Link.activeForeground",
            "Link.hoverForeground",
            "Link.secondaryForeground",
            "Notification.linkForeground",
            "GotItTooltip.linkForeground",
            "Tooltip.Learning.linkForeground",
        )

    private val editorColorKeys =
        listOf(
            ColorKey.find("HYPERLINK_COLOR"),
            ColorKey.find("LINK_FOREGROUND"),
        )

    private val editorAttrKeys =
        listOf(
            TextAttributesKey.find("CTRL_CLICKABLE"),
            TextAttributesKey.find("FOLLOWED_HYPERLINK_ATTRIBUTES"),
            TextAttributesKey.find("HYPERLINK_ATTRIBUTES"),
        )

    internal fun uiColors(color: Color): Map<String, Color> = uiKeys.associateWith { color }

    internal fun editorColors(color: Color): Sequence<Pair<ColorKey, Color>> =
        editorColorKeys.asSequence().map { it to color }

    internal fun editorAttributes(
        scheme: EditorColorsScheme,
        color: Color,
    ): Sequence<Pair<TextAttributesKey, TextAttributes>> =
        editorAttrKeys.asSequence().map { key ->
            val updated = scheme.getAttributes(key)?.clone() ?: TextAttributes()
            updated.foregroundColor = color
            updated.effectColor = color
            key to updated
        }

    override fun apply(color: Color) {
        for ((key, value) in uiColors(color)) {
            putAccentUiDefault(key, value)
        }
        val scheme = AyuEditorSchemeScope.activeScheme() ?: return
        for ((key, value) in editorColors(color)) {
            AyuEditorSchemeScope.writeColor(scheme, schemeOwner, key, value)
        }
        for ((key, value) in editorAttributes(scheme, color)) {
            AyuEditorSchemeScope.writeAttributes(scheme, schemeOwner, key, value)
        }
    }

    override fun applyNeutral(variant: AyuVariant) {
        for (key in uiKeys) {
            putAccentUiDefault(key, null)
        }
        AyuEditorSchemeScope.restore(schemeOwner)
    }

    override fun revert() {
        for (key in uiKeys) {
            putAccentUiDefault(key, null)
        }
        AyuEditorSchemeScope.restore(schemeOwner)
    }
}
