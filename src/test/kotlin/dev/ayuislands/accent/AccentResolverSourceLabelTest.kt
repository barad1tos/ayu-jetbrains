package dev.ayuislands.accent

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Locks the contract: a stable, human-readable label per [AccentResolver.Source]
 * value. Consumed by the chip tooltip and (in a follow-up) the AccentPanel
 * "currently active" comment.
 *
 * Pattern L from `RECURRING_PITFALLS.md` - `enum-size regression lock`. If a future
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
    fun `sourceLabel for MATERIAL_THEME returns Material Theme`() {
        assertEquals(
            "Material Theme",
            AccentResolver.sourceLabel(AccentResolver.Source.MATERIAL_THEME),
        )
    }

    @Test
    fun `sourceLabel for IDE_ACCENT returns IDE accent`() {
        assertEquals(
            "IDE accent",
            AccentResolver.sourceLabel(AccentResolver.Source.IDE_ACCENT),
        )
    }

    @Test
    fun `sourceLabel for EXTERNAL_ACCENT returns External accent`() {
        assertEquals(
            "External accent",
            AccentResolver.sourceLabel(AccentResolver.Source.EXTERNAL_ACCENT),
        )
    }

    @Test
    fun `Source enum size lock - six values exactly`() {
        // Pattern L regression lock. If you add a new Source value (e.g.
        // REMOTE for remote-environment awareness), this test fails on
        // purpose so you are forced to extend [AccentResolver.sourceLabel]
        // with the new case in the same commit. Without the lock, a missing
        // branch would land at runtime as "Global" via a future `else ->`
        // fallback, silently mislabelling every remote-resolved accent.
        assertEquals(
            6,
            AccentResolver.Source.entries.size,
            "AccentResolver.Source enum grew - extend AccentResolver.sourceLabel in the same change",
        )
    }
}
