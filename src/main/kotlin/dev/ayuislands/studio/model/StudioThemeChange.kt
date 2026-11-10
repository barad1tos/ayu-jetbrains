package dev.ayuislands.studio.model

/** Explicit user selection and a still-enabled system follower share the native theme owner. */
internal sealed interface StudioThemeChange {
    data class Explicit(
        val name: String,
    ) : StudioThemeChange

    data object FollowSystem : StudioThemeChange
}
