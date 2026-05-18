package dev.ayuislands.settings

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * D-02 invariant lock: `quickSwitcherWidgetEnabled` defaults to `true` (the chip
 * is FREE and surfaces on first launch after install) AND survives the
 * save/reload cycle via [com.intellij.openapi.components.SimplePersistentStateComponent.loadState]
 * so a user who explicitly disables the chip never sees it re-enable itself
 * on the next IDE restart.
 *
 * Mirrors the [AyuIslandsStatePersistenceTest] `roundTrip` harness — IntelliJ's
 * `XmlSerializerUtil.copyBean` reads/writes the same fields used by real IDE
 * restarts, so an in-memory round-trip is representative of on-disk
 * persistence.
 */
class AyuIslandsStateQuickSwitcherTest {
    private fun roundTrip(mutate: (AyuIslandsState) -> Unit): AyuIslandsSettings {
        val original = AyuIslandsSettings()
        mutate(original.state)
        val savedState = original.state
        val reloaded = AyuIslandsSettings()
        reloaded.loadState(savedState)
        return reloaded
    }

    @Test
    fun `quickSwitcherWidgetEnabled defaults to true on a fresh state (D-02)`() {
        val settings = AyuIslandsSettings()
        assertTrue(
            settings.state.quickSwitcherWidgetEnabled,
            "D-02: chip is FREE and surfaces on first launch — the toggle must default to true",
        )
    }

    @Test
    fun `quickSwitcherWidgetEnabled survives save reload cycle when disabled`() {
        val reloaded =
            roundTrip { state ->
                state.quickSwitcherWidgetEnabled = false
            }
        assertFalse(
            reloaded.state.quickSwitcherWidgetEnabled,
            "An explicitly-disabled chip must NOT re-enable across IDE restart " +
                "(XML round-trip preserves the false value)",
        )
    }

    @Test
    fun `quickSwitcherWidgetEnabled survives save reload cycle when re-enabled`() {
        val reloaded =
            roundTrip { state ->
                state.quickSwitcherWidgetEnabled = false
                state.quickSwitcherWidgetEnabled = true
            }
        assertTrue(
            reloaded.state.quickSwitcherWidgetEnabled,
            "Last-write-wins across reload — the final true survives the round-trip",
        )
    }
}
