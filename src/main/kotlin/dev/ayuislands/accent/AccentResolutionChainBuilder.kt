package dev.ayuislands.accent

import com.intellij.lang.Language
import com.intellij.openapi.project.Project
import dev.ayuislands.licensing.LicenseChecker
import dev.ayuislands.settings.AyuIslandsSettings
import dev.ayuislands.settings.mappings.AccentMappingsSettings
import dev.ayuislands.settings.mappings.AccentMappingsState
import java.awt.Color
import java.util.Locale

private typealias Source = AccentResolver.Source

internal object AccentResolutionChainBuilder {
    private const val PROPORTIONS_TOP_N = 3
    private const val NO_WINNER_TOP_N = 2
    private const val PERCENTAGE_SCALE = 100
    private const val DETAIL_LICENSE_BLOCKED = "Premium feature — license required"
    private const val DETAIL_NO_PROJECT = "No project open"
    private const val DETAIL_NO_PROJECT_KEY = "Project path unavailable"

    private const val MATERIAL_ACCENT_KEY = "material.accent"
    private const val COMPONENT_ACCENT_KEY = "Component.accentColor"
    private const val ACTIONS_BLUE_KEY = "Actions.Blue"

    fun resolveAyu(
        project: Project?,
        variant: AyuVariant,
    ): AccentResolutionChain {
        val globalAccent = AyuIslandsSettings.getInstance().getAccentForVariant(variant)
        val steps = mutableListOf<AccentResolutionStep>()
        val verdict = collectOverrideChainSteps(project, steps)
        val winner =
            steps.firstOrNull { it.outcome == StepOutcome.WON }
                ?: AccentResolutionStep(
                    Source.GLOBAL,
                    globalAccent,
                    StepOutcome.WON,
                    "Default accent for ${variant.name.lowercase()}",
                )
        if (winner.source == Source.GLOBAL) {
            steps.add(winner)
        }
        return AccentResolutionChain(steps, winner, verdict)
    }

    fun resolveExternal(project: Project?): AccentResolutionChain {
        val state = AyuIslandsSettings.getInstance().state
        val steps = mutableListOf<AccentResolutionStep>()
        val isManual =
            ExternalAccentSource.fromName(state.externalThemeAccentSource) ==
                ExternalAccentSource.MANUAL
        if (isManual) {
            val hex = AccentHex.of(state.externalThemeAccent)?.value ?: AyuVariant.MIRAGE.defaultAccent
            val step =
                AccentResolutionStep(
                    Source.EXTERNAL_ACCENT,
                    hex,
                    StepOutcome.WON,
                    "Manual accent selection",
                )
            return AccentResolutionChain(listOf(step), step, null)
        }

        val verdict = collectOverrideChainSteps(project, steps)
        collectUiColorStep(
            steps,
            Source.MATERIAL_THEME,
            uiColorHex(MATERIAL_ACCENT_KEY),
            "Material Theme accent found",
            "No Material Theme accent",
        )?.let { return AccentResolutionChain(steps, it, verdict) }

        val ideHex = uiColorHex(COMPONENT_ACCENT_KEY) ?: uiColorHex(ACTIONS_BLUE_KEY)
        collectUiColorStep(steps, Source.IDE_ACCENT, ideHex, "IDE accent found", "No IDE accent")
            ?.let { return AccentResolutionChain(steps, it, verdict) }

        val storedHex = AccentHex.of(state.externalThemeAccent)?.value ?: AyuVariant.MIRAGE.defaultAccent
        val winner =
            steps.firstOrNull { it.outcome == StepOutcome.WON }
                ?: AccentResolutionStep(
                    Source.EXTERNAL_ACCENT,
                    storedHex,
                    StepOutcome.WON,
                    "Stored external accent",
                )
        if (winner.source == Source.EXTERNAL_ACCENT) {
            steps.add(winner)
        }
        return AccentResolutionChain(steps, winner, verdict)
    }

