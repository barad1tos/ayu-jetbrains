package dev.ayuislands.accent

import dev.ayuislands.settings.mappings.AccentMappingsState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Behavioral contract of [PersistedAccentMappingsView]: given a persisted
 * [AccentMappingsState], the view answers exactly what the resolution engine
 * asks per rung. Locks the normalization split — forced-language ids and the
 * language fallback are trimmed/blank-filtered (live-resolver semantics), while
 * every hex value passes through verbatim so [AccentHexPolicy] stays the only
 * place that judges validity.
 */
class AccentMappingsViewTest {
    private fun viewOf(configure: AccentMappingsState.() -> Unit = {}): PersistedAccentMappingsView {
        val state = AccentMappingsState().apply(configure)
        return PersistedAccentMappingsView { state }
    }

    @Test
    fun `projectAccent returns stored hex verbatim and null when absent`() {
        val view =
            viewOf {
                projectAccents["/tmp/alpha"] = "not-a-hex"
            }

        assertEquals("not-a-hex", view.projectAccent("/tmp/alpha"))
        assertNull(view.projectAccent("/tmp/other"))
    }

    @Test
    fun `forcedLanguageId trims stored id and keeps case`() {
        val view =
            viewOf {
                forcedProjectLanguages["/tmp/alpha"] = "  TypeScript  "
            }

        assertEquals("TypeScript", view.forcedLanguageId("/tmp/alpha"))
        assertNull(view.forcedLanguageId("/tmp/other"))
    }

    @Test
    fun `blank forced entry yields null id but still reports entry presence`() {
        // The blank-entry split is load-bearing: the engine must suppress the
        // language-detection rung for ANY forced entry, valid id or not.
        val view =
            viewOf {
                forcedProjectLanguages["/tmp/alpha"] = "   "
            }

        assertNull(view.forcedLanguageId("/tmp/alpha"))
        assertTrue(view.hasForcedLanguageEntry("/tmp/alpha"))
        assertFalse(view.hasForcedLanguageEntry("/tmp/other"))
    }

    @Test
    fun `languageAccent is an exact lookup without case folding`() {
        val view =
            viewOf {
                languageAccents["kotlin"] = "#ABCDEF"
            }

        assertEquals("#ABCDEF", view.languageAccent("kotlin"))
        assertNull(view.languageAccent("Kotlin"))
    }

    @Test
    fun `hasLanguageAccents reflects mapping presence`() {
        assertFalse(viewOf().hasLanguageAccents)
        assertTrue(viewOf { languageAccents["go"] = "#5CCFE6" }.hasLanguageAccents)
    }

    @Test
    fun `languageFallbackAccent trims stored value and filters blank to null`() {
        assertEquals(
            "#73D0FF",
            viewOf { languageFallbackAccent = "  #73D0FF  " }.languageFallbackAccent,
        )
        assertNull(viewOf { languageFallbackAccent = "   " }.languageFallbackAccent)
        assertNull(viewOf().languageFallbackAccent)
    }

    @Test
    fun `projectFallbackAccent returns stored hex verbatim with matching candidate flag`() {
        val view =
            viewOf {
                projectFallbackAccents["/tmp/alpha"] = "bad-hex"
            }

        assertEquals("bad-hex", view.projectFallbackAccent("/tmp/alpha"))
        assertTrue(view.hasProjectFallbackCandidate("/tmp/alpha"))
        assertNull(view.projectFallbackAccent("/tmp/other"))
        assertFalse(view.hasProjectFallbackCandidate("/tmp/other"))
    }

    @Test
    fun `state supplier is consulted lazily so gated resolutions stay zero-cost`() {
        var reads = 0
        val state = AccentMappingsState().apply { projectAccents["/tmp/alpha"] = "#112233" }
        val view =
            PersistedAccentMappingsView {
                reads += 1
                state
            }

        assertEquals(0, reads, "Construction must not read the persisted state")
        assertEquals("#112233", view.projectAccent("/tmp/alpha"))
        view.hasProjectFallbackCandidate("/tmp/alpha")
        assertEquals(1, reads, "State must be materialized once per view instance")
    }
}
