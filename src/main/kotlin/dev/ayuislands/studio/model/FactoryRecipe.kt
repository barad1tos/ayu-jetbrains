package dev.ayuislands.studio.model

/** Immutable starter identities and their approved accents; behavior remains in the profile builder. */
internal enum class FactoryRecipe(
    val accent: String,
) {
    WHISPER("#D9BE98"),
    AMBIENT("#FFCD66"),
    NEON("#5CCFE6"),
    CYBERPUNK("#DFBFFF"),
    ;

    val id: ProfileId = ProfileId("factory:${name.lowercase()}")
}
