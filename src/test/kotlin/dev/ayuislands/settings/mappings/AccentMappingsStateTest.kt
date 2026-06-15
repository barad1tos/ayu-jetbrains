package dev.ayuislands.settings.mappings

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Smoke tests for the state DTO — the real serialization concerns live in IntelliJ's
 * BaseState, but this guards against accidental field renames and ensures a fresh
 * instance starts empty.
 */
class AccentMappingsStateTest {
    @Test
    fun `fresh state has empty maps`() {
        val state = AccentMappingsState()

        assertTrue(state.projectAccents.isEmpty())
        assertTrue(state.languageAccents.isEmpty())
        assertTrue(state.projectDisplayNames.isEmpty())
        assertTrue(state.projectFallbackAccents.isEmpty())
        assertTrue(state.forcedProjectLanguages.isEmpty())
    }

    @Test
    fun `maps are mutable for serialization round-trip`() {
        val state = AccentMappingsState()
        state.projectAccents["/tmp/a"] = "#111111"
        state.projectDisplayNames["/tmp/a"] = "Alpha"
        state.languageAccents["kotlin"] = "#222222"
        state.projectFallbackAccents["/tmp/a"] = "#333333"
        state.forcedProjectLanguages["/tmp/a"] = "typescript"

        assertEquals("#111111", state.projectAccents["/tmp/a"])
        assertEquals("Alpha", state.projectDisplayNames["/tmp/a"])
        assertEquals("#222222", state.languageAccents["kotlin"])
        assertEquals("#333333", state.projectFallbackAccents["/tmp/a"])
        assertEquals("typescript", state.forcedProjectLanguages["/tmp/a"])
    }
}
