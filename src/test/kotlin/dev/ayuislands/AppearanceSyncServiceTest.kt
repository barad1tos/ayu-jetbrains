@file:Suppress("UnstableApiUsage")

package dev.ayuislands

import com.intellij.ide.ui.LafManager
import com.intellij.ide.ui.laf.UIThemeLookAndFeelInfo
import dev.ayuislands.accent.AyuVariant
import dev.ayuislands.accent.SystemAppearanceProvider
import dev.ayuislands.accent.SystemAppearanceProvider.Appearance
import dev.ayuislands.settings.AyuIslandsSettings
import dev.ayuislands.settings.AyuIslandsState
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import javax.swing.SwingUtilities
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AppearanceSyncServiceTest {
    private lateinit var service: AppearanceSyncService
    private lateinit var settings: AyuIslandsSettings
    private lateinit var state: AyuIslandsState
    private lateinit var lafManager: LafManager

    @BeforeTest
    fun setUp() {
        service = AppearanceSyncService()
        state = AyuIslandsState()
        settings = mockk(relaxed = true)
        every { settings.state } returns state

        lafManager = mockk(relaxed = true)

        mockkObject(SystemAppearanceProvider)
        mockkObject(AyuVariant.Companion)
        mockkObject(AyuIslandsSettings.Companion)
        mockkStatic(LafManager::class)
        mockkStatic(SwingUtilities::class)

        every { AyuIslandsSettings.getInstance() } returns settings
        every { LafManager.getInstance() } returns lafManager
        // Capture the Runnable passed to invokeLater and run it immediately
        every { SwingUtilities.invokeLater(any()) } answers {
            firstArg<Runnable>().run()
        }
    }

    @AfterTest
    fun tearDown() {
        unmockkAll()
    }

    // -- syncIfNeeded: early returns --

    @Test
    fun `syncIfNeeded does nothing when appearance is null`() {
        every { SystemAppearanceProvider.resolve() } returns null

        service.syncIfNeeded()

        assertFalse(service.programmaticSwitch)
    }

    @Test
    fun `syncIfNeeded does nothing when no Ayu theme active`() {
        every { SystemAppearanceProvider.resolve() } returns Appearance.DARK
        every { AyuVariant.detect() } returns null

        service.syncIfNeeded()

        assertFalse(service.programmaticSwitch)
    }

    @Test
    fun `syncIfNeeded does nothing when target theme name is null`() {
        every { SystemAppearanceProvider.resolve() } returns Appearance.DARK
        every { AyuVariant.detect() } returns AyuVariant.MIRAGE
        state.lastDarkThemeName = null

        service.syncIfNeeded()

        assertFalse(service.programmaticSwitch)
    }

    @Test
    fun `syncIfNeeded does nothing when appearance unchanged from last sync`() {
        val currentThemeLaf = mockk<UIThemeLookAndFeelInfo>(relaxed = true)
        every { currentThemeLaf.name } returns "Ayu Islands Mirage"
        every { lafManager.currentUIThemeLookAndFeel } returns currentThemeLaf

        val targetThemeLaf = mockk<UIThemeLookAndFeelInfo>(relaxed = true)
        every { targetThemeLaf.name } returns "Ayu Islands Dark"
        every { lafManager.installedThemes } returns sequenceOf(targetThemeLaf)

        every { SystemAppearanceProvider.resolve() } returns Appearance.DARK
        every { AyuVariant.detect() } returns AyuVariant.MIRAGE
        state.lastDarkThemeName = "Ayu Islands Dark"

        // The first call syncs successfully
        service.syncIfNeeded()
        assertTrue(service.programmaticSwitch)

        // Reset the flag to detect whether the second call does anything
        service.clearProgrammaticSwitch()

        // The second call with the same appearance should be a no-op (line 28 early return)
        service.syncIfNeeded()
        assertFalse(service.programmaticSwitch)
    }

    // -- syncIfNeeded: light appearance branch --

    @Test
    fun `syncIfNeeded uses lastLightThemeName for LIGHT appearance`() {
        val lightThemeName = "Ayu Islands Light (Islands UI)"
        state.lastLightThemeName = lightThemeName

        val currentThemeLaf = mockk<UIThemeLookAndFeelInfo>(relaxed = true)
        every { currentThemeLaf.name } returns "Ayu Islands Mirage"
        every { lafManager.currentUIThemeLookAndFeel } returns currentThemeLaf

        val targetThemeLaf = mockk<UIThemeLookAndFeelInfo>(relaxed = true)
        every { targetThemeLaf.name } returns lightThemeName
        every { lafManager.installedThemes } returns sequenceOf(targetThemeLaf)

        every { SystemAppearanceProvider.resolve() } returns Appearance.LIGHT
        every { AyuVariant.detect() } returns AyuVariant.MIRAGE

        service.syncIfNeeded()

        assertTrue(service.programmaticSwitch)
        verify { lafManager.setCurrentLookAndFeel(targetThemeLaf, true) }
    }

    @Test
    fun `syncIfNeeded returns when lastLightThemeName is null`() {
        state.lastLightThemeName = null

        every { SystemAppearanceProvider.resolve() } returns Appearance.LIGHT
        every { AyuVariant.detect() } returns AyuVariant.LIGHT

        service.syncIfNeeded()

        assertFalse(service.programmaticSwitch)
    }

    // -- switchToTheme via syncIfNeeded: full path --

    @Test
    fun `syncIfNeeded switches theme when all conditions met`() {
        val targetName = "Ayu Islands Dark (Islands UI)"
        state.lastDarkThemeName = targetName

        val currentThemeLaf = mockk<UIThemeLookAndFeelInfo>(relaxed = true)
        every { currentThemeLaf.name } returns "Ayu Islands Mirage"
        every { lafManager.currentUIThemeLookAndFeel } returns currentThemeLaf

        val targetThemeLaf = mockk<UIThemeLookAndFeelInfo>(relaxed = true)
        every { targetThemeLaf.name } returns targetName
        every { lafManager.installedThemes } returns sequenceOf(targetThemeLaf)

        every { SystemAppearanceProvider.resolve() } returns Appearance.DARK
        every { AyuVariant.detect() } returns AyuVariant.DARK

        service.syncIfNeeded()

        assertTrue(service.programmaticSwitch)
        verify { lafManager.setCurrentLookAndFeel(targetThemeLaf, true) }
        verify { lafManager.updateUI() }
    }

    // -- switchToTheme: the current theme already matches the target --

    @Test
    fun `switchToTheme does nothing when current theme matches target`() {
        val themeName = "Ayu Islands Mirage"
        state.lastDarkThemeName = themeName

        val currentThemeLaf = mockk<UIThemeLookAndFeelInfo>(relaxed = true)
        every { currentThemeLaf.name } returns themeName
        every { lafManager.currentUIThemeLookAndFeel } returns currentThemeLaf

        every { SystemAppearanceProvider.resolve() } returns Appearance.DARK
        every { AyuVariant.detect() } returns AyuVariant.MIRAGE

        service.syncIfNeeded()

        // programmaticSwitch should remain false because switchToTheme returns early
        assertFalse(service.programmaticSwitch)
        verify(exactly = 0) { lafManager.setCurrentLookAndFeel(any(), any<Boolean>()) }
    }

    // -- switchToTheme: target theme not found in installed themes --

    @Test
    fun `switchToTheme logs warning when target theme not installed`() {
        val targetName = "Ayu Islands Mirage (Islands UI)"
        state.lastDarkThemeName = targetName

        val currentThemeLaf = mockk<UIThemeLookAndFeelInfo>(relaxed = true)
        every { currentThemeLaf.name } returns "Ayu Islands Dark"
        every { lafManager.currentUIThemeLookAndFeel } returns currentThemeLaf

        // No matching theme in the installed list
        val otherThemeLaf = mockk<UIThemeLookAndFeelInfo>(relaxed = true)
        every { otherThemeLaf.name } returns "Some Other Theme"
        every { lafManager.installedThemes } returns sequenceOf(otherThemeLaf)

        every { SystemAppearanceProvider.resolve() } returns Appearance.DARK
        every { AyuVariant.detect() } returns AyuVariant.MIRAGE

        service.syncIfNeeded()

        // Should not set programmaticSwitch because the target was not found
        assertFalse(service.programmaticSwitch)
        verify(exactly = 0) { lafManager.setCurrentLookAndFeel(any(), any<Boolean>()) }
    }

    @Test
    fun `switchToTheme logs warning when installed themes list is empty`() {
        state.lastDarkThemeName = "Ayu Islands Mirage"

        val currentThemeLaf = mockk<UIThemeLookAndFeelInfo>(relaxed = true)
        every { currentThemeLaf.name } returns "Ayu Islands Dark"
        every { lafManager.currentUIThemeLookAndFeel } returns currentThemeLaf
        every { lafManager.installedThemes } returns emptySequence()

        every { SystemAppearanceProvider.resolve() } returns Appearance.DARK
        every { AyuVariant.detect() } returns AyuVariant.MIRAGE

        service.syncIfNeeded()

        assertFalse(service.programmaticSwitch)
        verify(exactly = 0) { lafManager.setCurrentLookAndFeel(any(), any<Boolean>()) }
    }

    // -- recordManualChoice --

    @Test
    fun `recordManualChoice stores dark theme name for Mirage`() {
        val themeName = "Ayu Islands Mirage"

        service.recordManualChoice(themeName)

        assertEquals(themeName, state.lastDarkThemeName)
    }

    @Test
    fun `recordManualChoice stores dark theme name for Dark variant`() {
        val themeName = "Ayu Islands Dark"

        service.recordManualChoice(themeName)

        assertEquals(themeName, state.lastDarkThemeName)
    }

    @Test
    fun `recordManualChoice stores dark theme name for Dark Islands UI variant`() {
        val themeName = "Ayu Islands Dark (Islands UI)"

        service.recordManualChoice(themeName)

        assertEquals(themeName, state.lastDarkThemeName)
    }

    @Test
    fun `recordManualChoice stores light theme name`() {
        val themeName = "Ayu Islands Light"

        service.recordManualChoice(themeName)

        assertEquals(themeName, state.lastLightThemeName)
    }

    @Test
    fun `recordManualChoice stores light Islands UI theme name`() {
        val themeName = "Ayu Islands Light (Islands UI)"

        service.recordManualChoice(themeName)

        assertEquals(themeName, state.lastLightThemeName)
    }

    @Test
    fun `recordManualChoice ignores non-Ayu themes`() {
        val originalDark = state.lastDarkThemeName
        val originalLight = state.lastLightThemeName

        service.recordManualChoice("Darcula")

        assertEquals(originalDark, state.lastDarkThemeName)
        assertEquals(originalLight, state.lastLightThemeName)
    }

    @Test
    fun `recordManualChoice does not modify light slot when storing dark theme`() {
        val originalLight = state.lastLightThemeName

        service.recordManualChoice("Ayu Islands Mirage (Islands UI)")

        assertEquals(originalLight, state.lastLightThemeName)
    }

    @Test
    fun `recordManualChoice does not modify dark slot when storing light theme`() {
        val originalDark = state.lastDarkThemeName

        service.recordManualChoice("Ayu Islands Light")

        assertEquals(originalDark, state.lastDarkThemeName)
    }

    // -- clearProgrammaticSwitch --

    @Test
    fun `clearProgrammaticSwitch resets flag`() {
        service.clearProgrammaticSwitch()

        assertFalse(service.programmaticSwitch)
    }

    @Test
    fun `clearProgrammaticSwitch resets flag after it was set by theme switch`() {
        val targetName = "Ayu Islands Dark"
        state.lastDarkThemeName = targetName

        val currentThemeLaf = mockk<UIThemeLookAndFeelInfo>(relaxed = true)
        every { currentThemeLaf.name } returns "Ayu Islands Mirage"
        every { lafManager.currentUIThemeLookAndFeel } returns currentThemeLaf

        val targetThemeLaf = mockk<UIThemeLookAndFeelInfo>(relaxed = true)
        every { targetThemeLaf.name } returns targetName
        every { lafManager.installedThemes } returns sequenceOf(targetThemeLaf)

        every { SystemAppearanceProvider.resolve() } returns Appearance.DARK
        every { AyuVariant.detect() } returns AyuVariant.MIRAGE

        service.syncIfNeeded()
        assertTrue(service.programmaticSwitch)

        service.clearProgrammaticSwitch()
        assertFalse(service.programmaticSwitch)
    }

    // -- programmaticSwitch initial state --

    @Test
    fun `programmaticSwitch is false initially`() {
        val freshService = AppearanceSyncService()
        assertFalse(freshService.programmaticSwitch)
    }

    // -- lastSyncedAppearance via reflection --

    @Test
    fun `syncIfNeeded does not update lastSyncedAppearance when no Ayu theme active`() {
        every { SystemAppearanceProvider.resolve() } returns Appearance.DARK
        every { AyuVariant.detect() } returns null

        service.syncIfNeeded()

        // Verify the private field was not set
        val lastSynced = getLastSyncedAppearance()
        assertNull(lastSynced)
    }

    @Test
    fun `syncIfNeeded updates lastSyncedAppearance on successful sync`() {
        val targetName = "Ayu Islands Dark"
        state.lastDarkThemeName = targetName

        val currentThemeLaf = mockk<UIThemeLookAndFeelInfo>(relaxed = true)
        every { currentThemeLaf.name } returns "Ayu Islands Mirage"
        every { lafManager.currentUIThemeLookAndFeel } returns currentThemeLaf

        val targetThemeLaf = mockk<UIThemeLookAndFeelInfo>(relaxed = true)
        every { targetThemeLaf.name } returns targetName
        every { lafManager.installedThemes } returns sequenceOf(targetThemeLaf)

        every { SystemAppearanceProvider.resolve() } returns Appearance.DARK
        every { AyuVariant.detect() } returns AyuVariant.MIRAGE

        service.syncIfNeeded()

        val lastSynced = getLastSyncedAppearance()
        assertEquals(Appearance.DARK, lastSynced)
    }

    // -- Helpers --

    private fun getLastSyncedAppearance(): Appearance? {
        val field = AppearanceSyncService::class.java.getDeclaredField("lastSyncedAppearance")
        field.isAccessible = true
        return field.get(service) as Appearance?
    }
}
