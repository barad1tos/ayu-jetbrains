package dev.ayuislands.integration

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.ayuislands.settings.AyuIslandsConfigurable
import dev.ayuislands.settings.AyuIslandsEffectsPanel
import dev.ayuislands.settings.AyuIslandsSettings
import io.mockk.mockkConstructor
import io.mockk.unmockkConstructor
import io.mockk.verify
import java.awt.Container
import javax.swing.JCheckBox
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

    fun testConfigurableDelegatesModifiedResetAndApplyToItsSession() {
        val settings = AyuIslandsSettings.getInstance()
        val storedIgnoreSetting = settings.state.ignorePluginSyntaxColorsEnabled
        val configurable = AyuIslandsConfigurable()
        try {
            val component = configurable.createComponent()
            val checkbox =
                descendants(component, JCheckBox::class.java)
                    .single { it.text == ".ignore syntax colors" }

            assertEquals(storedIgnoreSetting, checkbox.isSelected)
            checkbox.doClick()
            assertTrue(configurable.isModified)

            configurable.reset()
            assertEquals(storedIgnoreSetting, checkbox.isSelected)
            assertFalse(configurable.isModified)

            checkbox.doClick()
            configurable.apply()
            assertEquals(!storedIgnoreSetting, settings.state.ignorePluginSyntaxColorsEnabled)
            assertFalse(configurable.isModified)
        } finally {
            settings.state.ignorePluginSyntaxColorsEnabled = storedIgnoreSetting
            configurable.disposeUIResources()
        }
    }

    fun testConfigurableClosesReplacedAndDisposedSessions() {
        mockkConstructor(AyuIslandsEffectsPanel::class)
        val configurable = AyuIslandsConfigurable()
        try {
            configurable.createPanel()
            configurable.createPanel()

            verify(exactly = 1) { anyConstructed<AyuIslandsEffectsPanel>().dispose() }

            configurable.disposeUIResources()

            verify(exactly = 2) { anyConstructed<AyuIslandsEffectsPanel>().dispose() }
        } finally {
            configurable.disposeUIResources()
            unmockkConstructor(AyuIslandsEffectsPanel::class)
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

    private fun <T : java.awt.Component> descendants(
        root: java.awt.Component,
        type: Class<T>,
        found: MutableList<T> = mutableListOf(),
    ): List<T> {
        if (type.isInstance(root)) {
            found += type.cast(root)
        }
        if (root is Container) {
            root.components.forEach { descendants(it, type, found) }
        }
        return found
    }
}
