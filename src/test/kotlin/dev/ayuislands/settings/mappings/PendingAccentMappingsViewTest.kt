package dev.ayuislands.settings.mappings

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Behavioral contract of [PendingAccentMappingsView]: given the in-memory
 * Settings model snapshots, the view answers exactly what the resolution
 * engine asks per rung. Everything passes through verbatim — normalization is
 * [OverridesGroupBuilder]'s job at model-entry time, so the view re-applying it
 * would mask a broken entry path instead of surfacing it.
 */
class PendingAccentMappingsViewTest {
    private fun viewOf(
        projectAccents: Map<String, String> = emptyMap(),
        languageAccents: Map<String, String> = emptyMap(),
        forcedLanguages: Map<String, String> = emptyMap(),
        projectFallbackAccents: Map<String, String> = emptyMap(),
        languageFallbackAccent: String? = null,
    ): PendingAccentMappingsView =
        PendingAccentMappingsView(
            projectAccents = projectAccents,
            languageAccents = languageAccents,
            forcedLanguages = forcedLanguages,
            projectFallbackAccents = projectFallbackAccents,
            languageFallbackAccent = languageFallbackAccent,
        )

    @Test
    fun `projectAccent answers from the pending project snapshot`() {
        val view = viewOf(projectAccents = mapOf("/tmp/alpha" to "#112233"))

        assertEquals("#112233", view.projectAccent("/tmp/alpha"))
        assertNull(view.projectAccent("/tmp/other"))
    }

    @Test
    fun `forcedLanguageId returns pending entry verbatim with matching presence flag`() {
        val view = viewOf(forcedLanguages = mapOf("/tmp/alpha" to "typescript"))

        assertEquals("typescript", view.forcedLanguageId("/tmp/alpha"))
        assertTrue(view.hasForcedLanguageEntry("/tmp/alpha"))
        assertNull(view.forcedLanguageId("/tmp/other"))
        assertFalse(view.hasForcedLanguageEntry("/tmp/other"))
    }

    @Test
    fun `languageAccent is an exact lookup without case folding`() {
        val view = viewOf(languageAccents = mapOf("kotlin" to "#CAFE00"))

        assertEquals("#CAFE00", view.languageAccent("kotlin"))
        assertNull(view.languageAccent("Kotlin"))
    }

    @Test
    fun `hasLanguageAccents reflects pending language rows`() {
        assertFalse(viewOf().hasLanguageAccents)
        assertTrue(viewOf(languageAccents = mapOf("go" to "#5CCFE6")).hasLanguageAccents)
    }

    @Test
    fun `languageFallbackAccent passes through the pending value`() {
        assertEquals("#73D0FF", viewOf(languageFallbackAccent = "#73D0FF").languageFallbackAccent)
        assertNull(viewOf().languageFallbackAccent)
    }

    @Test
    fun `projectFallbackAccent answers with matching candidate flag`() {
        val view = viewOf(projectFallbackAccents = mapOf("/tmp/alpha" to "#FFB454"))

        assertEquals("#FFB454", view.projectFallbackAccent("/tmp/alpha"))
        assertTrue(view.hasProjectFallbackCandidate("/tmp/alpha"))
        assertNull(view.projectFallbackAccent("/tmp/other"))
        assertFalse(view.hasProjectFallbackCandidate("/tmp/other"))
    }
}