    private fun collectOverrideChainSteps(
        project: Project?,
        steps: MutableList<AccentResolutionStep>,
    ): ProjectLanguageVerdict? {
        if (!LicenseChecker.isLicensedOrGrace()) {
            collectPremiumUnavailableSteps(steps, StepOutcome.LICENSE_BLOCKED, DETAIL_LICENSE_BLOCKED)
            return null
        }

        val activeProject =
            project
                ?.takeUnless { it.isDefault }
                ?.takeUnless { it.isDisposed }
        if (activeProject == null) {
            collectPremiumUnavailableSteps(steps, StepOutcome.NOT_APPLICABLE, DETAIL_NO_PROJECT)
            return null
        }

        val mappings = AccentMappingsSettings.getInstance().state
        val projectKey = AccentResolver.projectKey(activeProject)
        if (projectKey == null) {
            collectPremiumUnavailableSteps(steps, StepOutcome.NOT_APPLICABLE, DETAIL_NO_PROJECT_KEY)
            return null
        }

        if (collectProjectOverrideStep(projectKey, mappings, steps)) {
            return null
        }

        val forcedLanguageId = mappings.forcedProjectLanguages[projectKey]
        if (collectForcedLanguageStep(forcedLanguageId, mappings, steps)) {
            return null
        }
        val hasProjectFallbackCandidate = mappings.projectFallbackAccents.containsKey(projectKey)

        val verdict =
            collectLanguageOverrideStep(
                forcedLanguageId = forcedLanguageId,
                mappings = mappings,
                activeProject = activeProject,
                steps = steps,
                shouldConsultForProjectFallback = hasProjectFallbackCandidate,
            )
                ?: return null
        if (steps.lastOrNull()?.outcome == StepOutcome.WON) {
            return verdict
        }

        collectProjectFallbackStep(projectKey, verdict, mappings, steps)
        return verdict
    }

    private fun collectPremiumUnavailableSteps(
        steps: MutableList<AccentResolutionStep>,
        outcome: StepOutcome,
        detail: String,
    ) {
        val premiumSources =
            listOf(
                Source.PROJECT_OVERRIDE,
                Source.FORCED_LANGUAGE_OVERRIDE,
                Source.LANGUAGE_OVERRIDE,
                Source.PROJECT_FALLBACK,
            )
        for (source in premiumSources) {
            steps.add(AccentResolutionStep(source, null, outcome, detail))
        }
    }

    private fun collectProjectOverrideStep(
        projectKey: String,
        mappings: AccentMappingsState,
        steps: MutableList<AccentResolutionStep>,
    ): Boolean {
        val projectAccent = mappings.projectAccents[projectKey]
        if (projectAccent == null) {
            steps.add(
                AccentResolutionStep(Source.PROJECT_OVERRIDE, null, StepOutcome.NOT_SET, "No project override set"),
            )
            return false
        }

        val hex = AccentHex.of(projectAccent)?.value
        if (hex != null) {
            steps.add(
                AccentResolutionStep(Source.PROJECT_OVERRIDE, hex, StepOutcome.WON, "Pinned accent for this project"),
            )
            return true
        }

        steps.add(
            AccentResolutionStep(Source.PROJECT_OVERRIDE, null, StepOutcome.NOT_SET, "Invalid hex in project override"),
        )
        return false
    }

    private fun collectForcedLanguageStep(
        forcedLanguageId: String?,
        mappings: AccentMappingsState,
        steps: MutableList<AccentResolutionStep>,
    ): Boolean {
        if (forcedLanguageId == null) {
            steps.add(
                AccentResolutionStep(
                    Source.FORCED_LANGUAGE_OVERRIDE,
                    null,
                    StepOutcome.NOT_SET,
                    "No forced language set",
                ),
            )
            return false
        }

        val forcedAccent = mappings.languageAccents[forcedLanguageId]
        if (forcedAccent == null) {
            steps.add(
                AccentResolutionStep(
                    Source.FORCED_LANGUAGE_OVERRIDE,
                    null,
                    StepOutcome.NO_MAPPING,
                    "Forced language ${displayNameFor(forcedLanguageId)} has no accent mapping",
                ),
            )
            return collectLanguageFallbackStep(
                mappings.languageFallbackAccent,
                "Language fallback for forced ${displayNameFor(forcedLanguageId)}",
                steps,
            )
        }

        val hex = AccentHex.of(forcedAccent)?.value
        if (hex != null) {
            steps.add(
                AccentResolutionStep(
                    Source.FORCED_LANGUAGE_OVERRIDE,
                    hex,
                    StepOutcome.WON,
                    "Forced language: ${displayNameFor(forcedLanguageId)}",
                ),
            )
            return true
        }

        steps.add(
            AccentResolutionStep(
                Source.FORCED_LANGUAGE_OVERRIDE,
                null,
                StepOutcome.NOT_SET,
                "Invalid hex for forced language ${displayNameFor(forcedLanguageId)}",
            ),
        )
        return collectLanguageFallbackStep(
            mappings.languageFallbackAccent,
            "Language fallback for forced ${displayNameFor(forcedLanguageId)}",
            steps,
        )
    }

