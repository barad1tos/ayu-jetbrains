package dev.ayuislands.accent

import dev.ayuislands.settings.AyuIslandsState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Locks the [ChromeTintContext] / [ChromeTintSnapshot] contract on which the
 * Phase 40.4 "settings panel apply uses pending values without committing
 * state" guarantee depends. Each test isolates one ThreadLocal invariant —
 * pass-through, single-frame override, nesting restoration, and `try/finally`
 * pop on `block` throw.
 *
 * The tests run on the JUnit thread without any project / application
 * fixtures; [ChromeTintContext] is `internal object` so we can call it
 * directly. The companion [ChromeTintSnapshot.isToggleEnabled] also gets
 * coverage for both branches (chrome id → snapshot, non-chrome id → state).
 */
class ChromeTintContextTest {
    @Test
    fun `currentIntensity falls back to state when no snapshot is active`() {
        val state =
            AyuIslandsState().apply {
                chromeTintIntensity = 33
            }

        assertEquals(
            state.effectiveChromeTintIntensity(),
            ChromeTintContext.currentIntensity(state),
            "Without an active snapshot currentIntensity must mirror state's effective intensity",
        )
    }

    @Test
    fun `currentIntensity returns snapshot intensity while withSnapshot is active`() {
        val state = AyuIslandsState().apply { chromeTintIntensity = 10 }
        val snapshot = chromeSnapshot(intensityPercent = 47)

        ChromeTintContext.withSnapshot(snapshot) {
            assertEquals(
                47,
                ChromeTintContext.currentIntensity(state).percent,
                "Inside withSnapshot the snapshot intensity must override state",
            )
        }
    }

    @Test
    fun `withSnapshot null is a pass-through that does not mutate the slot`() {
        // The Phase 40.4 contract for `withSnapshot(null)` is "use existing
        // context, do not clear" — important so a nested chrome apply pipeline
        // running outside Settings still sees state, not a transiently-empty
        // ThreadLocal frame.
        val state = AyuIslandsState().apply { chromeTintIntensity = 21 }
        val snapshot = chromeSnapshot(intensityPercent = 42)

        ChromeTintContext.withSnapshot(snapshot) {
            ChromeTintContext.withSnapshot(null) {
                assertEquals(
                    42,
                    ChromeTintContext.currentIntensity(state).percent,
                    "withSnapshot(null) must not clear an outer snapshot",
                )
            }
            // After the null pass-through frame returns, the outer snapshot
            // must still be visible — proves null pass-through never touched
            // the ThreadLocal slot.
            assertEquals(42, ChromeTintContext.currentIntensity(state).percent)
        }
    }

    @Test
    fun `withSnapshot restores previous snapshot on nested exit, not null`() {
        val state = AyuIslandsState().apply { chromeTintIntensity = 5 }
        val outer = chromeSnapshot(intensityPercent = 18)
        val inner = chromeSnapshot(intensityPercent = 35)

        ChromeTintContext.withSnapshot(outer) {
            assertEquals(18, ChromeTintContext.currentIntensity(state).percent)
            ChromeTintContext.withSnapshot(inner) {
                assertEquals(35, ChromeTintContext.currentIntensity(state).percent)
            }
            assertEquals(
                18,
                ChromeTintContext.currentIntensity(state).percent,
                "After nested withSnapshot exit, the outer snapshot must be restored — not null",
            )
        }
    }

    @Test
    fun `withSnapshot pops the slot when block throws (try-finally invariant)`() {
        val state = AyuIslandsState().apply { chromeTintIntensity = 7 }
        val snapshot = chromeSnapshot(intensityPercent = 27)
        val token = "boom"

        val captured =
            runCatching {
                ChromeTintContext.withSnapshot(snapshot) {
                    error(token)
                }
            }
        assertTrue(captured.isFailure, "block should propagate its exception out of withSnapshot")
        assertEquals(token, captured.exceptionOrNull()?.message)

        // After the throw, currentIntensity must fall back to state — proving
        // the finally block popped the snapshot even though block() threw.
        assertEquals(
            state.effectiveChromeTintIntensity().percent,
            ChromeTintContext.currentIntensity(state).percent,
            "ThreadLocal slot must be cleared after withSnapshot throws",
        )
    }

