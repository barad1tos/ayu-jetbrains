package dev.ayuislands.theme

import com.intellij.openapi.editor.colors.ColorKey
import com.intellij.openapi.editor.colors.EditorColorsScheme
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.editor.colors.impl.AbstractColorsScheme
import com.intellij.openapi.editor.colors.impl.DefaultColorsScheme
import com.intellij.openapi.editor.markup.TextAttributes
import dev.ayuislands.theme.EditorSchemeOverrides.SchemeEntry
import dev.ayuislands.theme.EditorSchemeOverrides.SchemeValue
import java.awt.Color

/** The caller freezes mutable values before capture and passes this exact payload to the native setter. */
internal fun capturePlannedCell(value: SchemeValue): SchemeCellFacts =
    when (value) {
        is SchemeValue.ColorValue -> {
            SchemeCellFacts.Color(captureColorFacts(value.value ?: AbstractColorsScheme.NULL_COLOR_MARKER))
        }

        is SchemeValue.AttributesValue -> {
            val facts = captureAttributeFacts(value.value)
            check(facts is SchemeAttributeFacts.Native) { "Editor attributes require a qualified native payload" }
            SchemeCellFacts.Attributes(facts)
        }

        SchemeValue.InheritedColor -> {
            SchemeCellFacts.Color(captureColorFacts(AbstractColorsScheme.INHERITED_COLOR_MARKER))
        }

        SchemeValue.InheritedAttributes -> SchemeCellFacts.InheritedAttributes
    }

/** Effective getters cannot establish local absence; opaque attributes cannot prove unchanged mutation state. */
internal fun captureSchemeCell(
    scheme: EditorColorsScheme,
    entry: SchemeEntry,
): SchemeCellFacts? {
    if (scheme !is AbstractColorsScheme) return null
    return when (entry) {
        is SchemeEntry.ColorEntry -> captureCellFacts(readSchemeColor(scheme, entry.key))
        is SchemeEntry.AttributesEntry -> {
            if (TextAttributesKey.isTemp(entry.key)) return null
            captureCellFacts(readSchemeAttributes(scheme, entry.key))
        }
    }
}

/** Uses the same copied maps as the deletion census; it never re-reads the native scheme. */
internal fun captureSchemeCell(
    entry: SchemeEntry,
    attributes: Map<String, TextAttributes>,
    colors: Map<ColorKey, Color>,
): SchemeCellFacts? =
    when (entry) {
        is SchemeEntry.ColorEntry -> captureCellFacts(classifySchemeColor(colors[entry.key]))
        is SchemeEntry.AttributesEntry ->
            if (TextAttributesKey.isTemp(entry.key)) {
                null
            } else {
                captureCellFacts(classifySchemeAttributes(attributes[entry.key.externalName]))
            }
    }

private fun captureCellFacts(cell: SchemeColorCell): SchemeCellFacts.Color {
    val color =
        when (cell) {
            SchemeColorCell.Absent -> null
            SchemeColorCell.Inherited -> AbstractColorsScheme.INHERITED_COLOR_MARKER
            SchemeColorCell.Null -> AbstractColorsScheme.NULL_COLOR_MARKER
            is SchemeColorCell.Value -> cell.color
        }
    return SchemeCellFacts.Color(captureColorFacts(color))
}

private fun captureCellFacts(cell: SchemeAttributeCell): SchemeCellFacts? =
    when (cell) {
        SchemeAttributeCell.Absent -> SchemeCellFacts.Attributes(null)
        SchemeAttributeCell.Inherited -> SchemeCellFacts.InheritedAttributes
        is SchemeAttributeCell.Value -> {
            (captureAttributeFacts(cell.attributes) as? SchemeAttributeFacts.Native)?.let(SchemeCellFacts::Attributes)
        }
    }

internal fun capturePruningFacts(
    parent: AbstractColorsScheme,
    attributes: Map<String, TextAttributes>,
    colors: Map<ColorKey, Color>,
): SchemePruningFacts? {
    val qualified = qualifyPruningParent(parent) ?: return null
    val directAttributes: Map<String, TextAttributes> = qualified.directlyDefinedAttributes
    val directColors: Map<ColorKey, Color> = qualified.directlyDefinedColors
    val attributeChecks =
        attributes.mapValues { (name, value) ->
            if (value === AbstractColorsScheme.INHERITED_ATTRS_MARKER) {
                SchemeComparison.inherited(directAttributes[name], AbstractColorsScheme.INHERITED_ATTRS_MARKER)
            } else {
                SchemeComparison.attributes(
                    captureAttributeFacts(qualified.getAttributes(TextAttributesKey.find(name), false)),
                    captureAttributeFacts(value),
                )
            }
        }
    val colorChecks =
        colors.entries.associate { (key, value) ->
            val comparison =
                if (value === AbstractColorsScheme.INHERITED_COLOR_MARKER) {
                    SchemeComparison.inherited(directColors[key], AbstractColorsScheme.INHERITED_COLOR_MARKER)
                } else {
                    val local = value.takeUnless { it === AbstractColorsScheme.NULL_COLOR_MARKER }
                    SchemeComparison.colors(captureColorFacts(qualified.getColor(key, false)), captureColorFacts(local))
                }
            key.externalName to comparison
        }
    return SchemePruningFacts(attributeChecks, colorChecks)
}

internal fun qualifyPruningParent(parent: AbstractColorsScheme): DefaultColorsScheme? {
    if (parent !is DefaultColorsScheme || parent.javaClass != DefaultColorsScheme::class.java) return null
    return parent.takeIf { it.parentScheme == null }
}

internal fun captureColorFacts(color: Color?): SchemeColorFacts? =
    when {
        color == null -> {
            null
        }

        color === AbstractColorsScheme.INHERITED_COLOR_MARKER ->
            SchemeColorFacts.Marker(
                color,
                SchemeColorMarker.INHERITED,
            )

        color === AbstractColorsScheme.NULL_COLOR_MARKER -> SchemeColorFacts.Marker(color, SchemeColorMarker.NULL)

        color.javaClass == Color::class.java -> {
            SchemeColorFacts.Plain(color, color.rgb)
        }

        else -> {
            SchemeColorFacts.Opaque(color)
        }
    }

internal fun captureAttributeFacts(attributes: TextAttributes?): SchemeAttributeFacts? {
    if (attributes == null) return null
    if (attributes.javaClass != TextAttributes::class.java) return SchemeAttributeFacts.Opaque(attributes)
    val flyweight = attributes.flyweight
    // This public factory only wraps the captured immutable flyweight; it never creates or interns another one.
    val snapshot = TextAttributes.fromFlyweight(flyweight)
    val fields =
        SchemeAttributeFields(
            fontType = snapshot.fontType,
            background = captureColorFacts(snapshot.backgroundColor),
            effectColor = captureColorFacts(snapshot.effectColor),
            effectType = snapshot.effectType?.name,
            errorStripe = captureColorFacts(snapshot.errorStripeColor),
            foreground = captureColorFacts(snapshot.foregroundColor),
        )
    return SchemeAttributeFacts.Native(attributes, flyweight, fields)
}
