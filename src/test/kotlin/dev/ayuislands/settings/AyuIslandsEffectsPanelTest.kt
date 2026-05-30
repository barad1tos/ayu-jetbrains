package dev.ayuislands.settings

import com.intellij.ide.ui.LafManager
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.application.Application
import com.intellij.openapi.application.ApplicationManager
import com.intellij.ui.dsl.builder.SegmentedButton
import dev.ayuislands.glow.GlowPreset
import dev.ayuislands.licensing.LicenseChecker
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkClass
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFalse

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
}
