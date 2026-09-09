package dev.ayuislands

import com.intellij.ide.ui.LafManager
import com.intellij.openapi.application.Application
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.util.JDOMUtil
import com.intellij.openapi.wm.IdeFrame
import com.intellij.util.xmlb.XmlSerializer
import dev.ayuislands.accent.SystemAppearanceProvider
import dev.ayuislands.accent.SystemAppearanceProvider.Appearance
import dev.ayuislands.settings.AyuIslandsSettings
import dev.ayuislands.settings.AyuIslandsState
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import javax.swing.SwingUtilities
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

class AppearanceSyncListenerTest {
    private val application = mockk<Application>()
    private val ideFrame = mockk<IdeFrame>()
    private val lafManager = mockk<LafManager>()
    private val listener = AppearanceSyncListener()
    private val switchedThemes = mutableListOf<String>()

    private lateinit var settings: AyuIslandsSettings
    private lateinit var syncService: AppearanceSyncService
    private var systemAppearance = Appearance.DARK
    private var currentThemeName = "Ayu Mirage (Islands UI)"

    @BeforeTest
    fun setUp() {
        settings = createSettings()
        syncService = AppearanceSyncService()

        // ApplicationManager is the supported SDK boundary used by both real services.
        mockkStatic(ApplicationManager::class)
        every { ApplicationManager.getApplication() } returns application
        every { application.getService(AyuIslandsSettings::class.java) } answers { settings }
        every { application.getService(AppearanceSyncService::class.java) } answers { syncService }

        // These adapters read OS/global LAF state, so the fixture supplies deterministic native input/output.
        mockkStatic(LafManager::class)
        every { LafManager.getInstance() } returns lafManager
        mockkObject(SystemAppearanceProvider)
        every { SystemAppearanceProvider.resolve() } answers { systemAppearance }
        mockkObject(AyuLaf)
        every { AyuLaf.currentThemeName(lafManager) } answers { currentThemeName }
        every {
            AyuLaf.switchToThemeByName(
                themeName = any(),
                shouldLockEditorScheme = true,
                shouldApplyLater = true,
            )
        } answers {
            val requestedTheme = firstArg<String>()
            switchedThemes += requestedTheme
            currentThemeName = requestedTheme
            true
        }
    }

    @AfterTest
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `disabled activation preserves settings and defers a required theme switch`() {
        systemAppearance = Appearance.LIGHT
        val disabledState = encodedState()

        activate()

        assertEquals(emptyList(), switchedThemes)
        assertEquals(disabledState, encodedState())

        settings.state.followSystemAppearance = true
        val enabledState = encodedState()

        activate()

        assertEquals(listOf(LIGHT_THEME), switchedThemes)
        assertEquals(enabledState, encodedState())
    }

    @Test
    fun `enabled activations select remembered themes idempotently without changing preferences`() {
        settings.state.followSystemAppearance = true
        val originalState = encodedState()

        systemAppearance = Appearance.LIGHT
        activate()
        assertEquals(listOf(LIGHT_THEME), switchedThemes)
        assertEquals(originalState, encodedState())

        activate()
        assertEquals(listOf(LIGHT_THEME), switchedThemes)
        assertEquals(originalState, encodedState())

        systemAppearance = Appearance.DARK
        activate()
        assertEquals(listOf(LIGHT_THEME, DARK_THEME), switchedThemes)
        assertEquals(originalState, encodedState())

        activate()
        assertEquals(listOf(LIGHT_THEME, DARK_THEME), switchedThemes)
        assertEquals(originalState, encodedState())
    }

    @Test
    fun `disable reload and re-enable preserve unavailable customization and use latest appearance`() {
        settings.state.followSystemAppearance = true
        systemAppearance = Appearance.LIGHT
        val enabledState = encodedState()

        activate()

        assertEquals(listOf(LIGHT_THEME), switchedThemes)
        assertEquals(enabledState, encodedState())

        settings.state.followSystemAppearance = false
        val disabledState = encodedState()
        reloadServices(disabledState)
        assertEquals(disabledState, encodedState())

        systemAppearance = Appearance.DARK
        activate()
        assertEquals(listOf(LIGHT_THEME), switchedThemes)
        assertEquals(disabledState, encodedState())

        settings.state.followSystemAppearance = true
        val reenabledState = encodedState()
        activate()
        assertEquals(listOf(LIGHT_THEME, DARK_THEME), switchedThemes)
        assertEquals(reenabledState, encodedState())

        reloadServices(reenabledState)
        assertEquals(reenabledState, encodedState())
        assertEquals(FUTURE_CUSTOMIZATION, settings.state.fontPresetCustomizations[FUTURE_PRESET])
        assertEquals(DARK_THEME, settings.state.lastDarkAppearanceTheme)
        assertEquals(LIGHT_THEME, settings.state.lastLightAppearanceTheme)
    }

    private fun activate() {
        SwingUtilities.invokeAndWait { listener.applicationActivated(ideFrame) }
    }

    private fun encodedState(): String = JDOMUtil.writeElement(XmlSerializer.serialize(settings.state))

    private fun reloadServices(encodedState: String) {
        settings =
            AyuIslandsSettings().apply {
                loadState(XmlSerializer.deserialize(JDOMUtil.load(encodedState), AyuIslandsState::class.java))
            }
        syncService = AppearanceSyncService()
    }

    private fun createSettings(): AyuIslandsSettings =
        AyuIslandsSettings().apply {
            state.followSystemAppearance = false
            state.lastDarkAppearanceTheme = DARK_THEME
            state.lastLightAppearanceTheme = LIGHT_THEME
            state.glowEnabled = true
            state.glowStyle = "SHARP_NEON"
            state.sharpNeonIntensity = 73
            state.sharpNeonWidth = 17
            state.fontPresetEnabled = true
            state.fontPresetName = "CUSTOM"
            state.fontApplyToConsole = true
            state.fontPresetCustomizations[FUTURE_PRESET] = FUTURE_CUSTOMIZATION
        }

    private companion object {
        const val DARK_THEME = "Ayu Dark (Islands UI)"
        const val LIGHT_THEME = "Ayu Light (Islands UI)"
        const val FUTURE_PRESET = "FUTURE_PRESET"
        const val FUTURE_CUSTOMIZATION =
            "21.00|1.37|true|FUTURE_WEIGHT|Unavailable Font|extension=42"
    }
}
