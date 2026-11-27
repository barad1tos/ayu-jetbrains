package dev.ayuislands.studio.model

import dev.ayuislands.syntax.FontEmphasis
import dev.ayuislands.syntax.FontStyleOverride
import dev.ayuislands.syntax.PrimitiveCategory
import dev.ayuislands.syntax.SyntaxPreset

internal sealed interface StudioSyntaxEdit {
    data class Preset(
        val value: SyntaxPreset,
    ) : StudioSyntaxEdit

    data class Readability(
        val option: StudioSyntaxReadability,
        val enabled: Boolean,
    ) : StudioSyntaxEdit

    data class Intensity(
        val language: String,
        val category: PrimitiveCategory,
        val value: Int,
    ) : StudioSyntaxEdit

    data class Style(
        val language: String,
        val category: PrimitiveCategory,
        val value: FontStyleOverride?,
    ) : StudioSyntaxEdit

    data class Emphasis(
        val language: String,
        val category: PrimitiveCategory,
        val value: FontEmphasis?,
    ) : StudioSyntaxEdit

    data class Reset(
        val language: String,
        val categories: Set<PrimitiveCategory>,
    ) : StudioSyntaxEdit
}

internal enum class StudioSyntaxReadability(
    fieldName: String,
) {
    DIM_COMMENTS("dimComments"),
    SOFTEN_DOCUMENTATION("softenDocumentation"),
    QUIET_OPERATORS("quietOperators"),
    EMPHASIZE_DECLARATIONS("emphasizeDeclarations"),
    ;

    val field = LegacyFieldKey(LegacyStore.SYNTAX, fieldName)
}
