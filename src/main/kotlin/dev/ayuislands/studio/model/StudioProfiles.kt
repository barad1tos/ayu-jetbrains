package dev.ayuislands.studio.model

@JvmInline
internal value class ProfileId(
    val value: String,
)

internal sealed interface ProfileDefinition {
    val id: ProfileId
    val name: String
    val appearance: AppearanceSnapshot

    data class Factory(
        override val id: ProfileId,
        override val name: String,
        override val appearance: AppearanceSnapshot,
        val recipeVersion: Int,
    ) : ProfileDefinition
}
