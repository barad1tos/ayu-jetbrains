package dev.ayuislands.accent

import com.intellij.lang.Language
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import dev.ayuislands.licensing.LicenseChecker
import dev.ayuislands.settings.AyuIslandsSettings
import java.awt.Color
import java.util.Locale

private typealias Source = AccentResolver.Source

/**
 * The single accent-resolution engine: one walk of the override priority
 * ladder (project override → forced language → language override / fallback →
 * project fallback), parameterized by an [AccentResolutionRequest].
 *
 * Every consumer is a projection of the same walk:
 * - [resolveAyu] / [resolveExternal] build the full diagnostics trace
 *   (steps + winner + verdict) for the status-bar widget and Settings.
 * - [overrideWinner] reduces the walk to its winning `(source, hex)` pair —
 *   the projection behind [AccentResolver.resolve], [AccentResolver.source],
 *   and the Settings pending preview in
 *   `dev.ayuislands.settings.mappings.OverridesGroupBuilder`.
 *
 * Parity between hex, source label, and the rendered chain therefore holds by
 * construction: they cannot disagree on ladder order, license gating, or
 * short-circuit points, only on the seams a request explicitly selects
 * (mapping source, detector consultation, hex validation).
 */
internal object AccentResolutionChainBuilder {
    private val LOG = logger<AccentResolutionChainBuilder>()

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

    /**
     * Runs one ladder walk for [request] and reduces it to the winning
     * override, or `null` when no override rung won (the caller then serves
     * its own global / external fallback). This is the ONLY reduction from
     * chain to accent — [AccentResolver.resolve] and [AccentResolver.source]
     * both go through here, so their answers agree by construction.
     */
    fun overrideWinner(
        project: Project?,
        request: AccentResolutionRequest,
    ): ResolvedAccent? {
        val steps = mutableListOf<AccentResolutionStep>()
        collectOverrideChainSteps(project, steps, request)
        val winner = steps.firstOrNull { it.outcome == StepOutcome.WON } ?: return null
        val hex = winner.hex
        if (hex == null) {
            // Unreachable today: every WON step built by collectOverrideChainSteps carries a
            // non-null hex. Lock kept against future step-construction refactors — degrading a
            // hex-less winner to "no override" keeps callers on their global fallback instead
            // of pushing a null hex toward the applicator. Source-locked in
            // `AccentResolutionChainBuilderGuardTest`.
            LOG.warn("Override winner ${winner.source} carried no hex; treating as no override")
            return null
        }
        return ResolvedAccent(winner.source, hex)
    }

