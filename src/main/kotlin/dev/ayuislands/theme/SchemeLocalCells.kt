package dev.ayuislands.theme

import com.intellij.openapi.editor.markup.TextAttributes
import java.awt.Color

/** Exact local membership; a value retains its native identity instead of an effective fallback. */
internal sealed interface SchemeColorCell {
    data object Absent : SchemeColorCell

    data object Inherited : SchemeColorCell

    data object Null : SchemeColorCell

    class Value(
        val color: Color,
    ) : SchemeColorCell
}

internal sealed interface SchemeAttributeCell {
    data object Absent : SchemeAttributeCell

    data object Inherited : SchemeAttributeCell

    class Value(
        val attributes: TextAttributes,
    ) : SchemeAttributeCell
}

internal enum class SchemeAttributeSeed {
    NONE,
    INHERITED,
    PARENT,
    UNAVAILABLE,
}

internal enum class SchemeColorSeed {
    NONE,
    INHERITED,
    NULL,
    PARENT,
    BARRIER,
}
