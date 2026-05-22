package dev.ayuislands.syntax

/**
 * Phase 49 cross-cutting style modifiers. Each axis applies a deterministic
 * transform across all 26 supported languages simultaneously. Axes stack
 * additively on top of the active mood (D-07).
 *
 * Callers parse stored enum names via
 * `runCatching { StyleAxis.valueOf(name) }.getOrNull()` and filter nulls
 * (no [SyntaxMood]-style `fromName` factory — tampered names are dropped, not
 * coerced to a default, because each axis is opt-in).
 */
enum class StyleAxis(
    val displayName: String,
    val description: String,
) {
    ITALIC_DECLARATIONS(
        "Italic declarations",
        "Italicize class / function / property declarations",
    ),
    BOLD_TYPE_REFERENCES(
        "Bold type references",
        "Bold-face type names where they appear as references",
    ),
    DIMMED_COMMENTS(
        "Dimmed comments",
        "Multiply comment foreground RGB by 0.6 (preserves alpha)",
    ),
    ITALIC_DOC_TAGS(
        "Italic doc tags",
        "Italicize KDoc / JavaDoc / ScalaDoc tags",
    ),
}
