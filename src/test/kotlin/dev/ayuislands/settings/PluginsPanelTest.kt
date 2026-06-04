package dev.ayuislands.settings

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ex.ActionManagerEx
import com.intellij.openapi.application.Application
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.dsl.builder.panel
import dev.ayuislands.accent.AccentApplicator
import dev.ayuislands.accent.AccentContext
import dev.ayuislands.accent.AyuVariant
import dev.ayuislands.accent.conflict.ConflictRegistry
import dev.ayuislands.glow.GlowOverlayManager
import dev.ayuislands.indent.IndentPreset
import dev.ayuislands.licensing.LicenseChecker
import dev.ayuislands.settings.mappings.AccentSwatchPickerRow
import dev.ayuislands.syntax.SyntaxIntensityService
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkClass
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import java.awt.Component
import java.awt.Container
import javax.swing.JCheckBox
import javax.swing.JLabel
import javax.swing.JSlider
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PluginsPanelTest {
    private lateinit var state: AyuIslandsState
    private lateinit var settings: AyuIslandsSettings
    private lateinit var syntaxIntensityService: SyntaxIntensityService

    @BeforeTest
    fun setUp() {
        state = AyuIslandsState()
        settings = mockk(relaxed = true)
        every { settings.state } returns state
        syntaxIntensityService = mockk(relaxed = true)
        mockkObject(AyuIslandsSettings.Companion)
        every { AyuIslandsSettings.getInstance() } returns settings

        mockkObject(LicenseChecker)
        every { LicenseChecker.isLicensedOrGrace() } returns true

        mockkObject(ConflictRegistry)
        every { ConflictRegistry.isCodeGlanceProDetected() } returns false
        every { ConflictRegistry.isIndentRainbowDetected() } returns true

        wireUiDslServices()
    }

    @AfterTest
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `unlicensed indent rainbow keeps nested preview controls visible but locked`() {
        every { LicenseChecker.isLicensedOrGrace() } returns false
        state.irIntegrationEnabled = false
        val pluginsPanel = PluginsPanel()

        val dialogPanel = buildDialogPanel(pluginsPanel)
        val errorCheckbox =
            descendants(dialogPanel, JCheckBox::class.java)
                .first { it.text == "Highlight indent errors" }
        val alphaSlider = descendants(dialogPanel, JSlider::class.java).first()

        assertTrue(
            errorCheckbox.isEffectivelyVisibleWithin(dialogPanel),
            "Locked Indent Rainbow preview must show the error-highlighting row even when sync is off",
        )
        assertFalse(
            errorCheckbox.isEnabled,
            "Locked Indent Rainbow error-highlighting checkbox must be visible but not mutable",
        )
        assertTrue(
            alphaSlider.isEffectivelyVisibleWithin(dialogPanel),
            "Locked Indent Rainbow preview must show the custom alpha row",
        )
        assertFalse(
            alphaSlider.isEnabled,
            "Locked Indent Rainbow alpha slider must be visible but not mutable",
        )
        assertFalse(
            pluginsPanel.isModified(),
            "Rendering locked plugin integration previews must not dirty Settings",
        )
    }

    @Test
    fun `reset restores indent rainbow nested row visibility for licensed users`() {
        state.irIntegrationEnabled = true
        state.indentPresetName = IndentPreset.CUSTOM.name
        val pluginsPanel = PluginsPanel()

        val dialogPanel = buildDialogPanel(pluginsPanel)
        val enabledCheckbox =
            descendants(dialogPanel, JCheckBox::class.java)
                .first { it.text == "Indent Rainbow guides" }
        val errorCheckbox =
            descendants(dialogPanel, JCheckBox::class.java)
                .first { it.text == "Highlight indent errors" }
        val alphaSlider = descendants(dialogPanel, JSlider::class.java).first()

        enabledCheckbox.doClick()

        assertFalse(
            errorCheckbox.isEffectivelyVisibleWithin(dialogPanel),
            "Disabling Indent Rainbow sync should hide dependent rows for licensed users",
        )

        pluginsPanel.reset()

        assertTrue(enabledCheckbox.isSelected, "Reset must restore the stored Indent Rainbow enabled state")
        assertTrue(
            errorCheckbox.isEffectivelyVisibleWithin(dialogPanel),
            "Reset must restore dependent Indent Rainbow rows when stored sync is enabled",
        )
        assertTrue(
            alphaSlider.isEffectivelyVisibleWithin(dialogPanel),
            "Reset must restore custom alpha row visibility when stored preset is Custom",
        )
        assertFalse(pluginsPanel.isModified(), "Reset must leave Plugins settings clean")
    }

    @Test
    fun `ignore plugin toggle stays visible without premium plugin detections`() {
        every { ConflictRegistry.isIndentRainbowDetected() } returns false
        val pluginsPanel = PluginsPanel()

        val dialogPanel = buildDialogPanel(pluginsPanel)
        val ignoreCheckbox =
            descendants(dialogPanel, JCheckBox::class.java)
                .first { it.text == ".ignore syntax colors" }

        assertTrue(
            ignoreCheckbox.isEffectivelyVisibleWithin(dialogPanel),
            ".ignore syntax-color opt-out must stay visible even when premium plugin sync targets are absent",
        )
        assertTrue(ignoreCheckbox.isSelected, ".ignore syntax colors default on for the release feature")
        assertFalse(pluginsPanel.isModified(), "Rendering the default-on .ignore toggle must not dirty Settings")
    }

    @Test
    fun `external themes group is visible without premium plugin detections`() {
        every { ConflictRegistry.isIndentRainbowDetected() } returns false
        every { ConflictRegistry.isCodeGlanceProDetected() } returns false
        val pluginsPanel = PluginsPanel()

        val dialogPanel = buildDialogPanel(pluginsPanel)
        val checkbox =
            descendants(dialogPanel, JCheckBox::class.java)
                .first { it.text == "Enable Ayu enhancements on other themes" }

        assertTrue(checkbox.isEffectivelyVisibleWithin(dialogPanel))
        assertFalse(checkbox.isSelected)
        assertFalse(pluginsPanel.isModified())
    }

    @Test
    fun `external theme inheritance toggles render with approved defaults`() {
        val pluginsPanel = PluginsPanel()

        val dialogPanel = buildDialogPanel(pluginsPanel)
        val checkboxes = descendants(dialogPanel, JCheckBox::class.java)
        val masterCheckbox = checkboxes.first { it.text == "Enable Ayu enhancements on other themes" }
        val quickSwitcherCheckbox = checkboxes.first { it.text == "Quick switcher" }
        val glowCheckbox = checkboxes.first { it.text == "Glow" }
        val codeGlanceProCheckbox = checkboxes.first { it.text == "CodeGlance Pro" }
        val indentRainbowCheckbox = checkboxes.first { it.text == "Indent Rainbow" }

        assertTrue(masterCheckbox.isEffectivelyVisibleWithin(dialogPanel))
        assertTrue(quickSwitcherCheckbox.isEffectivelyVisibleWithin(dialogPanel))
        assertTrue(glowCheckbox.isEffectivelyVisibleWithin(dialogPanel))
        assertTrue(codeGlanceProCheckbox.isEffectivelyVisibleWithin(dialogPanel))
        assertTrue(indentRainbowCheckbox.isEffectivelyVisibleWithin(dialogPanel))
        assertFalse(masterCheckbox.isSelected)
        assertTrue(quickSwitcherCheckbox.isSelected)
        assertFalse(glowCheckbox.isSelected)
        assertTrue(codeGlanceProCheckbox.isSelected)
        assertTrue(indentRainbowCheckbox.isSelected)
        assertFalse(pluginsPanel.isModified())
    }

    @Test
    fun `plugins tab renders two approved focus sections`() {
        val pluginsPanel = PluginsPanel()

        val dialogPanel = buildDialogPanel(pluginsPanel)
        val labels = descendants(dialogPanel, JLabel::class.java).mapNotNull { it.text }

        assertTrue("External Theme Support" in labels)
        assertTrue("Plugin Integrations" in labels)
        assertFalse(".ignore" in labels, ".ignore should render as an integration row, not a standalone group")
        assertFalse(
            "CodeGlance Pro" in labels,
            "CodeGlance Pro should render as an integration row, not a standalone group",
        )
        assertFalse(
            "Indent Rainbow" in labels,
            "Indent Rainbow should render as an integration row, not a standalone group",
        )
    }

    @Test
    fun `external theme toggle persists opt-in`() {
        val pluginsPanel = PluginsPanel()
        val dialogPanel = buildDialogPanel(pluginsPanel)
        val checkbox =
            descendants(dialogPanel, JCheckBox::class.java)
                .first { it.text == "Enable Ayu enhancements on other themes" }

        checkbox.doClick()
        pluginsPanel.apply()

        assertTrue(state.externalThemeEnhancementsEnabled)
        assertFalse(pluginsPanel.isModified())
    }

    @Test
    fun `external theme opt-in applies external context immediately on non-Ayu themes`() {
        mockkObject(AyuVariant.Companion)
        every { AyuVariant.detect() } returns null
        mockkObject(AccentApplicator)
        every { AccentApplicator.applyForFocusedProject(AccentContext.External) } returns "#AABBCC"
        mockkObject(GlowOverlayManager)
        every { GlowOverlayManager.syncGlowForAllProjects() } returns Unit

        val pluginsPanel = PluginsPanel()
        val dialogPanel = buildDialogPanel(pluginsPanel)
        val checkbox =
            descendants(dialogPanel, JCheckBox::class.java)
                .first { it.text == "Enable Ayu enhancements on other themes" }

        checkbox.doClick()
        pluginsPanel.apply()

        assertTrue(state.externalThemeEnhancementsEnabled)
        verify(exactly = 1) { AccentApplicator.applyForFocusedProject(AccentContext.External) }
        verify(exactly = 1) { GlowOverlayManager.syncGlowForAllProjects() }
    }

    @Test
    fun `external theme opt-out reverts inherited surfaces immediately on non-Ayu themes`() {
        state.externalThemeEnhancementsEnabled = true
        mockkObject(AyuVariant.Companion)
        every { AyuVariant.detect() } returns null
        mockkObject(AccentApplicator)
        every { AccentApplicator.revertAll() } returns Unit
        mockkObject(GlowOverlayManager)
        every { GlowOverlayManager.syncGlowForAllProjects() } returns Unit

        val pluginsPanel = PluginsPanel()
        val dialogPanel = buildDialogPanel(pluginsPanel)
        val checkbox =
            descendants(dialogPanel, JCheckBox::class.java)
                .first { it.text == "Enable Ayu enhancements on other themes" }

        checkbox.doClick()
        pluginsPanel.apply()

        assertFalse(state.externalThemeEnhancementsEnabled)
        verify(exactly = 1) { AccentApplicator.revertAll() }
        verify(exactly = 1) { GlowOverlayManager.syncGlowForAllProjects() }
    }

    @Test
    fun `external theme inheritance toggles persist independently`() {
        val pluginsPanel = PluginsPanel()
        val dialogPanel = buildDialogPanel(pluginsPanel)
        val checkboxes = descendants(dialogPanel, JCheckBox::class.java)
        val quickSwitcherCheckbox = checkboxes.first { it.text == "Quick switcher" }
        val glowCheckbox = checkboxes.first { it.text == "Glow" }
        val codeGlanceProCheckbox = checkboxes.first { it.text == "CodeGlance Pro" }
        val indentRainbowCheckbox = checkboxes.first { it.text == "Indent Rainbow" }

        quickSwitcherCheckbox.doClick()
        glowCheckbox.doClick()
        codeGlanceProCheckbox.doClick()
        indentRainbowCheckbox.doClick()
        pluginsPanel.apply()

        assertFalse(state.externalThemeQuickSwitcherEnabled)
        assertTrue(state.externalThemeGlowEnabled)
        assertFalse(state.externalThemeCodeGlanceProEnabled)
        assertFalse(state.externalThemeIndentRainbowEnabled)
        assertFalse(pluginsPanel.isModified())
    }

    @Test
    fun `external accent picker renders stored fallback accent`() {
        state.externalThemeAccent = "#AABBCC"
        val pluginsPanel = PluginsPanel()

        val dialogPanel = buildDialogPanel(pluginsPanel)
        val picker =
            descendants(dialogPanel, AccentSwatchPickerRow::class.java)
                .first()

        assertEquals("#AABBCC", picker.selectedHex)
        assertFalse(pluginsPanel.isModified())
    }

    @Test
    fun `unlicensed users can disable ignore plugin syntax colors`() {
        every { LicenseChecker.isLicensedOrGrace() } returns false
        every { ConflictRegistry.isIndentRainbowDetected() } returns false
        state.ignorePluginSyntaxColorsEnabled = true
        val pluginsPanel = PluginsPanel()

        val dialogPanel = buildDialogPanel(pluginsPanel)
        val ignoreCheckbox =
            descendants(dialogPanel, JCheckBox::class.java)
                .first { it.text == ".ignore syntax colors" }

        ignoreCheckbox.doClick()
        pluginsPanel.apply()

        assertFalse(state.ignorePluginSyntaxColorsEnabled, "Free users must be able to opt out of .ignore colors")
        assertFalse(pluginsPanel.isModified(), "Persisting the .ignore opt-out should clean the panel state")
        verify(exactly = 1) { syntaxIntensityService.reapplyForActiveLaf() }
    }

    @Test
    fun `unlicensed users can re-enable ignore plugin syntax colors`() {
        every { LicenseChecker.isLicensedOrGrace() } returns false
        every { ConflictRegistry.isIndentRainbowDetected() } returns false
        state.ignorePluginSyntaxColorsEnabled = false
        val pluginsPanel = PluginsPanel()

        val dialogPanel = buildDialogPanel(pluginsPanel)
        val ignoreCheckbox =
            descendants(dialogPanel, JCheckBox::class.java)
                .first { it.text == ".ignore syntax colors" }

        assertFalse(ignoreCheckbox.isSelected, "Stored .ignore opt-out must render unchecked")

        ignoreCheckbox.doClick()
        pluginsPanel.apply()

        assertTrue(state.ignorePluginSyntaxColorsEnabled, "Free users must be able to re-enable .ignore colors")
        assertFalse(pluginsPanel.isModified(), "Persisting the .ignore opt-in should clean the panel state")
        verify(exactly = 1) { syntaxIntensityService.reapplyForActiveLaf() }
    }

    private fun buildDialogPanel(pluginsPanel: PluginsPanel): DialogPanel =
        panel {
            pluginsPanel.buildPanel(this, AyuVariant.MIRAGE)
        }

    private fun wireUiDslServices() {
        mockkStatic(ApplicationManager::class)
        val appMock = mockk<Application>(relaxed = true)
        val actionManagerMock = mockk<ActionManagerEx>(relaxed = true)
        mockkStatic(ActionManager::class)
        every { ActionManager.getInstance() } returns actionManagerMock
        every { ApplicationManager.getApplication() } returns appMock
        every { appMock.invokeLater(any()) } answers { firstArg<Runnable>().run() }
        every { actionManagerMock.getAction(any()) } returns null

        @Suppress("UNCHECKED_CAST")
        val experimentalUiClass = Class.forName("com.intellij.ui.ExperimentalUI") as Class<Any>
        val experimentalUiMock = mockkClass(experimentalUiClass.kotlin, relaxed = true)
        every { appMock.getService(any<Class<*>>()) } answers {
            when (val serviceClass = firstArg<Class<*>>()) {
                ActionManager::class.java,
                ActionManagerEx::class.java,
                -> actionManagerMock
                SyntaxIntensityService::class.java -> syntaxIntensityService
                experimentalUiClass -> experimentalUiMock
                else -> mockkClass(serviceClass.kotlin, relaxed = true)
            }
        }
        every { appMock.getService(ActionManager::class.java) } returns actionManagerMock
        every { appMock.getService(ActionManagerEx::class.java) } returns actionManagerMock
        every { appMock.getServiceIfCreated(ActionManager::class.java) } returns actionManagerMock
    }

    private fun <T : Component> descendants(
        container: Container,
        type: Class<T>,
    ): List<T> =
        buildList {
            fun visit(component: Component) {
                if (type.isInstance(component)) add(type.cast(component))
                if (component is Container) {
                    component.components.forEach(::visit)
                }
            }
            visit(container)
        }

    private fun Component.isEffectivelyVisibleWithin(root: Component): Boolean {
        var current: Component? = this
        while (current != null && current !== root) {
            if (!current.isVisible) return false
            current = current.parent
        }
        return current === root && root.isVisible
    }
}
