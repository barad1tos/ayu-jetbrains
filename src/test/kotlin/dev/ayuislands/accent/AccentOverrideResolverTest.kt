package dev.ayuislands.accent

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class AccentOverrideResolverTest {
    @Test
    fun `project override wins before language rules`() {
        var detectorConsulted = false
        val result =
            AccentOverrideResolver.resolve(
                request(
                    projectKey = PROJECT_KEY,
                    snapshot =
                        AccentOverrideSnapshot(
                            projectAccents = mapOf(PROJECT_KEY to "#111111"),
                            languageAccents = mapOf("kotlin" to "#222222"),
                        ),
                    detectedLanguage = {
                        detectorConsulted = true
                        "kotlin"
                    },
                ),
            )

        assertEquals(AccentResolver.Source.PROJECT_OVERRIDE, result?.source)
        assertEquals("#111111", result?.hex)
        assertFalse(detectorConsulted)
    }

    @Test
    fun `forced language wins without consulting detector`() {
        var detectorConsulted = false
        val result =
            AccentOverrideResolver.resolve(
                request(
                    projectKey = PROJECT_KEY,
                    snapshot =
                        AccentOverrideSnapshot(
                            languageAccents = mapOf("typescript" to "#3178C6", "javascript" to "#F7DF1E"),
                            forcedProjectLanguages = mapOf(PROJECT_KEY to "typescript"),
                        ),
                    detectedLanguage = {
                        detectorConsulted = true
                        "javascript"
                    },
                ),
            )

        assertEquals(AccentResolver.Source.FORCED_LANGUAGE_OVERRIDE, result?.source)
        assertEquals("#3178C6", result?.hex)
        assertFalse(detectorConsulted)
    }

    @Test
    fun `detected language uses injected verdict`() {
        val result =
            AccentOverrideResolver.resolve(
                request(
                    projectKey = PROJECT_KEY,
                    snapshot = AccentOverrideSnapshot(languageAccents = mapOf("kotlin" to "#A6E22E")),
                    detectedLanguage = { "kotlin" },
                ),
            )

        assertEquals(AccentResolver.Source.LANGUAGE_OVERRIDE, result?.source)
        assertEquals("#A6E22E", result?.hex)
    }

    @Test
    fun `language fallback applies when forced language is unmapped`() {
        val result =
            AccentOverrideResolver.resolve(
                request(
                    projectKey = PROJECT_KEY,
                    snapshot =
                        AccentOverrideSnapshot(
                            forcedProjectLanguages = mapOf(PROJECT_KEY to "rust"),
                            languageFallbackAccent = "#73D0FF",
                        ),
                    detectedLanguage = { error("detector must not be consulted") },
                ),
            )

        assertEquals(AccentResolver.Source.LANGUAGE_FALLBACK_OVERRIDE, result?.source)
        assertEquals("#73D0FF", result?.hex)
    }

    @Test
    fun `project fallback applies only on no-winner verdict`() {
        val coldResult =
            AccentOverrideResolver.resolve(
                request(
                    projectKey = PROJECT_KEY,
                    snapshot = AccentOverrideSnapshot(projectFallbackAccents = mapOf(PROJECT_KEY to "#5CCFE6")),
                    verdict = { ProjectLanguageVerdict.Cold },
                ),
            )
        val noWinnerResult =
            AccentOverrideResolver.resolve(
                request(
                    projectKey = PROJECT_KEY,
                    snapshot = AccentOverrideSnapshot(projectFallbackAccents = mapOf(PROJECT_KEY to "#5CCFE6")),
                    verdict = { ProjectLanguageVerdict.NoWinner(mapOf("kotlin" to 500L, "java" to 500L)) },
                ),
            )

        assertNull(coldResult)
        assertEquals(AccentResolver.Source.PROJECT_FALLBACK, noWinnerResult?.source)
        assertEquals("#5CCFE6", noWinnerResult?.hex)
    }

    @Test
    fun `disabled override gate returns no result without consulting detector`() {
        var detectorConsulted = false
        val result =
            AccentOverrideResolver.resolve(
                request(
                    projectKey = PROJECT_KEY,
                    snapshot =
                        AccentOverrideSnapshot(
                            projectAccents = mapOf(PROJECT_KEY to "#111111"),
                            languageAccents = mapOf("kotlin" to "#222222"),
                            projectFallbackAccents = mapOf(PROJECT_KEY to "#333333"),
                        ),
                    detectedLanguage = {
                        detectorConsulted = true
                        "kotlin"
                    },
                    verdict = {
                        detectorConsulted = true
                        ProjectLanguageVerdict.NoWinner(emptyMap())
                    },
                ).copy(overridesEnabled = false),
            )

        assertNull(result)
        assertFalse(detectorConsulted)
    }

    @Test
    fun `source reports global when no override resolves`() {
        val source =
            AccentOverrideResolver.source(
                request(
                    projectKey = PROJECT_KEY,
                    snapshot = AccentOverrideSnapshot(languageAccents = mapOf("kotlin" to "#A6E22E")),
                    detectedLanguage = { "typescript" },
                ),
            )

        assertEquals(AccentResolver.Source.GLOBAL, source)
    }

    @Test
    fun `source reports project fallback for no-winner verdict`() {
        val source =
            AccentOverrideResolver.source(
                request(
                    projectKey = PROJECT_KEY,
                    snapshot = AccentOverrideSnapshot(projectFallbackAccents = mapOf(PROJECT_KEY to "#5CCFE6")),
                    verdict = { ProjectLanguageVerdict.NoWinner(mapOf("kotlin" to 500L, "java" to 500L)) },
                ),
            )

        assertEquals(AccentResolver.Source.PROJECT_FALLBACK, source)
    }

    @Test
    fun `hex validation matches ayu and external traversal parity`() {
        val rawProjectResult =
            AccentOverrideResolver.resolve(
                request(
                    projectKey = PROJECT_KEY,
                    validateHex = false,
                    snapshot = AccentOverrideSnapshot(projectAccents = mapOf(PROJECT_KEY to "not-a-hex")),
                ),
            )
        val validatedProjectResult =
            AccentOverrideResolver.resolve(
                request(
                    projectKey = PROJECT_KEY,
                    validateHex = true,
                    snapshot = AccentOverrideSnapshot(projectAccents = mapOf(PROJECT_KEY to "not-a-hex")),
                ),
            )
        val invalidLanguageFallbackResult =
            AccentOverrideResolver.resolve(
                request(
                    projectKey = PROJECT_KEY,
                    validateHex = false,
                    snapshot =
                        AccentOverrideSnapshot(
                            forcedProjectLanguages = mapOf(PROJECT_KEY to "kotlin"),
                            languageFallbackAccent = "not-a-hex",
                        ),
                ),
            )

        assertEquals("not-a-hex", rawProjectResult?.hex)
        assertNull(validatedProjectResult)
        assertNull(invalidLanguageFallbackResult)
    }

    private fun request(
        projectKey: String?,
        snapshot: AccentOverrideSnapshot,
        validateHex: Boolean = false,
        detectedLanguage: () -> String? = { null },
        verdict: (warmCache: Boolean) -> ProjectLanguageVerdict = { ProjectLanguageVerdict.Cold },
    ): AccentOverrideResolver.Request =
        AccentOverrideResolver.Request(
            projectKey = projectKey,
            snapshot = snapshot,
            overridesEnabled = true,
            validateHex = validateHex,
            detectedLanguage = detectedLanguage,
            verdict = verdict,
        )

    private companion object {
        const val PROJECT_KEY = "/tmp/ayu-project"
    }
}
