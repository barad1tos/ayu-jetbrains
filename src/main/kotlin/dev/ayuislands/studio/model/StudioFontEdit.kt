package dev.ayuislands.studio.model

import dev.ayuislands.font.FontPreset
import dev.ayuislands.font.FontWeight

internal sealed interface StudioFontEdit {
    data class Enable(
        val enabled: Boolean,
    ) : StudioFontEdit

    data class Preset(
        val value: FontPreset,
    ) : StudioFontEdit

    data class Console(
        val enabled: Boolean,
    ) : StudioFontEdit

    data class Size(
        val value: Float,
    ) : StudioFontEdit

    data class Spacing(
        val value: Float,
    ) : StudioFontEdit

    data class Ligatures(
        val enabled: Boolean,
    ) : StudioFontEdit

    data class Weight(
        val value: FontWeight,
    ) : StudioFontEdit

    data class Family(
        val value: String,
    ) : StudioFontEdit

    data object Reset : StudioFontEdit

    data object Reapply : StudioFontEdit
}
