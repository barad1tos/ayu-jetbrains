package dev.ayuislands.studio.session

import com.intellij.openapi.project.Project
import dev.ayuislands.accent.AccentContext
import dev.ayuislands.accent.AccentDetectorLookup
import dev.ayuislands.accent.AccentFallback
import dev.ayuislands.accent.AccentHexPolicy
import dev.ayuislands.accent.AccentResolutionRequest
import dev.ayuislands.accent.AccentResolver
import dev.ayuislands.accent.AyuVariant
import dev.ayuislands.accent.PersistedAccentMappingsView
import dev.ayuislands.accent.ProjectLanguageDetector
import dev.ayuislands.accent.ResolvedAccent
import dev.ayuislands.licensing.LicenseChecker
import dev.ayuislands.licensing.LicenseEntitlement
import dev.ayuislands.studio.model.StudioAcceptedInput
import dev.ayuislands.studio.model.StudioList

/** Resolves one candidate against observations supplied by the outer native capability/theme boundary. */
internal class StudioAccentResolver(
    private val input: StudioEffectInput,
    variant: AyuVariant?,
    systemAccent: String?,
    private val entitlement: LicenseEntitlement,
) {
    init {
        require(entitlement != LicenseEntitlement.UNKNOWN) { "Candidate Accent requires effective entitlement" }
    }

    private val held = StudioEffectiveAccent.held(input, variant)
    val context: AccentContext? = StudioAccentProjection.context(input.candidate.shared.plugins, variant)
    private val fallback =
        context?.let { selected ->
            StudioEffectiveAccent.fallback(input, selected, systemAccent)
        }
    private val mappings by lazy(LazyThreadSafetyMode.NONE) {
        StudioAccentProjection.mappings(input.candidate.shared.mappings)
    }

    fun resolve(project: Project?): ResolvedAccent? = resolveChoice(project)?.accent

    fun resolve(
        focusedProject: Project?,
        projects: List<Project>,
    ): StudioAccentResult {
        if (context == null) return StudioAccentResult(null, focusedProject, null, StudioList(emptyList()))
        val census =
            projects.map { project ->
                val choice = checkNotNull(resolveChoice(project))
                StudioProjectAccent(project, choice.accent, choice.dependencies)
            }
        val focused =
            census.firstOrNull { it.project === focusedProject }?.let { item ->
                StudioAccentChoice(item.accent, item.dependencies)
            } ?: resolveChoice(focusedProject)
        return StudioAccentResult(
            context,
            focusedProject,
            focused?.accent,
            StudioList(census),
            focused?.dependencies ?: StudioList(emptyList()),
        )
    }

    private fun resolveChoice(project: Project?): StudioAccentChoice? {
        val selectedFallback = fallback ?: return null
        if (project == null && held != null) {
            val dependencies =
                checkNotNull(
                    input.candidate.runtime.effectiveContinuation
                        ?.accent,
                ).dependencies
            return StudioAccentChoice(held, dependencies)
        }
        val dependencies =
            StudioAccentDependencies(
                input.candidate.shared.mappings,
                PersistedAccentMappingsView { mappings },
            )
        val lookup =
            AccentDetectorLookup.SnapshotLookup(shouldReadFallbackEarly = false) { target ->
                dependencies.recordRules()
                if (input.glowSelection.shouldReclassifyLanguage) {
                    ProjectLanguageDetector.verdict(
                        target,
                        mappings.languageResolutionPolicy(),
                        mappings.languageAccents.keys,
                    )
                } else {
                    ProjectLanguageDetector.verdict(target)
                }
            }
        val policy = if (selectedFallback is AccentFallback.Global) AccentHexPolicy.LENIENT else AccentHexPolicy.STRICT
        val request = AccentResolutionRequest(dependencies, lookup, policy)
        val resolved =
            LicenseChecker.withConfirmedEntitlement(entitlement) {
                AccentResolver.resolve(project, request, selectedFallback)
            }
        return StudioAccentChoice(resolved, dependencies.inputs)
    }
}

private data class StudioAccentChoice(
    val accent: ResolvedAccent,
    val dependencies: StudioList<StudioAcceptedInput>,
)