    @Test
    fun `withSnapshot returns the block's result`() {
        val state = AyuIslandsState()
        val snapshot = chromeSnapshot(intensityPercent = 9)

        val result =
            ChromeTintContext.withSnapshot(snapshot) {
                "ok-${ChromeTintContext.currentIntensity(state).percent}"
            }
        assertEquals("ok-9", result, "withSnapshot must thread the block's return value back to the caller")
    }

    @Test
    fun `isToggleEnabled prefers snapshot for chrome ids`() {
        val state =
            AyuIslandsState().apply {
                chromeStatusBar = false
            }
        val snapshot = chromeSnapshot(chromeStatusBar = true)

        ChromeTintContext.withSnapshot(snapshot) {
            assertTrue(
                ChromeTintContext.isToggleEnabled(state, AccentElementId.STATUS_BAR),
                "Chrome id must read from snapshot, not state, while withSnapshot is active",
            )
        }
        // Outside the snapshot frame the state value reasserts itself.
        assertFalse(
            ChromeTintContext.isToggleEnabled(state, AccentElementId.STATUS_BAR),
            "Chrome id must read from state once the snapshot frame exits",
        )
    }

    @Test
    fun `isToggleEnabled falls back to state for non-chrome ids even with active snapshot`() {
        // The Phase 40.4 snapshot owns CHROME ids only. Non-chrome ids
        // (Inlay hints, caret row, links, etc.) must still pull from state —
        // this proves the `else` branch in [ChromeTintSnapshot.isToggleEnabled].
        val state = AyuIslandsState()
        val expected = state.isToggleEnabled(AccentElementId.INLAY_HINTS)
        val snapshot = chromeSnapshot()

        ChromeTintContext.withSnapshot(snapshot) {
            assertEquals(
                expected,
                ChromeTintContext.isToggleEnabled(state, AccentElementId.INLAY_HINTS),
                "Non-chrome ids must delegate to state regardless of snapshot",
            )
        }
    }

    @Test
    fun `isToggleEnabled without snapshot falls back to state for chrome ids`() {
        val state = AyuIslandsState().apply { chromeNavBar = true }

        assertTrue(
            ChromeTintContext.isToggleEnabled(state, AccentElementId.NAV_BAR),
            "Without an active snapshot the lookup must mirror state",
        )
    }

    @Test
    fun `state remains untouched even though block executes`() {
        // Proves the snapshot path never writes through to AyuIslandsState —
        // the load-bearing "state untouched on throw" property of the apply
        // contract relies on this.
        val state = AyuIslandsState().apply { chromeTintIntensity = 12 }
        val snapshot = chromeSnapshot(intensityPercent = 45)

        ChromeTintContext.withSnapshot(snapshot) {
            assertEquals(45, ChromeTintContext.currentIntensity(state).percent)
            assertEquals(12, state.chromeTintIntensity, "Snapshot read must NEVER mutate state")
        }
        assertEquals(
            12,
            state.chromeTintIntensity,
            "Snapshot exit must NEVER mutate state either",
        )
    }

    /**
     * Common-case snapshot factory. Defaults every chrome toggle to false; tests
     * that need a specific toggle on construct [ChromeTintSnapshot] directly.
     * Kept narrow (2 params) so detekt's `LongParameterList` rule stays happy.
     */
    private fun chromeSnapshot(
        intensityPercent: Int = 0,
        chromeStatusBar: Boolean = false,
    ): ChromeTintSnapshot =
        ChromeTintSnapshot(
            chromeStatusBar = chromeStatusBar,
            chromeMainToolbar = false,
            chromeToolWindowStripe = false,
            chromeNavBar = false,
            chromePanelBorder = false,
            intensity = TintIntensity.of(intensityPercent),
        )
}
