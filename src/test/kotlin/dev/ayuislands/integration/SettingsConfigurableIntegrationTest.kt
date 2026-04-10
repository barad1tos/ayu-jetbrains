package dev.ayuislands.integration

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.ayuislands.settings.AyuIslandsConfigurable

class SettingsConfigurableIntegrationTest : BasePlatformTestCase() {
    fun testConfigurableCreatesComponent() {
        val configurable = AyuIslandsConfigurable()
        try {
            val component = configurable.createComponent()
            assertNotNull("Configurable must produce a non-null component", component)
        } finally {
            configurable.disposeUIResources()
        }
    }

    fun testConfigurableIsNotModifiedByDefault() {
        val configurable = AyuIslandsConfigurable()
        try {
            configurable.createComponent()
            assertFalse(
                "Fresh configurable must not report modified",
                configurable.isModified,
            )
        } finally {
            configurable.disposeUIResources()
        }
    }
}