    private fun collectOverrideChainSteps(
        project: Project?,
        steps: MutableList<AccentResolutionStep>,
        request: AccentResolutionRequest = AccentResolutionRequest.diagnostics(),
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

        val projectKey = AccentResolver.projectKey(activeProject)
        if (projectKey == null) {
            collectPremiumUnavailableSteps(steps, StepOutcome.NOT_APPLICABLE, DETAIL_NO_PROJECT_KEY)
            return null
        }

        if (collectProjectOverrideStep(projectKey, request, steps)) {
            return null
        }
        if (collectForcedLanguageStep(request.view.forcedLanguageId(projectKey), request, steps)) {
            return null
        }

        val hasForcedLanguageEntry = request.view.hasForcedLanguageEntry(projectKey)
        val hasFallbackCandidate = request.view.hasProjectFallbackCandidate(projectKey)
        val languageVerdict =
            collectLanguageOverrideStep(activeProject, hasForcedLanguageEntry, hasFallbackCandidate, request, steps)
        if (steps.lastOrNull()?.outcome == StepOutcome.WON) {
            return languageVerdict
        }
        if (languageVerdict == null && !hasFallbackCandidate) {
            return null
        }

        val fallbackVerdict = collectProjectFallbackStep(projectKey, activeProject, languageVerdict, request, steps)
        return languageVerdict ?: fallbackVerdict
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
        request: AccentResolutionRequest,
        steps: MutableList<AccentResolutionStep>,
    ): Boolean {
        val projectAccent = request.view.projectAccent(projectKey)
        if (projectAccent == null) {
            steps.add(
                AccentResolutionStep(Source.PROJECT_OVERRIDE, null, StepOutcome.NOT_SET, "No project override set"),
            )
            return false
        }

        val hex = request.policy.accept(Source.PROJECT_OVERRIDE, projectAccent)
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
        request: AccentResolutionRequest,
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

        val forcedAccent = request.view.languageAccent(forcedLanguageId)
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
                request,
                "Language fallback for forced ${displayNameFor(forcedLanguageId)}",
                steps,
            )
        }

        val hex = request.policy.accept(Source.FORCED_LANGUAGE_OVERRIDE, forcedAccent)
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
            request,
            "Language fallback for forced ${displayNameFor(forcedLanguageId)}",
            steps,
        )
    }

    /**
     * Language-override rung. Returns the verdict the detector produced, or
     * `null` when the rung was skipped — either because a forced-language
     * entry suppresses detection (any entry, even a blank-id one) or because
     * the [AccentDetectorLookup] declined to consult for the candidate set.
     */
    private fun collectLanguageOverrideStep(
        activeProject: Project,
        hasForcedLanguageEntry: Boolean,
        hasFallbackCandidate: Boolean,
        request: AccentResolutionRequest,
        steps: MutableList<AccentResolutionStep>,
    ): ProjectLanguageVerdict? {
        if (hasForcedLanguageEntry) {
            collectSkippedLanguageOverrideStep(hasForcedLanguageEntry = true, steps)
            return null
        }
        val hasLanguageCandidate = request.view.hasLanguageAccents || request.view.languageFallbackAccent != null
        val verdict = request.lookup.languageRungVerdict(activeProject, hasLanguageCandidate, hasFallbackCandidate)
        if (verdict == null) {
            collectSkippedLanguageOverrideStep(hasForcedLanguageEntry = false, steps)
            return null
        }
        when (verdict) {
            is ProjectLanguageVerdict.Detected -> {
                collectDetectedLanguageStep(verdict, request, steps)
            }

            is ProjectLanguageVerdict.NoWinner -> {
                collectNoWinnerLanguageStep(verdict, steps)
            }

            ProjectLanguageVerdict.Cold -> {
                steps.add(
                    AccentResolutionStep(Source.LANGUAGE_OVERRIDE, null, StepOutcome.NOT_SET, "Detection pending"),
                )
            }

            ProjectLanguageVerdict.Empty -> {
                steps.add(
                    AccentResolutionStep(
                        Source.LANGUAGE_OVERRIDE,
                        null,
                        StepOutcome.NOT_SET,
                        "No project languages detected",
                    ),
                )
            }

            ProjectLanguageVerdict.Unavailable -> {
                steps.add(
                    AccentResolutionStep(
                        Source.LANGUAGE_OVERRIDE,
                        null,
                        StepOutcome.UNAVAILABLE,
                        "Language detection unavailable",
                    ),
                )
            }
        }
        return verdict
    }

    private fun collectSkippedLanguageOverrideStep(
        hasForcedLanguageEntry: Boolean,
        steps: MutableList<AccentResolutionStep>,
    ) {
        val detail =
            if (hasForcedLanguageEntry) {
                "Skipped — forced language takes priority"
            } else {
                "No language accent mappings configured"
            }
        val outcome = if (hasForcedLanguageEntry) StepOutcome.NOT_APPLICABLE else StepOutcome.NOT_SET
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
        request: AccentResolutionRequest,
        steps: MutableList<AccentResolutionStep>,
    ) {
        val languageAccent = request.view.languageAccent(verdict.languageId)
        val hex = languageAccent?.let { request.policy.accept(Source.LANGUAGE_OVERRIDE, it) }
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
            request,
            "Language fallback for ${displayNameFor(verdict.languageId)}",
            steps,
        )
    }

    /**
     * Project-fallback rung. Returns the verdict it consulted, or `null`
     * when no fallback accent is stored — in that case the detector is not
     * consulted at all — the zero-work guarantee live resolution relies on.
     */
    private fun collectProjectFallbackStep(
        projectKey: String,
        activeProject: Project,
        languageVerdict: ProjectLanguageVerdict?,
        request: AccentResolutionRequest,
        steps: MutableList<AccentResolutionStep>,
    ): ProjectLanguageVerdict? {
        val fallbackAccent = request.view.projectFallbackAccent(projectKey)
        if (fallbackAccent == null) {
            steps.add(
                AccentResolutionStep(Source.PROJECT_FALLBACK, null, StepOutcome.NOT_SET, "No project fallback set"),
            )
            return null
        }

        val verdict = request.lookup.fallbackRungVerdict(activeProject, languageVerdict)
        if (verdict !is ProjectLanguageVerdict.NoWinner) {
            steps.add(
                AccentResolutionStep(
                    Source.PROJECT_FALLBACK,
                    null,
                    StepOutcome.NOT_APPLICABLE,
                    "Fallback only applies when no dominant language",
                ),
            )
            return verdict
        }

        val hex = request.policy.accept(Source.PROJECT_FALLBACK, fallbackAccent)
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
        return verdict
    }

    private fun collectLanguageFallbackStep(
        request: AccentResolutionRequest,
        detail: String,
        steps: MutableList<AccentResolutionStep>,
    ): Boolean {
        val fallbackHex = request.view.languageFallbackAccent ?: return false
        val hex = request.policy.accept(Source.LANGUAGE_FALLBACK_OVERRIDE, fallbackHex)
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
