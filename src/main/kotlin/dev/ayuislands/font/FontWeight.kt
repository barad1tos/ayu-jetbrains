package dev.ayuislands.font

import java.awt.font.TextAttribute

/**
 * Font weight options.
 *
 * [subFamily] is passed to `ModifiableFontPreferences.setRegularSubFamily`
 * to select the weight variant within a font family.
 * [textAttributeValue] is used for Graphics2D preview rendering via TextAttribute.WEIGHT.
 */
enum class FontWeight(
    val displayName: String,
    val subFamily: String,
    val textAttributeValue: Float,
) {
    THIN("Thin", "Thin", TextAttribute.WEIGHT_EXTRA_LIGHT),
    EXTRA_LIGHT("Extra Light", "ExtraLight", TextAttribute.WEIGHT_LIGHT),
    LIGHT("Light", "Light", TextAttribute.WEIGHT_DEMILIGHT),
    REGULAR("Regular", "", TextAttribute.WEIGHT_REGULAR),
    MEDIUM("Medium", "Medium", TextAttribute.WEIGHT_SEMIBOLD),
    SEMI_BOLD("Semi Bold", "SemiBold", TextAttribute.WEIGHT_BOLD),
    ;

    companion object {
        fun fromName(name: String?): FontWeight = entries.firstOrNull { it.name == name } ?: REGULAR
    }
}
