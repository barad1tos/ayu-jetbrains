package dev.ayuislands.vcs

import dev.ayuislands.settings.AyuIslandsState
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Tests for [VcsColorSnapshot] and the [VcsColorContext] scoped-override channel.
 *
 * The snapshot carries the resolved per-category intensity map directly.
 * Per-section preset choices live on the panel / state side; by the time a
 * snapshot exists, preset/Custom branching is already collapsed into
 * concrete intensity numbers.
 */
class VcsColorSnapshotTest {
    @Test
    fun `snapshot intensityFor returns zero when disabled`() {
        val snapshot =
            VcsColorSnapshot(
                enabled = false,
                perCategoryIntensities = mapOf(VcsColorCategory.DIFF_VIEWER to 99),
            )
        for (category in VcsColorCategory.entries) {
            assertEquals(0, snapshot.intensityFor(category).percent)
        }
    }

    @Test
    fun `snapshot intensityFor reads materialised per-category map`() {
        val snapshot =
            VcsColorSnapshot(
                enabled = true,
                perCategoryIntensities =
                    mapOf(
                        VcsColorCategory.DIFF_VIEWER to 67,
                        VcsColorCategory.BLAME_GUTTER to 33,
                    ),
            )
        assertEquals(67, snapshot.intensityFor(VcsColorCategory.DIFF_VIEWER).percent)
        assertEquals(33, snapshot.intensityFor(VcsColorCategory.BLAME_GUTTER).percent)
    }

    @Test
    fun `snapshot intensityFor falls back to Ambient slider for missing category`() {
        val snapshot =
            VcsColorSnapshot(
                enabled = true,
                perCategoryIntensities = mapOf(VcsColorCategory.DIFF_VIEWER to 67),
            )
        // Missing entry — defensive fallback to Ambient slider.
        assertEquals(
            VcsColorPreset.AMBIENT_SLIDER,
            snapshot.intensityFor(VcsColorCategory.BLAME_GUTTER).percent,
        )
    }

    @Test
    fun `fromState materialises per-section preset choices into per-category intensities`() {
        val state = AyuIslandsState()
        state.vcsColorEnabled = true
        state.vcsDiffPreset = VcsColorPreset.NEON.name
        state.vcsMergePreset = VcsColorPreset.WHISPER.name
        state.vcsBlamePreset = VcsColorPreset.CYBERPUNK.name

        val snapshot = VcsColorSnapshot.fromState(state)
        assertEquals(true, snapshot.enabled)
        assertEquals(
            VcsColorPreset.NEON_SLIDER,
            snapshot.intensityFor(VcsColorCategory.DIFF_VIEWER).percent,
        )
        assertEquals(
            VcsColorPreset.WHISPER_SLIDER,
            snapshot.intensityFor(VcsColorCategory.CONFLICT_MARKERS).percent,
        )
        assertEquals(
            VcsColorPreset.CYBERPUNK_SLIDER,
            snapshot.intensityFor(VcsColorCategory.BLAME_GUTTER).percent,
        )
    }

    @Test
    fun `withSnapshot installs snapshot for the duration of the block`() {
        val state = AyuIslandsState()
        val pending =
            VcsColorSnapshot(
                enabled = true,
                perCategoryIntensities =
                    mapOf(VcsColorCategory.DIFF_VIEWER to VcsColorPreset.CYBERPUNK_SLIDER),
            )

        VcsColorContext.withSnapshot(pending) {
            assertEquals(true, VcsColorContext.isEnabled(state))
            assertEquals(
                VcsColorPreset.CYBERPUNK_SLIDER,
                VcsColorContext.currentIntensity(VcsColorCategory.DIFF_VIEWER, state).percent,
            )
        }

        assertEquals(false, VcsColorContext.isEnabled(state))
    }

    @Test
    fun `withSnapshot rolls back even when block throws`() {
        val state = AyuIslandsState()
        val pending = VcsColorSnapshot(enabled = true, perCategoryIntensities = emptyMap())

        runCatching {
            VcsColorContext.withSnapshot(pending) {
                error("boom")
            }
        }

        // ThreadLocal must be cleared even after the throw — no leak across thread reuse.
        assertEquals(false, VcsColorContext.isEnabled(state))
    }

    @Test
    fun `withSnapshot null is a no-op pass-through`() {
        val state = AyuIslandsState()
        var blockRan = false
        VcsColorContext.withSnapshot(null) {
            blockRan = true
            // No snapshot installed: read falls through to state (disabled by default).
            assertEquals(false, VcsColorContext.isEnabled(state))
        }
        assertEquals(true, blockRan)
    }

    @Test
    fun `withSnapshot nests and restores previous snapshot on exit`() {
        val state = AyuIslandsState()
        val outer =
            VcsColorSnapshot(
                enabled = true,
                perCategoryIntensities = mapOf(VcsColorCategory.DIFF_VIEWER to VcsColorPreset.AMBIENT_SLIDER),
            )
        val inner =
            VcsColorSnapshot(
                enabled = true,
                perCategoryIntensities = mapOf(VcsColorCategory.DIFF_VIEWER to VcsColorPreset.CYBERPUNK_SLIDER),
            )

        VcsColorContext.withSnapshot(outer) {
            assertEquals(
                VcsColorPreset.AMBIENT_SLIDER,
                VcsColorContext.currentIntensity(VcsColorCategory.DIFF_VIEWER, state).percent,
            )
            VcsColorContext.withSnapshot(inner) {
                assertEquals(
                    VcsColorPreset.CYBERPUNK_SLIDER,
                    VcsColorContext.currentIntensity(VcsColorCategory.DIFF_VIEWER, state).percent,
                )
            }
            assertEquals(
                VcsColorPreset.AMBIENT_SLIDER,
                VcsColorContext.currentIntensity(VcsColorCategory.DIFF_VIEWER, state).percent,
                "outer snapshot must be restored after inner exit",
            )
        }
    }
}
