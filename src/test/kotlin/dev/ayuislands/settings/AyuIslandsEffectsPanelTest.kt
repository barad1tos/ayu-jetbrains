package dev.ayuislands.settings

import com.intellij.ide.ui.LafManager
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.application.Application
import com.intellij.openapi.application.ApplicationManager
import com.intellij.ui.dsl.builder.SegmentedButton
import com.intellij.ui.dsl.builder.components.SegmentedButtonComponent
import dev.ayuislands.glow.GlowPlacement
import dev.ayuislands.glow.GlowPreset
import dev.ayuislands.licensing.LicenseChecker
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkClass
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import java.awt.Component
import java.awt.Container
import javax.swing.JCheckBox
import javax.swing.JComboBox
import javax.swing.JLabel
import javax.swing.JSlider
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AyuIslandsEffectsPanelTest {
    private lateinit var state: AyuIslandsState
    private lateinit var settings: AyuIslandsSettings

    @BeforeTest
    fun setUp() {
        state =
            AyuIslandsState().apply {
                glowEnabled = true
                glowPreset = GlowPreset.WHISPER.name
            }
        settings = mockk(relaxed = true)
        every { settings.state } returns state
        mockkObject(AyuIslandsSettings.Companion)
        every { AyuIslandsSettings.getInstance() } returns settings

        mockkObject(LicenseChecker)
        every { LicenseChecker.isLicensedOrGrace() } returns true

        mockkStatic(ApplicationManager::class)
        val appMock = mockk<Application>(relaxed = true)
        val actionManagerMock = mockk<ActionManager>(relaxed = true)
        val lafManagerMock = mockk<LafManager>(relaxed = true)
        every { ApplicationManager.getApplication() } returns appMock
        every { appMock.invokeLater(any()) } answers { firstArg<Runnable>().run() }
        every { appMock.getService(ActionManager::class.java) } returns actionManagerMock
        every { appMock.getService(LafManager::class.java) } returns lafManagerMock
        every { actionManagerMock.getAction(any()) } returns null

        @Suppress("UNCHECKED_CAST")
        val experimentalUiClass = Class.forName("com.intellij.ui.ExperimentalUI") as Class<Any>
        val experimentalUiMock = mockkClass(experimentalUiClass.kotlin, relaxed = true)
        every { appMock.getService(experimentalUiClass) } returns experimentalUiMock
    }

    @AfterTest
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `unlicensed glow preset preview cannot mutate pending state`() {
        every { LicenseChecker.isLicensedOrGrace() } returns false
        val effectsPanel = AyuIslandsEffectsPanel()
        buildDialogPanel(effectsPanel)

        assertFalse(effectsPanel.isModified(), "locked Glow preview must start clean")
        presetSegmented(effectsPanel).selectedItem = GlowPreset.CYBERPUNK

        assertFalse(
            effectsPanel.isModified(),
            "locked Glow preset preview must ignore selection changes instead of dirtying Settings",
        )
    }

    @Test
    fun `unlicensed glow keeps custom controls and targets visible but locked`() {
        every { LicenseChecker.isLicensedOrGrace() } returns false
        state.glowEnabled = false
        state.glowPreset = GlowPreset.WHISPER.name
        val effectsPanel = AyuIslandsEffectsPanel()

        val dialogPanel = buildDialogPanel(effectsPanel)
        val styleCombo = field<JComboBox<*>>(effectsPanel, "styleCombo")
        val animationCombo = field<JComboBox<*>>(effectsPanel, "animationCombo")
        val intensitySlider = field<JSlider>(effectsPanel, "intensitySlider")
        val widthSlider = field<JSlider>(effectsPanel, "widthSlider")
        val targetCheckboxes = islandCheckboxes(effectsPanel).values.toList()
        val targetsLabel = descendants(dialogPanel, JLabel::class.java).first { it.text == "Targets" }

        assertTrue(
            styleCombo.isEffectivelyVisibleWithin(dialogPanel),
            "Locked Glow preview must show the style selector even when the preset is not Custom",
        )
        assertFalse(styleCombo.isEnabled, "Locked Glow style selector must not be mutable")
        assertTrue(
            intensitySlider.isEffectivelyVisibleWithin(dialogPanel),
            "Locked Glow preview must show the intensity slider even when the preset is not Custom",
        )
        assertFalse(intensitySlider.isEnabled, "Locked Glow intensity slider must not be mutable")
        assertTrue(
            widthSlider.isEffectivelyVisibleWithin(dialogPanel),
            "Locked Glow preview must show the width slider even when the preset is not Custom",
        )
        assertFalse(widthSlider.isEnabled, "Locked Glow width slider must not be mutable")
        assertTrue(
            animationCombo.isEffectivelyVisibleWithin(dialogPanel),
            "Locked Glow preview must show animation controls even when the preset is not Custom",
        )
        assertFalse(animationCombo.isEnabled, "Locked Glow animation controls must not be mutable")
        assertTrue(
            targetsLabel.isEffectivelyVisibleWithin(dialogPanel),
            "Locked Glow preview must show the Targets group even when the preset is not Custom",
        )
        assertTrue(targetCheckboxes.isNotEmpty(), "Locked Glow preview must render target checkboxes")
        assertTrue(
            targetCheckboxes.all { !it.isEnabled },
            "Locked Glow target controls must not be mutable",
        )

        intensitySlider.value += 1

        assertFalse(
            effectsPanel.isModified(),
            "Programmatic changes to locked Glow preview controls must not dirty Settings",
        )
    }

    @Test
    fun `licensed default preset keeps placement visible while targets stay hidden`() {
        val effectsPanel = AyuIslandsEffectsPanel()
        val dialogPanel = buildDialogPanel(effectsPanel)
        val labels = descendants(dialogPanel, JLabel::class.java)

        assertTrue(
            labels.first { it.text == "Editor placement" }.isEffectivelyVisibleWithin(dialogPanel),
            "Editor placement must remain reachable outside the Custom preset",
        )
        assertTrue(
            labels.first { it.text == "Tool window placement" }.isEffectivelyVisibleWithin(dialogPanel),
            "Tool window placement must remain reachable outside the Custom preset",
        )
        assertFalse(
            labels.first { it.text == "Targets" }.isEffectivelyVisibleWithin(dialogPanel),
            "Licensed default presets must keep fine-grained Targets hidden until Custom is selected",
        )
    }

    @Test
    fun `licensed disabled glow keeps placement visible but locked`() {
        state.glowEnabled = false
        val effectsPanel = AyuIslandsEffectsPanel()
        val dialogPanel = buildDialogPanel(effectsPanel)
        val placementLabel = descendants(dialogPanel, JLabel::class.java).first { it.text == "Editor placement" }
        val placementControl =
            descendants(dialogPanel, SegmentedButtonComponent::class.java)
                .first { GlowPlacement.TAB_BAR in it.items }

        assertTrue(
            placementLabel.isEffectivelyVisibleWithin(dialogPanel),
            "disabled Glow must keep placement visible",
        )
        assertFalse(
            placementControl.isEnabled,
            "disabled Glow must lock placement options",
        )
    }

    @Test
    fun `licensed placement selection persists through apply into state`() {
        val effectsPanel = AyuIslandsEffectsPanel()
        buildDialogPanel(effectsPanel)

        placementSegmented(effectsPanel, "editorPlacement").selectedItem = GlowPlacement.TAB_BAR
        placementSegmented(effectsPanel, "toolWindowPlacement").selectedItem = GlowPlacement.SIDE_EDGES

        assertTrue(effectsPanel.isModified(), "placement change must dirty the panel")
        effectsPanel.apply()

        assertEquals(GlowPlacement.TAB_BAR.name, state.glowEditorPlacement)
        assertEquals(GlowPlacement.SIDE_EDGES.name, state.glowToolWindowPlacement)
        assertEquals(
            GlowPreset.WHISPER.name,
            state.glowPreset,
            "placement is independent from the selected visual preset",
        )
        assertFalse(effectsPanel.isModified(), "apply must converge stored onto pending")
    }

    @Test
    fun `unlicensed glow placement preview cannot mutate pending state`() {
        every { LicenseChecker.isLicensedOrGrace() } returns false
        val effectsPanel = AyuIslandsEffectsPanel()
        val dialogPanel = buildDialogPanel(effectsPanel)
        val placementLabel = descendants(dialogPanel, JLabel::class.java).first { it.text == "Editor placement" }

        assertTrue(
            placementLabel.isEffectivelyVisibleWithin(dialogPanel),
            "locked placement preview must remain visible outside the Custom preset",
        )

        placementSegmented(effectsPanel, "editorPlacement").selectedItem = GlowPlacement.TAB_BAR

        assertFalse(
            effectsPanel.isModified(),
            "locked placement preview must ignore selection changes instead of dirtying Settings",
        )
    }

    @Test
    fun `reset restores placement selection from stored state`() {
        val effectsPanel = AyuIslandsEffectsPanel()
        buildDialogPanel(effectsPanel)

        placementSegmented(effectsPanel, "editorPlacement").selectedItem = GlowPlacement.TAB_BAR
        assertTrue(effectsPanel.isModified(), "precondition: selection dirtied the panel")

        effectsPanel.reset()

        assertFalse(effectsPanel.isModified(), "reset must drop the pending placement change")
        assertEquals(
            GlowPlacement.ISLAND,
            placementSegmented(effectsPanel, "editorPlacement").selectedItem,
            "reset must restore the stored placement selection",
        )
    }

    private fun buildDialogPanel(panel: AyuIslandsEffectsPanel) =
        com.intellij.ui.dsl.builder
            .panel {
                panel.buildGlowPanel(this@panel)
            }

    @Suppress("UNCHECKED_CAST")
    private fun presetSegmented(panel: AyuIslandsEffectsPanel): SegmentedButton<GlowPreset> {
        val field = AyuIslandsEffectsPanel::class.java.getDeclaredField("presetSegmentedButton")
        field.isAccessible = true
        return field.get(panel) as? SegmentedButton<GlowPreset>
            ?: error("Glow preset segmented button must be created")
    }

    @Suppress("UNCHECKED_CAST")
    private fun placementSegmented(
        panel: AyuIslandsEffectsPanel,
        fieldName: String,
    ): SegmentedButton<GlowPlacement> {
        val field = AyuIslandsEffectsPanel::class.java.getDeclaredField(fieldName)
        field.isAccessible = true
        return field.get(panel) as? SegmentedButton<GlowPlacement>
            ?: error("Glow placement segmented button '$fieldName' must be created")
    }

    @Suppress("UNCHECKED_CAST")
    private fun islandCheckboxes(panel: AyuIslandsEffectsPanel): Map<String, JCheckBox> {
        val field = AyuIslandsEffectsPanel::class.java.getDeclaredField("islandCheckboxes")
        field.isAccessible = true
        return field.get(panel) as? Map<String, JCheckBox>
            ?: error("Glow target checkboxes must be created")
    }

    private fun <T : Component> field(
        panel: AyuIslandsEffectsPanel,
        fieldName: String,
    ): T {
        val field = AyuIslandsEffectsPanel::class.java.getDeclaredField(fieldName)
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        return field.get(panel) as? T ?: error("$fieldName must be created")
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
