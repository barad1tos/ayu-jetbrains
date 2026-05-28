package dev.ayuislands.accent.elements

import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.colors.EditorColorsScheme
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.ui.JBColor
import dev.ayuislands.accent.AccentElement
import dev.ayuislands.accent.AccentElementId
import dev.ayuislands.accent.AyuVariant
import dev.ayuislands.syntax.RgbBlend
import java.awt.Color

class MatchingTagElement : AccentElement {
    override val id = AccentElementId.MATCHING_TAG
    override val displayName = "Matching Tag"

    private val tagAttrKey = TextAttributesKey.find("MATCHED_TAG_NAME")

    override fun apply(color: Color) {
        val scheme = EditorColorsManager.getInstance().globalScheme
        val existing = scheme.getAttributes(tagAttrKey)
        val updated = existing?.clone() ?: TextAttributes()
        updated.backgroundColor = blendWithEditorBackground(color, editorBackgroundFor(scheme))
        updated.foregroundColor = null
        scheme.setAttributes(tagAttrKey, updated)
    }

    private fun editorBackgroundFor(scheme: EditorColorsScheme): Color {
        val raw = scheme.defaultBackground
        if (raw.rgb != Color.WHITE.rgb) return raw
        // White is the R-1 early-init sentinel the platform returns on dark variants
        // before the scheme resolves. Substitute the per-variant fallback only when
        // the scheme is positively a dark Ayu variant; Light — and anything we cannot
        // identify — keeps white, so we never blend a dark block onto a light theme.
        val darkVariantTag = darkVariantTagOrNull(scheme.name) ?: return raw
        return RgbBlend.fallbackEditorBgFor(darkVariantTag)
    }

    private fun darkVariantTagOrNull(schemeName: String): String? =
        when (schemeName.removePrefix("_@user_")) {
            "Ayu Islands Dark" -> "Dark"
            "Ayu Islands Mirage" -> "Mirage"
            else -> null
        }

    private fun blendWithEditorBackground(
        accent: Color,
        editorBackground: Color,
    ): Color {
        val red = blendChannel(editorBackground.red, accent.red)
        val green = blendChannel(editorBackground.green, accent.green)
        val blue = blendChannel(editorBackground.blue, accent.blue)
        val rgb = (red shl RED_SHIFT) or (green shl GREEN_SHIFT) or blue
        return JBColor(rgb, rgb)
    }

    private fun blendChannel(
        background: Int,
        accent: Int,
    ): Int =
        (
            background +
                (accent - background) * TAG_BG_ALPHA / MAX_CHANNEL_VALUE.toFloat() +
                ROUNDING_BIAS
        ).toInt().coerceIn(0, MAX_CHANNEL_VALUE)

    override fun applyNeutral(variant: AyuVariant) {
        val scheme = EditorColorsManager.getInstance().globalScheme
        val parentScheme = EditorColorsManager.getInstance().getScheme(variant.parentSchemeName)
        val parentAttrs = parentScheme?.getAttributes(tagAttrKey)
        scheme.setAttributes(tagAttrKey, parentAttrs ?: TextAttributes())
    }

    override fun revert() {
        val scheme = EditorColorsManager.getInstance().globalScheme
        val fallback = tagAttrKey.fallbackAttributeKey
        val defaultAttrs = if (fallback != null) scheme.getAttributes(fallback) else null
        scheme.setAttributes(tagAttrKey, defaultAttrs ?: TextAttributes())
    }

    companion object {
        private const val TAG_BG_ALPHA = 0x30
        private const val MAX_CHANNEL_VALUE = 255
        private const val ROUNDING_BIAS = 0.5f
        private const val RED_SHIFT = 16
        private const val GREEN_SHIFT = 8
    }
}
