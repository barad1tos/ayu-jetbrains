package dev.ayuislands.licensing

import dev.ayuislands.settings.AyuIslandsState
import kotlin.test.Test
import kotlin.test.assertEquals

class LicenseCheckerQuickSwitcherRevertTest {
    @Test
    fun `license loss preserves both hidden and visible Quick Switcher choices`() {
        for (visible in listOf(false, true)) {
            val state = AyuIslandsState().apply { quickSwitcherWidgetEnabled = visible }
            val accepted = confirmUnlicensed(state).settings
            assertEquals(visible, accepted.quickSwitcherWidgetEnabled)
        }
    }
}
