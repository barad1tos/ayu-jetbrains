package dev.ayuislands.theme

/** Native per-entry persistence payload. Omission is not local absence after editable canonical fill. */
internal sealed interface SchemeStoredCell {
    data object Omitted : SchemeStoredCell

    data class Color(
        val argb: Int,
    ) : SchemeStoredCell

    data object NullColor : SchemeStoredCell

    data class InheritedColor(
        val fallbackName: String,
    ) : SchemeStoredCell

    data class Attributes(
        val foregroundRgb: Int?,
        val backgroundRgb: Int?,
        val effectRgb: Int?,
        val errorStripeRgb: Int?,
        val fontType: Int,
        val effectCode: Int,
    ) : SchemeStoredCell

    data class InheritedAttributes(
        val fallbackName: String,
    ) : SchemeStoredCell
}

/** SDK251 native XML effect codes; their order is part of the file format. */
internal val SCHEME_STORED_EFFECTS: List<String> =
    listOf(
        "BOXED",
        "LINE_UNDERSCORE",
        "WAVE_UNDERSCORE",
        "STRIKEOUT",
        "BOLD_LINE_UNDERSCORE",
        "BOLD_DOTTED_LINE",
    )
