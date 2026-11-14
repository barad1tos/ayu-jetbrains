package dev.ayuislands.studio.model

/** Native context reapplication has no profile activation or raw preference intent. */
internal sealed interface StudioNativeChange {
    data class Theme(
        val selection: StudioThemeChange,
    ) : StudioNativeChange

    data object Appearance : StudioNativeChange

    data object AccentContext : StudioNativeChange
}

/** Explicit user selection and a still-enabled system follower share the native theme owner. */
internal sealed interface StudioThemeChange {
    data class Explicit(
        val name: String,
    ) : StudioThemeChange

    data object FollowSystem : StudioThemeChange
}
