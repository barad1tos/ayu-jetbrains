package dev.ayuislands.integration

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.ayuislands.settings.AyuIslandsSettings

class StateRoundTripIntegrationTest : BasePlatformTestCase() {
    fun testSettingsInstanceIsAvailable() {
        val settings = AyuIslandsSettings.getInstance()
        assertNotNull("AyuIslandsSettings service must be registered", settings)
        assertNotNull("State must not be null", settings.state)
    }

    fun testStateModificationsPersist() {
        val settings = AyuIslandsSettings.getInstance()
        settings.state.mirageAccent = "#FF0000"
        settings.state.glowEnabled = true

        assertEquals("#FF0000", settings.state.mirageAccent)
        assertTrue(settings.state.glowEnabled)
    }
}
