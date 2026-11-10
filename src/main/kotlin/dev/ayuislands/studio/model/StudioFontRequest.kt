package dev.ayuislands.studio.model

import dev.ayuislands.font.FontSettings

internal sealed interface StudioFontRequest {
    data object Disabled : StudioFontRequest

    data class Enabled(
        val settings: FontSettings,
    ) : StudioFontRequest

    data class Unavailable(
        val presetName: String,
    ) : StudioFontRequest
}
