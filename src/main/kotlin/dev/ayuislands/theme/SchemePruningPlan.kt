package dev.ayuislands.theme

import com.intellij.openapi.editor.colors.ColorKey
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.editor.colors.impl.DefaultColorsScheme
import com.intellij.openapi.editor.markup.TextAttributes
import java.awt.Color

/** Native preparation data only; it does not acquire ownership or authorize writes without the owner's receipt. */
internal sealed interface SchemePruningPlan {
    data object Unchanged : SchemePruningPlan

    data object Unavailable : SchemePruningPlan

    class Prepared(
        val parent: DefaultColorsScheme,
        val attributes: Map<TextAttributesKey, List<TextAttributes>>,
        val colors: Map<ColorKey, List<Color>>,
    ) : SchemePruningPlan
}
