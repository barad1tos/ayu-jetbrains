package dev.ayuislands.theme

import com.intellij.openapi.editor.colors.ColorKey
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.editor.colors.impl.AbstractColorsScheme
import com.intellij.openapi.editor.markup.TextAttributes
import java.awt.Color

internal fun readSchemeColor(
    scheme: AbstractColorsScheme,
    key: ColorKey,
): SchemeColorCell = classifySchemeColor(scheme.directlyDefinedColors[key])

internal fun classifySchemeColor(value: Color?): SchemeColorCell =
    when {
        value == null -> SchemeColorCell.Absent
        value === AbstractColorsScheme.INHERITED_COLOR_MARKER -> SchemeColorCell.Inherited
        value === AbstractColorsScheme.NULL_COLOR_MARKER -> SchemeColorCell.Null
        else -> SchemeColorCell.Value(value)
    }

internal fun readSchemeAttributes(
    scheme: AbstractColorsScheme,
    key: TextAttributesKey,
): SchemeAttributeCell = classifySchemeAttributes(scheme.directlyDefinedAttributes[key.externalName])

internal fun classifySchemeAttributes(value: TextAttributes?): SchemeAttributeCell =
    when {
        value == null -> SchemeAttributeCell.Absent
        value === AbstractColorsScheme.INHERITED_ATTRS_MARKER -> SchemeAttributeCell.Inherited
        else -> SchemeAttributeCell.Value(value)
    }
