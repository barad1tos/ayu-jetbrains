package dev.ayuislands.studio.persistence

import dev.ayuislands.accent.LanguageDetectionRules

/** Ordered mappings and exact stored policy values, without path rewriting or native project discovery. */
internal class AccentMappingsView(
    values: LegacyValues,
) {
    val projectAccents: Map<String, String> = values.stringMap("projectAccents")
    val languageAccents: Map<String, String> = values.stringMap("languageAccents")
    val projectDisplayNames: Map<String, String> = values.stringMap("projectDisplayNames")
    val projectFallbackAccents: Map<String, String> = values.stringMap("projectFallbackAccents")
    val forcedProjectLanguages: Map<String, String> = values.stringMap("forcedProjectLanguages")
    val languageFallbackAccent: String? = values.text("languageFallbackAccent")
    val dominanceThreshold: Float = values.float("dominanceThreshold")
    val dominanceMarginRatio: Float = values.float("dominanceMarginRatio")
    val dominanceFloor: Float = values.float("dominanceFloor")
    val tiebreakMinShare: Float = values.float("tiebreakMinShare")

    fun languageResolutionPolicy(): LanguageDetectionRules.ResolutionPolicy =
        LanguageDetectionRules.ResolutionPolicy.fromStored(
            dominanceThreshold = dominanceThreshold.toString().toDouble(),
            dominanceMarginRatio = dominanceMarginRatio.toString().toDouble(),
            dominanceFloor = dominanceFloor.toString().toDouble(),
            tiebreakMinShare = tiebreakMinShare.toString().toDouble(),
        )
}