    private fun collectLanguageOverrideStep(
        forcedLanguageId: String?,
        mappings: AccentMappingsState,
        activeProject: Project,
        steps: MutableList<AccentResolutionStep>,
        shouldConsultForProjectFallback: Boolean,
    ): ProjectLanguageVerdict? {
        val hasLanguageCandidate =
            mappings.languageAccents.isNotEmpty() ||
                mappings.languageFallbackAccent != null
        if (forcedLanguageId != null) {
            collectSkippedLanguageOverrideStep(forcedLanguageId, steps)
            return if (shouldConsultForProjectFallback) {
                ProjectLanguageDetector.verdict(activeProject)
            } else {
                null
            }
        }
        if (!hasLanguageCandidate && !shouldConsultForProjectFallback) {
            collectSkippedLanguageOverrideStep(forcedLanguageId, steps)
            return null
        }

        val verdict = ProjectLanguageDetector.verdict(activeProject)
        when (verdict) {
            is ProjectLanguageVerdict.Detected -> collectDetectedLanguageStep(verdict, mappings, steps)
            is ProjectLanguageVerdict.NoWinner -> collectNoWinnerLanguageStep(verdict, steps)
            ProjectLanguageVerdict.Cold ->
                steps.add(
                    AccentResolutionStep(Source.LANGUAGE_OVERRIDE, null, StepOutcome.NOT_SET, "Detection pending"),
                )
            ProjectLanguageVerdict.Empty ->
                steps.add(
                    AccentResolutionStep(
                        Source.LANGUAGE_OVERRIDE,
                        null,
                        StepOutcome.NOT_SET,
                        "No project languages detected",
                    ),
                )
            ProjectLanguageVerdict.Unavailable ->
                steps.add(
                    AccentResolutionStep(
                        Source.LANGUAGE_OVERRIDE,
                        null,
                        StepOutcome.UNAVAILABLE,
                        "Language detection unavailable",
                    ),
                )
        }
        return verdict
    }

    private fun collectSkippedLanguageOverrideStep(
        forcedLanguageId: String?,
        steps: MutableList<AccentResolutionStep>,
    ) {
        val detail =
            if (forcedLanguageId != null) {
                "Skipped — forced language takes priority"
            } else {
                "No language accent mappings configured"
            }
        val outcome = if (forcedLanguageId != null) StepOutcome.NOT_APPLICABLE else StepOutcome.NOT_SET
        steps.add(AccentResolutionStep(Source.LANGUAGE_OVERRIDE, null, outcome, detail))
    }

    private fun collectNoWinnerLanguageStep(
        verdict: ProjectLanguageVerdict.NoWinner,
        steps: MutableList<AccentResolutionStep>,
    ) {
        val total = verdict.weights.values.sum()
        val detail =
            verdict.weights.entries
                .sortedByDescending { it.value }
                .take(NO_WINNER_TOP_N)
                .joinToString(", ") { (lang, weight) ->
                    "${displayNameFor(lang)} ${proportionPct(weight, total)}"
                }
        steps.add(
            AccentResolutionStep(
                Source.LANGUAGE_OVERRIDE,
                null,
                StepOutcome.NOT_DOMINANT,
                "No dominant language: $detail",
            ),
        )
    }

    private fun collectDetectedLanguageStep(
        verdict: ProjectLanguageVerdict.Detected,
        mappings: AccentMappingsState,
        steps: MutableList<AccentResolutionStep>,
    ) {
        val langAccent = mappings.languageAccents[verdict.languageId]
        val hex = langAccent?.let { AccentHex.of(it)?.value }
        if (hex != null) {
            steps.add(
                AccentResolutionStep(
                    Source.LANGUAGE_OVERRIDE,
                    hex,
                    StepOutcome.WON,
                    detectedLanguageDetail(verdict),
                ),
            )
            return
        }

        steps.add(
            AccentResolutionStep(
                Source.LANGUAGE_OVERRIDE,
                null,
                StepOutcome.NO_MAPPING,
                "Detected ${displayNameFor(verdict.languageId)} but no accent mapping",
            ),
        )
        collectLanguageFallbackStep(
            mappings.languageFallbackAccent,
            "Language fallback for ${displayNameFor(verdict.languageId)}",
            steps,
        )
    }

