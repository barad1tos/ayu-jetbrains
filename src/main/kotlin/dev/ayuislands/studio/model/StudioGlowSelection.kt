package dev.ayuislands.studio.model

/** Explicit fields and dependent outputs to inspect; neither grants writes to unrelated native fields. */
internal data class StudioGlowSelection(
    val fields: StudioList<FieldAddress> = StudioList(emptyList()),
    val dependencies: StudioList<GlowDependency> = StudioList(emptyList()),
    val shouldReclassifyLanguage: Boolean = false,
)

internal enum class GlowDependency { ACCENT, AVAILABILITY }

/** Inputs of the existing accent resolver, separate from field types, defaults and ownership. */
internal object StudioGlowSources {
    val accents = StudioList(listOf("mirageAccent", "darkAccent", "lightAccent"))
    val rotation = StudioList(listOf("accentRotationMode", "accentRotationIntervalHours"))
    val plugins =
        StudioList(listOf("externalThemeAccentSource", "externalThemeAccent", "externalThemeAccentOverrideEnabled"))
    val languageRules =
        StudioList(
            listOf("dominanceThreshold", "dominanceMarginRatio", "dominanceFloor", "tiebreakMinShare"),
        )
    val mappings =
        StudioList(
            listOf(
                "projectAccents",
                "languageAccents",
                "forcedProjectLanguages",
                "projectFallbackAccents",
                "languageFallbackAccent",
            ) + languageRules,
        )
}
