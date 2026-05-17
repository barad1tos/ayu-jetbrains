package dev.ayuislands.accent

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Locks the D-18 contract: a stable, human-readable label per [AccentResolver.Source]
 * value. Consumed by the Phase 48 chip tooltip and (in a follow-up) the AccentPanel
 * "currently active" comment.
 *
 * Pattern L from `RECURRING_PITFALLS.md` — `enum-size regression lock`. If a future
 * commit grows [AccentResolver.Source] (e.g. `Source.REMOTE`), the
 * [size assertion][Source enum size lock] test fails and forces the implementer to
 * extend [AccentResolver.sourceLabel] in the same change, blocking the silent
 * `else -> "Global"`-style fallback drift that would otherwise label every new
 * resolution layer as "Global" in the tooltip.
 */
class AccentResolverSourceLabelTest {
    @Test
    fun `sourceLabel for GLOBAL returns Global`() {
        assertEquals("Global", AccentResolver.sourceLabel(AccentResolver.Source.GLOBAL))
    }

    @Test
    fun `sourceLabel for PROJECT_OVERRIDE returns Project override`() {
        assertEquals(
            "Project override",
            AccentResolver.sourceLabel(AccentResolver.Source.PROJECT_OVERRIDE),
        )
    }

    @Test
    fun `sourceLabel for LANGUAGE_OVERRIDE returns Language override`() {
        assertEquals(
            "Language override",
            AccentResolver.sourceLabel(AccentResolver.Source.LANGUAGE_OVERRIDE),
        )
    }

    @Test
    fun `Source enum size lock — three values exactly`() {
        // Pattern L regression lock. If you add a new Source value (e.g. REMOTE for
        // Phase 42 remote-environment awareness), this test fails on purpose so you
        // are forced to extend [AccentResolver.sourceLabel] with the new case in the
        // same commit. Without the lock, a missing branch would land at runtime as
        // "Global" via a future `else ->` fallback, silently mislabelling every
        // remote-resolved accent.
        assertEquals(
            3,
            AccentResolver.Source.values().size,
            "AccentResolver.Source enum grew — extend AccentResolver.sourceLabel in the same change",
        )
    }
}
