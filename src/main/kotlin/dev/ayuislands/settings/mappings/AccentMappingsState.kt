package dev.ayuislands.settings.mappings

import com.intellij.openapi.components.BaseState
import dev.ayuislands.accent.AccentOverrideSnapshot
import dev.ayuislands.accent.LanguageDetectionRules

/**
 * Persisted application-level accent overrides resolved in priority order:
 * project (by canonical path) > forced/detected language > language fallback >
 * project fallback for polyglot no-winner scans > global.
 *
 * Display names are kept alongside project paths so orphaned rows (path no longer
 * exists on disk) can still render meaningfully in the settings table.
 */
class AccentMappingsState : BaseState() {
    var projectAccents: MutableMap<String, String> by map()
    var languageAccents: MutableMap<String, String> by map()
    var projectDisplayNames: MutableMap<String, String> by map()
    var projectFallbackAccents: MutableMap<String, String> by map()
    var forcedProjectLanguages: MutableMap<String, String> by map()
    var languageFallbackAccent: String? by string(null)
    var dominanceThreshold: Float by property(LanguageDetectionRules.DEFAULT_DOMINANCE_THRESHOLD.toFloat())
    var dominanceMarginRatio: Float by property(LanguageDetectionRules.DOMINANCE_MARGIN_RATIO.toFloat())
    var dominanceFloor: Float by property(LanguageDetectionRules.DOMINANCE_FLOOR.toFloat())
    var tiebreakMinShare: Float by property(LanguageDetectionRules.TIE_BREAK_MIN_SHARE.toFloat())

    internal fun languageResolutionPolicy(): LanguageDetectionRules.ResolutionPolicy =
        LanguageDetectionRules.ResolutionPolicy.fromStored(
            dominanceThreshold = dominanceThreshold.toStoredDouble(),
            dominanceMarginRatio = dominanceMarginRatio.toStoredDouble(),
            dominanceFloor = dominanceFloor.toStoredDouble(),
            tiebreakMinShare = tiebreakMinShare.toStoredDouble(),
        )

    internal fun toAccentOverrideSnapshot(): AccentOverrideSnapshot =
        AccentOverrideSnapshot(
            projectAccents = projectAccents.toMap(),
            languageAccents = languageAccents.toMap(),
            projectFallbackAccents = projectFallbackAccents.toMap(),
            forcedProjectLanguages = forcedProjectLanguages.toMap(),
            languageFallbackAccent = languageFallbackAccent,
        )
}

private fun Float.toStoredDouble(): Double = toString().toDouble()
