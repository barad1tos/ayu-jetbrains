package dev.ayuislands.syntax

import com.intellij.openapi.editor.colors.EditorColorsScheme
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.editor.markup.TextAttributes

internal data class SyntaxSchemeChange(
    val scheme: EditorColorsScheme,
    val label: String,
    val attributes: Map<TextAttributesKey, TextAttributes>,
    val materializedKeys: Set<String>,
)

/** Computed candidate only; applying it requires separately captured native ownership and a journal acknowledgement. */
internal data class SyntaxPreparedChanges(
    val changes: List<SyntaxSchemeChange>,
    val replacementFonts: Map<String, Map<String, Int>>,
)
