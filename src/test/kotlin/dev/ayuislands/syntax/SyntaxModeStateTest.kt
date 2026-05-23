package dev.ayuislands.syntax

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Round-trip tests for [SyntaxModeBaseState] — the BaseState subclass that
 * [SyntaxModeState] persists via SimplePersistentStateComponent. No platform
 * fixture: BaseState's `string()` / `stringSet()` delegate properties are
 * platform-agnostic Java field-backed bags.
 *
 * The persistence integration (loadState ↔ XML round-trip) is covered by a
 * BasePlatformTestCase in Plan 49-04. Here we only need to lock the data
 * shape: default values per D-02, fromName fallback per Q5/R-5, string-set
 * mutation behavior.
 */
class SyntaxModeStateTest {
    @Test
    fun `default mood is null (deserializes to MAXIMUM via SyntaxMood fromName)`() {
        val state = SyntaxModeBaseState()
        // Null mood field — caller wraps with SyntaxMood.fromName(state.mood)
        // which returns MAXIMUM per D-02 first-launch contract.
        assertSame(SyntaxMood.MAXIMUM, SyntaxMood.fromName(state.mood))
    }

    @Test
    fun `default axes is empty set per D-02`() {
        val state = SyntaxModeBaseState()
        assertTrue(state.axes.isEmpty(), "default axes must be empty per D-02 first-launch contract")
    }

    @Test
    fun `setting mood persists enum name string`() {
        val state = SyntaxModeBaseState()
        state.mood = SyntaxMood.RICH.name
        assertEquals("RICH", state.mood)
        assertSame(SyntaxMood.RICH, SyntaxMood.fromName(state.mood))
    }

    @Test
    fun `adding axes persists as Set of enum names`() {
        val state = SyntaxModeBaseState()
        state.axes.addAll(setOf("ITALIC_DECLARATIONS", "DIMMED_COMMENTS"))
        assertEquals(2, state.axes.size)
        assertTrue("ITALIC_DECLARATIONS" in state.axes)
        assertTrue("DIMMED_COMMENTS" in state.axes)
    }

    @Test
    fun `clearing axes empties the Set`() {
        val state = SyntaxModeBaseState()
        state.axes.addAll(setOf("ITALIC_DECLARATIONS"))
        state.axes.clear()
        assertTrue(state.axes.isEmpty(), "after clear axes must be empty")
    }

    @Test
    fun `invalid stored mood name falls back to MAXIMUM via fromName (T-49-04 mitigation)`() {
        val state = SyntaxModeBaseState()
        state.mood = "BOGUS_TIER_FROM_TAMPERED_XML"
        assertSame(SyntaxMood.MAXIMUM, SyntaxMood.fromName(state.mood))
    }

    @Test
    fun `axes can be replaced by clear plus addAll without losing field reference`() {
        val state = SyntaxModeBaseState()
        state.axes.addAll(setOf("ITALIC_DECLARATIONS"))
        state.axes.clear()
        state.axes.addAll(setOf("BOLD_TYPE_REFERENCES"))
        assertEquals(setOf("BOLD_TYPE_REFERENCES"), state.axes.toSet())
    }
}
