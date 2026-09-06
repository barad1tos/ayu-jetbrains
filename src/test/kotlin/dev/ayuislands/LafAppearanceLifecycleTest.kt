package dev.ayuislands

import com.intellij.ide.ui.LafManager
import com.intellij.openapi.application.Application
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.util.JDOMUtil
import com.intellij.util.xmlb.XmlSerializer
import dev.ayuislands.accent.AccentApplicator
import dev.ayuislands.accent.AccentContext
import dev.ayuislands.accent.SystemAppearanceProvider
import dev.ayuislands.accent.SystemAppearanceProvider.Appearance
import dev.ayuislands.font.FontPresetApplicator
import dev.ayuislands.glow.GlowOverlayManager
import dev.ayuislands.settings.AyuIslandsSettings
import dev.ayuislands.settings.AyuIslandsState
import dev.ayuislands.syntax.SyntaxIntensityService
import dev.ayuislands.theme.AyuEditorSchemeBinder
import dev.ayuislands.vcs.VcsColorApplier
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import java.util.ArrayDeque
import javax.swing.SwingUtilities
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Exercises appearance synchronization through the real listener, service, settings, and reapply runner. */
class LafAppearanceLifecycleTest {
    private val application = mockk<Application>()
    private val lafManager = mockk<LafManager>()
    private val projectManager = mockk<ProjectManager>()
    private val syntaxService = mockk<SyntaxIntensityService>(relaxed = true)
    private val listener = AyuIslandsLafListener()
    private val pendingApplies = ArrayDeque<() -> Unit>()
    private val completedFontThemes = mutableListOf<String>()
    private val completedVcsThemes = mutableListOf<String>()

    private lateinit var settings: AyuIslandsSettings
    private lateinit var syncService: AppearanceSyncService
    private var systemAppearance = Appearance.DARK
    private var currentThemeName = MIRAGE_THEME
    private var failNextFontApply = false

    @BeforeTest
    fun setUp() {
        settings = createSettings()
        syncService = AppearanceSyncService()

        mockkStatic(ApplicationManager::class)
        every { ApplicationManager.getApplication() } returns application
        every { application.isDispatchThread } answers { SwingUtilities.isEventDispatchThread() }
        every { application.getService(AyuIslandsSettings::class.java) } answers { settings }
        every { application.getService(AppearanceSyncService::class.java) } answers { syncService }

        mockkStatic(ProjectManager::class)
        every { ProjectManager.getInstance() } returns projectManager
        every { projectManager.openProjects } returns emptyArray()

        // These adapters read or mutate IDE-global/native surfaces. The lifecycle's own
        // settings, service, context detection, and reapply runner stay real.
        mockkStatic(LafManager::class)
        every { LafManager.getInstance() } returns lafManager
        mockkObject(SystemAppearanceProvider)
        every { SystemAppearanceProvider.resolve() } answers { systemAppearance }
        mockkObject(AyuLaf)
        every { AyuLaf.currentThemeName(any()) } answers { currentThemeName }
        every {
            AyuLaf.switchToThemeByName(
                themeName = any(),
                shouldLockEditorScheme = true,
                shouldApplyLater = true,
            )
        } answers {
            val requestedTheme = firstArg<String>()
            pendingApplies.addLast {
                currentThemeName = requestedTheme
                listener.lookAndFeelChanged(lafManager)
            }
            true
        }

        mockkObject(AyuEditorSchemeBinder)
        every { AyuEditorSchemeBinder.bindForVariant(any()) } returns true
        mockkObject(AccentApplicator)
        every { AccentApplicator.applyForFocusedProject(any<AccentContext>()) } returns "#FFCC66"
        every { AccentApplicator.revertAll() } returns Unit
        mockkObject(FontPresetApplicator)
        every { FontPresetApplicator.applyFromState() } answers {
            if (failNextFontApply) {
                failNextFontApply = false
                error("controlled font surface failure")
            }
            completedFontThemes += currentThemeName
        }
        every { FontPresetApplicator.revert() } returns Unit
        mockkObject(GlowOverlayManager)
        every { GlowOverlayManager.syncGlowForAllProjects() } returns Unit
        mockkObject(VcsColorApplier)
        every { VcsColorApplier.applyAll() } answers {
            completedVcsThemes += currentThemeName
        }
        every { VcsColorApplier.revertAll() } returns Unit
        mockkObject(SyntaxIntensityService.Companion)
        every { SyntaxIntensityService.getInstance() } returns syntaxService
    }

    @AfterTest
    fun tearDown() {
        pendingApplies.clear()
        unmockkAll()
    }

    @Test
    fun `enabled manual callbacks remember only the matching appearance slot`() {
        settings.state.followSystemAppearance = true

        assertManualChoice(DARK_THEME, isDark = true)
        assertManualChoice(LIGHT_THEME, isDark = false)
    }

    @Test
    fun `automatic callback preserves a newer choice and clears the guard for the next manual callback`() {
        settings.state.followSystemAppearance = true
        settings.state.lastDarkAppearanceTheme = DARK_ISLANDS_THEME
        systemAppearance = Appearance.DARK
        currentThemeName = MIRAGE_THEME

        onEdt { syncService.syncIfNeeded() }

        assertTrue(syncService.programmaticSwitch)
        assertEquals(1, pendingApplies.size)

        settings.state.lastDarkAppearanceTheme = MIRAGE_ISLANDS_THEME
        val stateAfterUserEdit = encodedState()
        drainNextApply()

        assertEquals(stateAfterUserEdit, encodedState())
        assertFalse(syncService.programmaticSwitch)

        val expectedAfterManualCallback = cloneState()
        expectedAfterManualCallback.lastDarkAppearanceTheme = DARK_THEME
        emitManualCallback(DARK_THEME)
        assertEquals(encodedState(expectedAfterManualCallback), encodedState())
    }

