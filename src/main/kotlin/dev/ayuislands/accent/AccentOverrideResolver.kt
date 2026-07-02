package dev.ayuislands.accent

internal data class AccentOverrideSnapshot(
    val projectAccents: Map<String, String> = emptyMap(),
    val languageAccents: Map<String, String> = emptyMap(),
    val projectFallbackAccents: Map<String, String> = emptyMap(),
    val forcedProjectLanguages: Map<String, String> = emptyMap(),
    val languageFallbackAccent: String? = null,
)

internal object AccentOverrideResolver {
    data class Request(
        val projectKey: String?,
        val snapshot: AccentOverrideSnapshot,
        val overridesEnabled: Boolean,
        val validateHex: Boolean,
        val warmLanguageDetection: Boolean = true,
        val languageDetection: LanguageDetection,
    )

    /**
     * Project-language read adapter for resolver callers.
     *
     * [detectedLanguage] and [verdict] must honor `warmCache`: `false` is a
     * cache-only read with no scan scheduling. The resolver passes `true` at
     * most once per resolution path, then uses cache-only verdict reads for
     * fallback checks after detection was already consulted.
     */
    data class LanguageDetection(
        val detectedLanguage: (warmCache: Boolean) -> String?,
        val verdict: (warmCache: Boolean) -> ProjectLanguageVerdict,
    )

    data class Result(
        val source: AccentResolver.Source,
        val hex: String,
    )

    fun source(request: Request): AccentResolver.Source = resolve(request)?.source ?: AccentResolver.Source.GLOBAL

    fun resolve(request: Request): Result? {
        if (!request.overridesEnabled) return null
        val projectKey = request.projectKey?.takeIf { it.isNotBlank() } ?: return null

        request.snapshot.projectAccents[projectKey]
            ?.let { rawHex -> overrideAccent(AccentResolver.Source.PROJECT_OVERRIDE, rawHex, request.validateHex) }
            ?.let { return it }

        val languageRequest =
            LanguageRequest(
                snapshot = request.snapshot,
                projectKey = projectKey,
            )
        val hasProjectFallbackCandidate = request.snapshot.projectFallbackAccents.containsKey(projectKey)
        if (!languageRequest.hasResolutionWork && !hasProjectFallbackCandidate) return null

        val languageResult = resolveLanguageOverride(request, languageRequest)
        languageResult.accent?.let { return it }

        val fallbackAccent = request.snapshot.projectFallbackAccents[projectKey] ?: return null
        val fallbackVerdict =
            request
                .languageDetection
                .verdict(!languageResult.detectionConsulted && request.warmLanguageDetection)
        return fallbackAccent
            .takeIf { fallbackVerdict is ProjectLanguageVerdict.NoWinner }
            ?.let { rawHex -> overrideAccent(AccentResolver.Source.PROJECT_FALLBACK, rawHex, request.validateHex) }
    }

    private fun resolveLanguageOverride(
        request: Request,
        languageRequest: LanguageRequest,
    ): LanguageResult {
        languageRequest.forcedLanguageId
            ?.let { languageId ->
                overrideAccentForLanguage(
                    languageAccents = request.snapshot.languageAccents,
                    languageFallbackAccent = languageRequest.languageFallbackAccent,
                    languageId = languageId,
                    exactSource = AccentResolver.Source.FORCED_LANGUAGE_OVERRIDE,
                    validateHex = request.validateHex,
                )?.let { return LanguageResult(it, detectionConsulted = false) }
            }
        if (!languageRequest.shouldDetectLanguage) {
            return LanguageResult(accent = null, detectionConsulted = false)
        }
        val resolvedAccent =
            request
                .languageDetection
                .detectedLanguage(request.warmLanguageDetection)
                ?.let { languageId ->
                    overrideAccentForLanguage(
                        languageAccents = request.snapshot.languageAccents,
                        languageFallbackAccent = languageRequest.languageFallbackAccent,
                        languageId = languageId,
                        exactSource = AccentResolver.Source.LANGUAGE_OVERRIDE,
                        validateHex = request.validateHex,
                    )
                }
        return LanguageResult(resolvedAccent, detectionConsulted = true)
    }

    private data class LanguageRequest(
        val snapshot: AccentOverrideSnapshot,
        val projectKey: String,
    ) {
        val hasForcedLanguageEntry: Boolean = snapshot.forcedProjectLanguages.containsKey(projectKey)
        val forcedLanguageId: String? =
            snapshot.forcedProjectLanguages[projectKey]
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
        val languageFallbackAccent: String? =
            snapshot.languageFallbackAccent
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
        private val hasLanguageCandidate = snapshot.languageAccents.isNotEmpty() || languageFallbackAccent != null

        val hasResolutionWork: Boolean =
            hasLanguageCandidate && (forcedLanguageId != null || !hasForcedLanguageEntry)

        val shouldDetectLanguage: Boolean =
            hasLanguageCandidate && !hasForcedLanguageEntry
    }

    private data class LanguageResult(
        val accent: Result?,
        val detectionConsulted: Boolean,
    )

    private fun overrideAccentForLanguage(
        languageAccents: Map<String, String>,
        languageFallbackAccent: String?,
        languageId: String,
        exactSource: AccentResolver.Source,
        validateHex: Boolean,
    ): Result? {
        languageAccents[languageId]
            ?.let { rawHex -> overrideAccent(exactSource, rawHex, validateHex) }
            ?.let { return it }
        return languageFallbackAccent
            ?.let { rawHex -> overrideAccent(AccentResolver.Source.LANGUAGE_FALLBACK_OVERRIDE, rawHex, validateHex) }
    }

    private fun overrideAccent(
        source: AccentResolver.Source,
        rawHex: String,
        validateHex: Boolean,
    ): Result? {
        val accent = AccentHex.of(rawHex)
        if (accent != null) {
            return Result(source, accent.value)
        }
        if (!validateHex && source != AccentResolver.Source.LANGUAGE_FALLBACK_OVERRIDE) {
            return Result(source, rawHex)
        }
        return null
    }
}
