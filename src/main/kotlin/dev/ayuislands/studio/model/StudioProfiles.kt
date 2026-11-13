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

    data class User(
        override val id: ProfileId,
        override val name: String,
        override val appearance: AppearanceSnapshot,
        val revision: Long,
    ) : ProfileDefinition
}

internal data class ProfileReference(
    val id: ProfileId,
    val revision: Long,
)

internal data class AcceptedAppearance(
    val appearance: AppearanceSnapshot,
    val revision: Long,
    val source: ProfileReference?,
)

@JvmInline
internal value class StudioProjectKey(
    val value: String,
)

internal sealed interface StudioTarget {
    data object Global : StudioTarget

    data class Project(
        val key: StudioProjectKey,
    ) : StudioTarget
}