    @Test
    fun `disable reload and re-enable preserve customizations while gating manual recording`() {
        settings.state.followSystemAppearance = true
        val expectedInitiallyEnabled = cloneState()
        expectedInitiallyEnabled.lastDarkAppearanceTheme = DARK_THEME
        emitManualCallback(DARK_THEME)
        assertEquals(encodedState(expectedInitiallyEnabled), encodedState())

        settings.state.followSystemAppearance = false
        val disabledState = encodedState()
        reloadServices(disabledState)
        assertEquals(disabledState, encodedState())
        emitManualCallback(LIGHT_THEME)
        assertEquals(disabledState, encodedState())

        settings.state.followSystemAppearance = true
        val enabledBeforeCallback = cloneState()
        enabledBeforeCallback.lastLightAppearanceTheme = LIGHT_THEME
        emitManualCallback(LIGHT_THEME)
        val enabledState = encodedState()
        assertEquals(encodedState(enabledBeforeCallback), enabledState)

        reloadServices(enabledState)
        assertEquals(enabledState, encodedState())
        assertEquals(FUTURE_CUSTOMIZATION, settings.state.fontPresetCustomizations[FUTURE_PRESET])
        assertEquals(LIGHT_THEME, settings.state.lastLightAppearanceTheme)
    }

    @Test
    fun `non Ayu callbacks preserve remembered themes and customizations in both external modes`() {
        for (externalEnhancementsEnabled in listOf(false, true)) {
            settings.state.externalThemeEnhancementsEnabled = externalEnhancementsEnabled
            val expectedState = encodedState()

            emitManualCallback("Darcula")

            assertEquals(expectedState, encodedState())
        }
    }

    @Test
    fun `partial native apply preserves settings continues later surfaces and recovers on the next callback`() {
        settings.state.followSystemAppearance = true
        failNextFontApply = true
        val expectedAfterFailure = cloneState()
        expectedAfterFailure.lastDarkAppearanceTheme = DARK_THEME

        emitManualCallback(DARK_THEME)

        assertEquals(encodedState(expectedAfterFailure), encodedState())
        assertEquals(emptyList(), completedFontThemes)
        assertEquals(listOf(DARK_THEME), completedVcsThemes)

        val expectedAfterRecovery = cloneState()
        expectedAfterRecovery.lastDarkAppearanceTheme = MIRAGE_THEME
        emitManualCallback(MIRAGE_THEME)

        assertEquals(encodedState(expectedAfterRecovery), encodedState())
        assertEquals(listOf(MIRAGE_THEME), completedFontThemes)
        assertEquals(listOf(DARK_THEME, MIRAGE_THEME), completedVcsThemes)
    }

    private fun assertManualChoice(
        themeName: String,
        isDark: Boolean,
    ) {
        val expected = cloneState()
        if (isDark) {
            expected.lastDarkAppearanceTheme = themeName
        } else {
            expected.lastLightAppearanceTheme = themeName
        }

        emitManualCallback(themeName)

        assertEquals(encodedState(expected), encodedState())
        reloadServices(encodedState())
        assertEquals(encodedState(expected), encodedState())
    }

    private fun emitManualCallback(themeName: String) {
        onEdt {
            currentThemeName = themeName
            listener.lookAndFeelChanged(lafManager)
        }
    }

    private fun drainNextApply() {
        onEdt { pendingApplies.removeFirst().invoke() }
    }

    private fun onEdt(action: () -> Unit) {
        SwingUtilities.invokeAndWait(action)
    }

    private fun encodedState(): String = encodedState(settings.state)

    private fun encodedState(state: AyuIslandsState): String = JDOMUtil.writeElement(XmlSerializer.serialize(state))

    private fun cloneState(): AyuIslandsState =
        XmlSerializer.deserialize(XmlSerializer.serialize(settings.state), AyuIslandsState::class.java)

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
            state.lastDarkAppearanceTheme = MIRAGE_ISLANDS_THEME
            state.lastLightAppearanceTheme = LIGHT_ISLANDS_THEME
            state.glowEnabled = true
            state.glowStyle = "SHARP_NEON"
            state.sharpNeonIntensity = 73
            state.sharpNeonWidth = 17
            state.fontPresetEnabled = true
            state.fontPresetName = "CUSTOM"
            state.fontApplyToConsole = true
            state.fontPresetCustomizations[FUTURE_PRESET] = FUTURE_CUSTOMIZATION
            state.lastApplyOk = true
        }

    private companion object {
        const val MIRAGE_THEME = "Ayu Mirage"
        const val DARK_THEME = "Ayu Dark"
        const val LIGHT_THEME = "Ayu Light"
        const val MIRAGE_ISLANDS_THEME = "Ayu Mirage (Islands UI)"
        const val DARK_ISLANDS_THEME = "Ayu Dark (Islands UI)"
        const val LIGHT_ISLANDS_THEME = "Ayu Light (Islands UI)"
        const val FUTURE_PRESET = "FUTURE_PRESET"
        const val FUTURE_CUSTOMIZATION =
            "21.00|1.37|true|FUTURE_WEIGHT|Unavailable Font|extension=42"
    }
}
