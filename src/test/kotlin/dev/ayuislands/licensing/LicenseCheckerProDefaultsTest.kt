package dev.ayuislands.licensing

import com.intellij.notification.NotificationGroup
import com.intellij.notification.NotificationGroupManager
import com.intellij.openapi.application.Application
import com.intellij.openapi.application.ApplicationManager
import dev.ayuislands.accent.AccentApplicator
import dev.ayuislands.accent.AccentElementId
import dev.ayuislands.accent.AyuVariant
import dev.ayuislands.glow.GlowAnimation
import dev.ayuislands.glow.GlowOverlayManager
import dev.ayuislands.glow.GlowPreset
import dev.ayuislands.glow.GlowStyle
import dev.ayuislands.rotation.AccentRotationService
import dev.ayuislands.settings.AyuIslandsSettings
import dev.ayuislands.settings.AyuIslandsState
import dev.ayuislands.settings.PanelWidthMode
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.runs
import io.mockk.unmockkAll
import io.mockk.verify
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LicenseCheckerProDefaultsTest {
    private lateinit var state: AyuIslandsState

    @BeforeTest
    fun setUp() {
        state = AyuIslandsState()
        val settingsMock = mockk<AyuIslandsSettings>()
        mockkObject(AyuIslandsSettings.Companion)
        every { AyuIslandsSettings.getInstance() } returns settingsMock
        every { settingsMock.state } returns state
    }

    @AfterTest
    fun tearDown() {
        unmockkAll()
    }

    // enableProDefaults — glow settings

    @Test
    fun `enableProDefaults enables glow`() {
        assertFalse(state.glowEnabled)
        LicenseChecker.enableProDefaults()
        assertTrue(state.glowEnabled)
    }

    @Test
    fun `enableProDefaults sets glow style to SHARP_NEON`() {
        LicenseChecker.enableProDefaults()
        assertEquals(GlowStyle.SHARP_NEON.name, state.glowStyle)
    }

    @Test
    fun `enableProDefaults sets glow preset to CUSTOM`() {
        LicenseChecker.enableProDefaults()
        assertEquals(GlowPreset.CUSTOM.name, state.glowPreset)
    }

    @Test
    fun `enableProDefaults sets animation to BREATHE`() {
        LicenseChecker.enableProDefaults()
        assertEquals(GlowAnimation.BREATHE.name, state.glowAnimation)
    }

    @Test
    fun `enableProDefaults sets intensity to 100 and width to 2`() {
        LicenseChecker.enableProDefaults()
        assertEquals(100, state.sharpNeonIntensity)
        assertEquals(2, state.sharpNeonWidth)
    }

    @Test
    fun `enableProDefaults sets all island toggles to true`() {
        state.glowEditor = false
        state.glowProject = false
        state.glowTerminal = false
        state.glowRun = false
        state.glowDebug = false
        state.glowGit = false
        state.glowServices = false

        LicenseChecker.enableProDefaults()

        assertTrue(state.glowEditor)
        assertTrue(state.glowProject)
        assertTrue(state.glowTerminal)
        assertTrue(state.glowRun)
        assertTrue(state.glowDebug)
        assertTrue(state.glowGit)
        assertTrue(state.glowServices)
    }

    @Test
    fun `enableProDefaults enables focus ring`() {
        state.glowFocusRing = false
        LicenseChecker.enableProDefaults()
        assertTrue(state.glowFocusRing)
    }

    @Test
    fun `enableProDefaults sets proDefaultsApplied flag`() {
        assertFalse(state.proDefaultsApplied)
        LicenseChecker.enableProDefaults()
        assertTrue(state.proDefaultsApplied)
    }

    // enableProDefaults does NOT set workspace defaults (b22c629: prevent overwriting customizations)

    @Test
    fun `enableProDefaults does not change panel width modes`() {
        assertEquals(PanelWidthMode.DEFAULT.name, state.projectPanelWidthMode)
        LicenseChecker.enableProDefaults()
        assertEquals(PanelWidthMode.DEFAULT.name, state.projectPanelWidthMode)
    }

    @Test
    fun `enableProDefaults does not change project root path or scrollbar`() {
        assertFalse(state.hideProjectRootPath)
        assertFalse(state.hideProjectViewHScrollbar)
        LicenseChecker.enableProDefaults()
        assertFalse(state.hideProjectRootPath)
        assertFalse(state.hideProjectViewHScrollbar)
    }

    @Test
    fun `enableProDefaults does not set workspaceDefaultsApplied`() {
        assertFalse(state.workspaceDefaultsApplied)
        LicenseChecker.enableProDefaults()
        assertFalse(state.workspaceDefaultsApplied)
    }

    // applyWorkspaceDefaults (standalone)

    @Test
    fun `applyWorkspaceDefaults sets all three panel modes to AUTO_FIT`() {
        LicenseChecker.applyWorkspaceDefaults()

        assertEquals(PanelWidthMode.AUTO_FIT.name, state.projectPanelWidthMode)
        assertEquals(PanelWidthMode.AUTO_FIT.name, state.commitPanelWidthMode)
        assertEquals(PanelWidthMode.AUTO_FIT.name, state.gitPanelWidthMode)
    }

    @Test
    fun `applyWorkspaceDefaults hides root path and scrollbar`() {
        LicenseChecker.applyWorkspaceDefaults()

        assertTrue(state.hideProjectRootPath)
        assertTrue(state.hideProjectViewHScrollbar)
    }

    @Test
    fun `applyWorkspaceDefaults sets workspaceDefaultsApplied flag`() {
        LicenseChecker.applyWorkspaceDefaults()
        assertTrue(state.workspaceDefaultsApplied)
    }

    // revertToFreeDefaults

    @Test
    fun `revertToFreeDefaults disables glow`() {
        state.glowEnabled = true
        mockAccentApplicator()

        LicenseChecker.revertToFreeDefaults(AyuVariant.MIRAGE)

        assertFalse(state.glowEnabled)
    }

    @Test
    fun `revertToFreeDefaults resets tab mode to MINIMAL`() {
        state.glowTabMode = "FULL"
        mockAccentApplicator()

        LicenseChecker.revertToFreeDefaults(AyuVariant.MIRAGE)

        assertEquals("MINIMAL", state.glowTabMode)
    }

    @Test
    fun `revertToFreeDefaults resets all element toggles to true`() {
        for (id in AccentElementId.entries) {
            state.setToggle(id, false)
        }
        mockAccentApplicator()

        LicenseChecker.revertToFreeDefaults(AyuVariant.MIRAGE)

        for (id in AccentElementId.entries) {
            assertTrue(state.isToggleEnabled(id), "${id.name} should be reset to true")
        }
    }

    @Test
    fun `revertToFreeDefaults resets all workspace settings to defaults`() {
        state.projectPanelWidthMode = PanelWidthMode.AUTO_FIT.name
        state.commitPanelWidthMode = PanelWidthMode.AUTO_FIT.name
        state.gitPanelWidthMode = PanelWidthMode.AUTO_FIT.name
        state.hideProjectRootPath = true
        state.hideProjectViewHScrollbar = true
        mockAccentApplicator()

        LicenseChecker.revertToFreeDefaults(AyuVariant.MIRAGE)

        assertEquals(PanelWidthMode.DEFAULT.name, state.projectPanelWidthMode)
        assertEquals(PanelWidthMode.DEFAULT.name, state.commitPanelWidthMode)
        assertEquals(PanelWidthMode.DEFAULT.name, state.gitPanelWidthMode)
        assertFalse(state.hideProjectRootPath)
        assertFalse(state.hideProjectViewHScrollbar)
    }

    @Test
    fun `revertToFreeDefaults preserves accent color`() {
        state.mirageAccent = "#CUSTOM1"
        mockAccentApplicator()

        LicenseChecker.revertToFreeDefaults(AyuVariant.MIRAGE)

        assertEquals("#CUSTOM1", state.mirageAccent)
    }

    @Test
    fun `revertToFreeDefaults resets cgpIntegrationEnabled to false`() {
        state.cgpIntegrationEnabled = true
        mockAccentApplicator()

        LicenseChecker.revertToFreeDefaults(AyuVariant.MIRAGE)

        assertFalse(state.cgpIntegrationEnabled)
    }

    @Test
    fun `revertToFreeDefaults resets irIntegrationEnabled to false`() {
        state.irIntegrationEnabled = true
        mockAccentApplicator()

        LicenseChecker.revertToFreeDefaults(AyuVariant.MIRAGE)

        assertFalse(state.irIntegrationEnabled)
    }

    @Test
    fun `revertToFreeDefaults resets accentRotationEnabled to false`() {
        state.accentRotationEnabled = true
        mockAccentApplicator()

        LicenseChecker.revertToFreeDefaults(AyuVariant.MIRAGE)

        assertFalse(state.accentRotationEnabled)
    }

    @Test
    fun `revertToFreeDefaults stops accent rotation service`() {
        mockAccentApplicator()
        val rotationService = mockk<AccentRotationService>(relaxed = true)
        val app = mockk<Application>()
        every { ApplicationManager.getApplication() } returns app
        every { app.getService(AccentRotationService::class.java) } returns rotationService

        LicenseChecker.revertToFreeDefaults(AyuVariant.MIRAGE)

        verify { rotationService.stopRotation() }
    }

    // Fragile: this test mocks a 4-deep notification chain
    // (NotificationGroupManager→NotificationGroup→Notification→notify).
    // If the notification path in revertToFreeDefaults changes, update
    // the mock setup below rather than deleting the test — it covers
    // an important error-handling path (AccentApplicator crash recovery).
    @Test
    fun `revertToFreeDefaults handles AccentApplicator failure gracefully`() {
        val settingsMock = mockk<AyuIslandsSettings>()
        every { AyuIslandsSettings.getInstance() } returns settingsMock
        every { settingsMock.state } returns state
        every { settingsMock.getAccentForVariant(any()) } returns "#FFCC66"
        mockkObject(AccentApplicator)
        every { AccentApplicator.apply(any()) } throws RuntimeException("Test failure")

        // Mock ApplicationManager for AccentRotationService.stopRotation() call
        mockkStatic(ApplicationManager::class)
        val app = mockk<Application>()
        every { ApplicationManager.getApplication() } returns app
        every { app.getService(AccentRotationService::class.java) } returns null

        // Mock GlowOverlayManager.syncGlowForAllProjects()
        mockkObject(GlowOverlayManager.Companion)
        every { GlowOverlayManager.syncGlowForAllProjects() } just runs

        // Mock NotificationGroupManager for the catch block's warning notification
        mockkStatic(NotificationGroupManager::class)
        val notificationManager = mockk<NotificationGroupManager>()
        val notificationGroup = mockk<NotificationGroup>()
        every { NotificationGroupManager.getInstance() } returns notificationManager
        every { notificationManager.getNotificationGroup(any()) } returns notificationGroup
        val notification = mockk<com.intellij.notification.Notification>(relaxed = true)
        every {
            notificationGroup.createNotification(
                any<String>(),
                any<String>(),
                any<com.intellij.notification.NotificationType>(),
            )
        } returns notification
        every { notification.notify(any()) } just runs

        // Should not throw — exception is caught internally
        LicenseChecker.revertToFreeDefaults(AyuVariant.MIRAGE)

        // State changes still applied before the accent re-apply
        assertFalse(state.glowEnabled)
    }

    private fun mockAccentApplicator() {
        val settingsMock = mockk<AyuIslandsSettings>()
        every { AyuIslandsSettings.getInstance() } returns settingsMock
        every { settingsMock.state } returns state
        every { settingsMock.getAccentForVariant(any()) } returns "#FFCC66"
        mockkObject(AccentApplicator)
        every { AccentApplicator.apply(any()) } just runs

        // Mock ApplicationManager for AccentRotationService.stopRotation() call
        mockkStatic(ApplicationManager::class)
        val app = mockk<Application>()
        every { ApplicationManager.getApplication() } returns app
        every { app.getService(AccentRotationService::class.java) } returns null

        // Mock GlowOverlayManager.syncGlowForAllProjects()
        mockkObject(GlowOverlayManager.Companion)
        every { GlowOverlayManager.syncGlowForAllProjects() } just runs
    }
}
