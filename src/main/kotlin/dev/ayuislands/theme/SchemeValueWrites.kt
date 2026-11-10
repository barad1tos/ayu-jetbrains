package dev.ayuislands.theme

import com.intellij.openapi.editor.colors.impl.AbstractColorsScheme
import com.intellij.openapi.editor.markup.TextAttributes
import dev.ayuislands.theme.EditorSchemeOverrides.SchemeEntry
import java.awt.Color

/** Called on EDT only after the owner has acknowledged and guarded the exact native payload. */
internal fun writeSchemeCell(
    scheme: AbstractColorsScheme,
    entry: SchemeEntry,
    cell: SchemeCellFacts,
) {
    scheme.setSaveNeeded(true)
    when (entry) {
        is SchemeEntry.ColorEntry -> scheme.setColor(entry.key, cell.colorPayload())
        is SchemeEntry.AttributesEntry -> scheme.setAttributes(entry.key, cell.attributePayload())
    }
}

/** Returns the retained setter payload without evaluating a foreign color. */
internal fun SchemeCellFacts.colorPayload(): Color =
    checkNotNull((this as? SchemeCellFacts.Color)?.value) {
        "Local absence requires a prepared scheme pruning batch"
    }.identity as Color

/** Shared by read-only map projection and the acknowledged native setter. */
internal fun SchemeCellFacts.attributePayload(): TextAttributes =
    when (this) {
        is SchemeCellFacts.Attributes ->
            checkNotNull(value) { "Local absence requires a prepared scheme pruning batch" }.identity as TextAttributes
        SchemeCellFacts.InheritedAttributes -> AbstractColorsScheme.INHERITED_ATTRS_MARKER
        else -> error("Unqualified editor attributes cannot be written")
    }
