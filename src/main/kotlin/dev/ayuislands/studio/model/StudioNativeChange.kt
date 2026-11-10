package dev.ayuislands.studio.model

/** Native context reapplication has no profile activation or raw preference intent. */
internal sealed interface StudioNativeChange {
    data class Theme(
        val selection: StudioThemeChange,
    ) : StudioNativeChange

    data object Appearance : StudioNativeChange

    data object AccentContext : StudioNativeChange
}
