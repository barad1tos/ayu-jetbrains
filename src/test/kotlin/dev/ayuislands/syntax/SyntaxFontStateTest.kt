package dev.ayuislands.syntax

import org.junit.jupiter.api.Test
import java.awt.Font
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class SyntaxFontStateTest {
    @Test
    fun `preparation freezes nested choices without replacing the current semantic styles`() {
        val initialStyles = linkedMapOf("KEYWORD" to Font.BOLD, "FUTURE" to 37)
        val initial = linkedMapOf("Kotlin" to initialStyles)
        val state = SyntaxFontState()
        state.replace(initial)
        val accepted = state.current
        val candidateStyles = linkedMapOf("OPERATOR" to Font.ITALIC)
        val candidate = linkedMapOf("Swift" to candidateStyles)

        val write = state.prepare(candidate)
        initialStyles.clear()
        initial.clear()
        candidateStyles.clear()
        candidate.clear()

        assertSame(accepted, state.current)
        assertSame(accepted, write.before)
        assertEquals(
            listOf("KEYWORD", "FUTURE"),
            write.before.styles
                .getValue("Kotlin")
                .keys
                .toList(),
        )
        assertEquals(mapOf("OPERATOR" to Font.ITALIC), write.planned.styles.getValue("Swift"))
        assertEquals(SyntaxFontPhase.PREPARED, write.phase)
    }

    @Test
    fun `apply and rollback retain exact snapshot identity and duplicate rollback does not replay`() {
        val state = SyntaxFontState()
        state.replace(fonts(Font.BOLD))
        val original = state.current
        val write = state.prepare(fonts(Font.ITALIC))

        assertTrue(write.apply())
        assertSame(write.planned, state.current)
        assertEquals(SyntaxFontPhase.APPLIED, write.phase)
        assertFailsWith<IllegalStateException> { write.apply() }
        assertTrue(write.restore())
        assertSame(original, state.current)
        assertEquals(SyntaxFontPhase.RESTORED, write.phase)

        state.replace(fonts(Font.PLAIN))
        val later = state.current
        assertTrue(write.restore())
        assertSame(later, state.current)
    }

    @Test
    fun `an equal later observation cannot authorize a stale prepared write`() {
        val state = SyntaxFontState()
        state.replace(fonts(Font.BOLD))
        val write = state.prepare(fonts(Font.ITALIC))
        state.replace(fonts(Font.BOLD))
        val foreign = state.current

        assertFalse(write.apply())
        assertEquals(SyntaxFontPhase.FOREIGN, write.phase)
        assertSame(foreign, state.current)
        assertFalse(write.restore())
        assertSame(foreign, state.current)
        assertFailsWith<IllegalStateException> { write.accept() }
    }

    @Test
    fun `rollback preserves a later writer even when its styles equal the candidate`() {
        val state = SyntaxFontState()
        val write = state.prepare(fonts(Font.BOLD))
        assertTrue(write.apply())
        state.replace(fonts(Font.BOLD))
        val foreign = state.current

        assertFalse(write.restore())
        assertEquals(SyntaxFontPhase.FOREIGN, write.phase)
        assertSame(foreign, state.current)
        assertFalse(write.restore())
        assertSame(foreign, state.current)
    }

    @Test
    fun `accept retains styles and a later aborted change restores that accepted baseline`() {
        val state = SyntaxFontState()
        val first = state.prepare(fonts(Font.BOLD))
        assertTrue(first.apply())
        first.accept()
        first.accept()
        val accepted = state.current

        val second = state.prepare(fonts(Font.ITALIC))
        assertTrue(second.apply())
        assertTrue(second.restore())
        assertSame(accepted, state.current)
        assertEquals(SyntaxFontPhase.ACCEPTED, first.phase)
        assertFailsWith<IllegalStateException> { first.restore() }
        assertSame(accepted, state.current)
    }

    @Test
    fun `unused preparation is discarded without replacing the latest styles`() {
        val state = SyntaxFontState()
        val write = state.prepare(fonts(Font.BOLD))
        state.replace(fonts(Font.ITALIC))
        val current = state.current

        assertTrue(write.restore())
        assertSame(current, state.current)
        assertEquals(SyntaxFontPhase.RESTORED, write.phase)
        assertFailsWith<IllegalStateException> { write.apply() }
        assertFailsWith<IllegalStateException> { write.accept() }
    }

    private fun fonts(style: Int): Map<String, Map<String, Int>> = mapOf("Kotlin" to mapOf("KEYWORD" to style))
}
