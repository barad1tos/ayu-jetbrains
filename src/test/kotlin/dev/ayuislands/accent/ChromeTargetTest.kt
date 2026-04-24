package dev.ayuislands.accent

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Locks the [ChromeTarget.ByClassNameInside] private-constructor contract added in
 * Phase 40.4 R-1.
 *
 * Contract:
 *  1. The public factory `invoke(target = ..., ancestor = ...)` returns a valid
 *     instance and the `data class` equality / hashCode semantics are preserved.
 *  2. Two instances with the same target and ancestor compare equal and hash to
 *     the same bucket.
 *  3. Swapping target and ancestor between two instances produces a NON-equal
 *     object — proving the two fields are not interchangeable.
 *
 * A future refactor that drops `data class` (e.g. collapsing to a plain `class`)
 * would silently break every `==` call in the element map and this test catches
 * that regression.
 */
class ChromeTargetTest {
    private val dividerFqn = ClassFqn.require("com.intellij.openapi.ui.OnePixelDivider")
    private val ancestorFqn = ClassFqn.require("com.intellij.toolWindow.InternalDecoratorImpl")

    @Test
    fun `invoke factory builds an instance with the supplied target and ancestor`() {
        val created = ChromeTarget.ByClassNameInside(target = dividerFqn, ancestor = ancestorFqn)

        assertEquals(dividerFqn, created.target)
        assertEquals(ancestorFqn, created.ancestor)
    }

    @Test
    fun `two instances with identical target and ancestor compare equal`() {
        val left = ChromeTarget.ByClassNameInside(target = dividerFqn, ancestor = ancestorFqn)
        val right = ChromeTarget.ByClassNameInside(target = dividerFqn, ancestor = ancestorFqn)

        assertEquals(left, right, "data class equality must hold after factory construction")
        assertEquals(left.hashCode(), right.hashCode(), "hashCode must match for equal instances")
    }

    @Test
    fun `swapping target and ancestor produces a distinct non-equal instance`() {
        val original = ChromeTarget.ByClassNameInside(target = dividerFqn, ancestor = ancestorFqn)
        val swapped = ChromeTarget.ByClassNameInside(target = ancestorFqn, ancestor = dividerFqn)

        assertNotEquals(
            original,
            swapped,
            "swapping target and ancestor must NOT produce an equal object — " +
                "the two fields are semantically distinct (what to find vs where to find it)",
        )
    }

    @Test
    fun `toString reflects both fields so logs distinguish instances`() {
        val target = ChromeTarget.ByClassNameInside(target = dividerFqn, ancestor = ancestorFqn)

        val rendered = target.toString()

        assertTrue(
            rendered.contains("OnePixelDivider"),
            "toString must include the target FQN for log readability",
        )
        assertTrue(
            rendered.contains("InternalDecoratorImpl"),
            "toString must include the ancestor FQN for log readability",
        )
    }
}
