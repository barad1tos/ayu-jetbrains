package dev.ayuislands.integration

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.ayuislands.settings.AyuIslandsConfigurable
import java.awt.Container
import javax.swing.JTabbedPane

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

    fun testConfigurableExposesPluginsTabByDefault() {
        val configurable = AyuIslandsConfigurable()
        try {
            val component = configurable.createComponent()
            val tabTitles =
                collectTabbedPanes(component)
                    .flatMap { tabbedPane ->
                        (0 until tabbedPane.tabCount).map(tabbedPane::getTitleAt)
                    }

            assertTrue(
                "Plugins tab must stay reachable when Settings opens outside an Ayu theme",
                tabTitles.contains("Plugins"),
            )
        } finally {
            configurable.disposeUIResources()
        }
    }

    private fun collectTabbedPanes(
        root: java.awt.Component,
        found: MutableList<JTabbedPane> = mutableListOf(),
    ): List<JTabbedPane> {
        if (root is JTabbedPane) {
            found += root
        }
        if (root is Container) {
            root.components.forEach { collectTabbedPanes(it, found) }
        }
        return found
    }
}
