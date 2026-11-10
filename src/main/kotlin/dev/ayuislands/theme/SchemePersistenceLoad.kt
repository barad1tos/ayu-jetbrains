package dev.ayuislands.theme

import com.intellij.openapi.editor.colors.impl.AbstractColorsScheme
import com.intellij.openapi.editor.markup.EffectType
import com.intellij.openapi.editor.markup.TextAttributes
import dev.ayuislands.theme.EditorSchemeOverrides.SchemeEntry
import dev.ayuislands.theme.EditorSchemeOverrides.SchemeValue
import java.awt.Color
import java.awt.Font

/** Reconstructs the native file representation, including the editable copy's qualified canonical fill. */
internal object SchemePersistenceLoad {
    fun value(
        entry: SchemeEntry,
        stored: SchemeStoredCell,
        resource: AyuSchemeResource?,
    ): SchemeValue? =
        when (stored) {
            SchemeStoredCell.Omitted -> {
                original(entry, resource)
            }

            is SchemeStoredCell.Color -> {
                require(entry is SchemeEntry.ColorEntry)
                SchemeValue.ColorValue(Color(stored.argb, true))
            }

            SchemeStoredCell.NullColor -> {
                require(entry is SchemeEntry.ColorEntry)
                SchemeValue.ColorValue(null)
            }

            is SchemeStoredCell.InheritedColor -> {
                require(
                    entry is SchemeEntry.ColorEntry && entry.key.fallbackColorKey?.externalName == stored.fallbackName,
                )
                SchemeValue.InheritedColor
            }

            is SchemeStoredCell.InheritedAttributes -> {
                require(
                    entry is SchemeEntry.AttributesEntry &&
                        entry.key.fallbackAttributeKey
                            ?.externalName
                            .orEmpty() == stored.fallbackName,
                )
                SchemeValue.InheritedAttributes
            }

            is SchemeStoredCell.Attributes -> {
                require(entry is SchemeEntry.AttributesEntry)
                val attributes =
                    TextAttributes(
                        stored.foregroundRgb?.let(::Color),
                        stored.backgroundRgb?.let(::Color),
                        stored.effectRgb?.let(::Color),
                        stored.effectCode.takeIf { it >= 0 }?.let { EffectType.valueOf(SCHEME_STORED_EFFECTS[it]) }
                            ?: EffectType.BOXED,
                        stored.fontType.takeIf { it in Font.PLAIN..(Font.BOLD or Font.ITALIC) } ?: Font.PLAIN,
                    )
                attributes.errorStripeColor = stored.errorStripeRgb?.let(::Color)
                SchemeValue.AttributesValue(attributes)
            }
        }

    /** Native equality includes runtime-only effects; matching serialized fields alone never authorizes a write. */
    fun matches(
        expected: SchemeValue?,
        observed: SchemeCellFacts,
    ): Boolean {
        if (expected == null) return SchemeAdmission.isAbsent(observed)
        return when (val captured = capturePlannedCell(expected)) {
            is SchemeCellFacts.Color -> {
                observed is SchemeCellFacts.Color &&
                    SchemeComparison.colors(captured.value, observed.value) == SchemeEquality.EQUAL
            }

            is SchemeCellFacts.Attributes -> {
                observed is SchemeCellFacts.Attributes &&
                    SchemeComparison.attributes(captured.value, observed.value) == SchemeEquality.EQUAL
            }

            SchemeCellFacts.InheritedAttributes -> {
                observed === SchemeCellFacts.InheritedAttributes
            }

            SchemeCellFacts.Unavailable -> {
                false
            }
        }
    }

    private fun original(
        entry: SchemeEntry,
        resource: AyuSchemeResource?,
    ): SchemeValue? {
        if (resource == null) return null
        check(resource.isCurrent()) { "Canonical fill changed during recovery" }
        return when (entry) {
            is SchemeEntry.ColorEntry -> {
                resource.scheme.directlyDefinedColors[entry.key]?.let {
                    if (it ===
                        AbstractColorsScheme.INHERITED_COLOR_MARKER
                    ) {
                        SchemeValue.InheritedColor
                    } else {
                        SchemeValue.ColorValue(it)
                    }
                }
            }

            is SchemeEntry.AttributesEntry -> {
                resource.scheme.directlyDefinedAttributes[entry.key.externalName]?.let {
                    if (it ===
                        AbstractColorsScheme.INHERITED_ATTRS_MARKER
                    ) {
                        SchemeValue.InheritedAttributes
                    } else {
                        SchemeValue.AttributesValue(it.clone())
                    }
                }
            }
        }
    }
}
