package dev.ayuislands.settings

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Legacy XML import and accepted Studio persistence retain user choices across reload. */
class AyuIslandsStateQuickSwitcherTest {
    private fun roundTrip(mutate: (AyuIslandsState) -> Unit): dev.ayuislands.studio.persistence.AyuSettingsView =
        dev.ayuislands.studio.StudioFixtures
            .persistedView(freshSettingsState().apply(mutate))
            .settings

    @Test
    fun `quickSwitcherWidgetEnabled defaults to true on a fresh state`() {
        val settings = freshSettingsState()
        assertTrue(
            settings.quickSwitcherWidgetEnabled,
            "Chip is FREE and surfaces on first launch — the toggle must default to true",
        )
    }

    @Test
    fun `quickSwitcherWidgetEnabled survives save reload cycle when disabled`() {
        val reloaded =
            roundTrip { state ->
                state.quickSwitcherWidgetEnabled = false
            }
        assertFalse(
            reloaded.quickSwitcherWidgetEnabled,
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
            reloaded.quickSwitcherWidgetEnabled,
            "Last-write-wins across reload — the final true survives the round-trip",
        )
    }
}