    private fun collectProjectFallbackStep(
        projectKey: String,
        verdict: ProjectLanguageVerdict,
        mappings: AccentMappingsState,
        steps: MutableList<AccentResolutionStep>,
    ) {
        val fallbackHex = mappings.projectFallbackAccents[projectKey]
        if (fallbackHex == null) {
            steps.add(
                AccentResolutionStep(Source.PROJECT_FALLBACK, null, StepOutcome.NOT_SET, "No project fallback set"),
            )
            return
        }

        if (verdict !is ProjectLanguageVerdict.NoWinner) {
            steps.add(
                AccentResolutionStep(
                    Source.PROJECT_FALLBACK,
                    null,
                    StepOutcome.NOT_APPLICABLE,
                    "Fallback only applies when no dominant language",
                ),
            )
            return
        }

        val hex = AccentHex.of(fallbackHex)?.value
        if (hex != null) {
            steps.add(
                AccentResolutionStep(
                    Source.PROJECT_FALLBACK,
                    hex,
                    StepOutcome.WON,
                    "Project fallback (polyglot project)",
                ),
            )
        } else {
            steps.add(
                AccentResolutionStep(
                    Source.PROJECT_FALLBACK,
                    null,
                    StepOutcome.NOT_SET,
                    "Invalid hex in project fallback",
                ),
            )
        }
    }

    private fun collectLanguageFallbackStep(
        rawHex: String?,
        detail: String,
        steps: MutableList<AccentResolutionStep>,
    ): Boolean {
        val fallbackHex = rawHex ?: return false
        val hex = AccentHex.of(fallbackHex)?.value
        if (hex != null) {
            steps.add(
                AccentResolutionStep(
                    Source.LANGUAGE_FALLBACK_OVERRIDE,
                    hex,
                    StepOutcome.WON,
                    detail,
                ),
            )
            return true
        }

        steps.add(
            AccentResolutionStep(
                Source.LANGUAGE_FALLBACK_OVERRIDE,
                null,
                StepOutcome.NOT_SET,
                "Invalid language fallback accent",
            ),
        )
        return false
    }

    private fun collectUiColorStep(
        steps: MutableList<AccentResolutionStep>,
        source: Source,
        hex: String?,
        foundDetail: String,
        missingDetail: String,
    ): AccentResolutionStep? {
        if (hex != null) {
            val step = AccentResolutionStep(source, hex, StepOutcome.WON, foundDetail)
            steps.add(step)
            return step
        }
        steps.add(AccentResolutionStep(source, null, StepOutcome.NOT_SET, missingDetail))
        return null
    }

    private fun uiColorHex(key: String): String? = AccentResolver.uiColorForDiagnostics(key)?.toHex()

    private fun displayNameFor(languageId: String): String =
        Language
            .getRegisteredLanguages()
            .firstOrNull { it.id.equals(languageId, ignoreCase = true) }
            ?.displayName
            ?.takeIf { it.isNotBlank() }
            ?: languageId

    private fun detectedLanguageDetail(verdict: ProjectLanguageVerdict.Detected): String {
        val languageDisplayName = displayNameFor(verdict.languageId)
        val weights = verdict.weights ?: return "Detected $languageDisplayName"
        val total = weights.values.sum()
        val detectedEntry = weights.entries.firstOrNull { it.key.equals(verdict.languageId, ignoreCase = true) }
        val otherEntries =
            weights.entries
                .filterNot { it.key.equals(verdict.languageId, ignoreCase = true) }
                .sortedByDescending { it.value }
                .take(PROPORTIONS_TOP_N - 1)
        val detailSegments =
            buildList {
                detectedEntry?.let { add(proportionPct(it.value, total)) }
                otherEntries.mapTo(this) { (lang, weight) ->
                    "${displayNameFor(lang)} ${proportionPct(weight, total)}"
                }
            }
        if (detailSegments.isEmpty()) return "Detected $languageDisplayName"
        return "Detected $languageDisplayName ${detailSegments.joinToString(", ")}"
    }

    private fun proportionPct(
        weight: Long,
        total: Long,
    ): String {
        if (total == 0L) return "0%"
        val percentage = weight * PERCENTAGE_SCALE / total
        return "$percentage%"
    }

    private fun Color.toHex(): String = "#%02X%02X%02X".format(Locale.ROOT, red, green, blue)
}
