package dev.ayuislands.studio.model

/** Immutable starter identities and their approved accents; behavior remains in the profile builder. */
internal enum class FactoryRecipe(
    val id: ProfileId,
    val accent: String,
) {
    WHISPER(ProfileId("factory:whisper"), "#D9BE98"),
    AMBIENT(ProfileId("factory:ambient"), "#FFCD66"),
    NEON(ProfileId("factory:neon"), "#5CCFE6"),
    CYBERPUNK(ProfileId("factory:cyberpunk"), "#DFBFFF"),
}
