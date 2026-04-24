package dev.ayuislands.accent

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/**
 * Tests for [ClassFqn] — the typed wrapper that kills the
 * `refreshByClassNameInsideAncestorClass(target, ancestor, color)` positional
 * footgun introduced in Phase 40 (two adjacent `String` params of identical
 * type, swapping them compiled + silently over-tinted the wrong peers).
 */
class ClassFqnTest {
    @Test
    fun `require returns a ClassFqn when input matches FQN pattern`() {
        val fqn = ClassFqn.require("com.intellij.toolWindow.Stripe")
        assertEquals("com.intellij.toolWindow.Stripe", fqn.value)
    }

    @Test
    fun `of returns null for blank input`() {
        assertNull(ClassFqn.of(""))
        assertNull(ClassFqn.of("   "))
    }

    @Test
    fun `of returns null for input with spaces`() {
        assertNull(ClassFqn.of("com.intellij tool Window"))
    }

    @Test
    fun `require throws IllegalArgumentException for blank input`() {
        assertFailsWith<IllegalArgumentException> { ClassFqn.require("") }
        assertFailsWith<IllegalArgumentException> { ClassFqn.require("   ") }
    }

    @Test
    fun `of accepts fully-qualified names with dots and digits`() {
        val fqn = ClassFqn.of("com.acme.widget.Tool2Panel")
        assertEquals("com.acme.widget.Tool2Panel", fqn?.value)
    }
}
