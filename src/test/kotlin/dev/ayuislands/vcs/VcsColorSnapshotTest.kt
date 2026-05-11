package dev.ayuislands.vcs

import dev.ayuislands.settings.AyuIslandsState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * Tests for [VcsColorSnapshot] and the [VcsColorContext] scoped-override channel.
 *
 * The snapshot + context pair lets the Settings panel feed *pending* values into
 * the applier during one apply pass without mutating the persisted
 * [AyuIslandsState] first — same "state untouched on throw" invariant the chrome
 * tinting context defends in Phase 40.
 */
class VcsColorSnapshotTest {
    @Test
    fun `snapshot intensityFor returns zero when disabled`() {
        val snapshot =
            VcsColorSnapshot(
                enabled = false,
                preset = VcsColorPreset.VIBRANT,
                perCategoryIntensities = emptyMap(),
            )
        for (category in VcsColorCategory.entries) {
            assertEquals(0, snapshot.intensityFor(category).percent)
        }
    }

    @Test
    fun `snapshot intensityFor uses preset table for non-Custom`() {
        val snapshot =
            VcsColorSnapshot(
                enabled = true,
                preset = VcsColorPreset.BALANCED,
                perCategoryIntensities = mapOf(VcsColorCategory.DIFF_VIEWER to 99),
            )
        // Balanced preset declares 50% — per-category map is ignored.
        assertEquals(50, snapshot.intensityFor(VcsColorCategory.DIFF_VIEWER).percent)
    }

    @Test
    fun `snapshot intensityFor uses per-category map in Custom mode`() {
        val snapshot =
            VcsColorSnapshot(
                enabled = true,
                preset = VcsColorPreset.CUSTOM,
                perCategoryIntensities = mapOf(VcsColorCategory.DIFF_VIEWER to 75),
            )
        assertEquals(75, snapshot.intensityFor(VcsColorCategory.DIFF_VIEWER).percent)
    }

    @Test
    fun `snapshot intensityFor falls back to preset for missing category in Custom`() {
        val snapshot =
            VcsColorSnapshot(
                enabled = true,
                preset = VcsColorPreset.CUSTOM,
                // Map missing BLAME_GUTTER — defensive fallback should kick in.
                perCategoryIntensities = mapOf(VcsColorCategory.DIFF_VIEWER to 75),
            )
        // CUSTOM.intensityFor falls back to BALANCED's value (50) per design.
        assertEquals(50, snapshot.intensityFor(VcsColorCategory.BLAME_GUTTER).percent)
    }

    @Test
    fun `fromState mirrors current state values`() {
        val state = AyuIslandsState()
        state.vcsColorEnabled = true
        state.vcsColorPreset = VcsColorPreset.CUSTOM.name
        state.vcsDiffIntensity = 60

        val snapshot = VcsColorSnapshot.fromState(state)
        assertEquals(true, snapshot.enabled)
        assertEquals(VcsColorPreset.CUSTOM, snapshot.preset)
        assertEquals(60, snapshot.perCategoryIntensities[VcsColorCategory.DIFF_VIEWER])
    }

    @Test
    fun `withSnapshot installs snapshot for the duration of the block`() {
        val state = AyuIslandsState()
        // State says disabled; pending snapshot says enabled at Vibrant.
        val pending =
            VcsColorSnapshot(
                enabled = true,
                preset = VcsColorPreset.VIBRANT,
                perCategoryIntensities = emptyMap(),
            )

        VcsColorContext.withSnapshot(pending) {
            assertEquals(true, VcsColorContext.isEnabled(state))
            assertEquals(100, VcsColorContext.currentIntensity(VcsColorCategory.DIFF_VIEWER, state).percent)
        }

        // After the block: ThreadLocal cleared, fallback state visible.
        assertEquals(false, VcsColorContext.isEnabled(state))
    }

    @Test
    fun `withSnapshot rolls back even when block throws`() {
        val state = AyuIslandsState()
        val pending =
            VcsColorSnapshot(
                enabled = true,
                preset = VcsColorPreset.VIBRANT,
                perCategoryIntensities = emptyMap(),
            )

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
                preset = VcsColorPreset.BALANCED,
                perCategoryIntensities = emptyMap(),
            )
        val inner =
            VcsColorSnapshot(
                enabled = true,
                preset = VcsColorPreset.VIBRANT,
                perCategoryIntensities = emptyMap(),
            )

        VcsColorContext.withSnapshot(outer) {
            assertEquals(50, VcsColorContext.currentIntensity(VcsColorCategory.DIFF_VIEWER, state).percent)
            VcsColorContext.withSnapshot(inner) {
                assertEquals(100, VcsColorContext.currentIntensity(VcsColorCategory.DIFF_VIEWER, state).percent)
            }
            // After inner block — outer must be restored.
            assertEquals(
                50,
                VcsColorContext.currentIntensity(VcsColorCategory.DIFF_VIEWER, state).percent,
                "outer snapshot must be restored after inner exit",
            )
            // Sanity: snapshots are different objects, no aliasing.
            assertNotEquals(outer.preset, inner.preset)
        }
    }
}
