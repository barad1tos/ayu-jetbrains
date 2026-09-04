package dev.ayuislands.licensing

import com.intellij.notification.Notification
import com.intellij.notification.NotificationGroup
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.Application
import com.intellij.openapi.application.ApplicationManager
import dev.ayuislands.accent.AccentApplicator
import dev.ayuislands.accent.AccentApplyOutcome
import dev.ayuislands.accent.AccentHex
import dev.ayuislands.accent.AyuVariant
import dev.ayuislands.glow.GlowOverlayManager
import dev.ayuislands.rotation.AccentRotationService
import dev.ayuislands.settings.AyuIslandsSettings
import dev.ayuislands.settings.AyuIslandsState
import dev.ayuislands.vcs.VcsColorApplier
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.runs
import io.mockk.unmockkAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * Persistence lock for the Quick Switcher preference across a runtime-only downgrade.
 */
class LicenseCheckerQuickSwitcherRevertTest {
    private lateinit var state: AyuIslandsState
    private lateinit var settings: AyuIslandsSettings

    @BeforeEach
    fun setUp() {
        state = AyuIslandsState()
        // Stubbed applies report success, so the persisted clean flag must read
        // clean too — ThemeReapplication's tear-escalation check consults it.
        state.lastApplyOk = true
        settings = mockk()
        every { settings.state } returns state
        every { settings.getAccentForVariant(any()) } returns "#FFCC66"

        mockkObject(AyuIslandsSettings.Companion)
        every { AyuIslandsSettings.getInstance() } returns settings

        mockkObject(AccentApplicator)
        every { AccentApplicator.applyFromHexString(any()) } answers
            {
                AccentHex.of(firstArg<String>())?.let { validatedHex -> AccentApplyOutcome.Applied(validatedHex) }
                    ?: AccentApplyOutcome.Rejected(firstArg())
            }

        mockkObject(GlowOverlayManager.Companion)
        every { GlowOverlayManager.syncGlowForAllProjects() } just runs

        mockkObject(VcsColorApplier)
        every { VcsColorApplier.revertAll() } just runs

        mockkStatic(ApplicationManager::class)
        val app = mockk<Application>()
        every { ApplicationManager.getApplication() } returns app
        every { app.isDispatchThread } returns true
        val rotationService = mockk<AccentRotationService>(relaxed = true)
        every { app.getService(AccentRotationService::class.java) } returns rotationService

        mockkStatic(NotificationGroupManager::class)
        val ngm = mockk<NotificationGroupManager>()
        val group = mockk<NotificationGroup>()
        val notification = mockk<Notification>(relaxed = true)
        every { NotificationGroupManager.getInstance() } returns ngm
        every { ngm.getNotificationGroup(any()) } returns group
        every {
            group.createNotification(any<String>(), any<String>(), any<NotificationType>())
        } returns notification
        every { notification.notify(any()) } just runs
    }

    @AfterEach
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `revertToFreeDefaults preserves a hidden Quick Switcher preference`() {
        state.quickSwitcherWidgetEnabled = false
        assertEquals(false, state.quickSwitcherWidgetEnabled)

        LicenseChecker.revertToFreeDefaults(AyuVariant.MIRAGE)

        assertEquals(
            false,
            state.quickSwitcherWidgetEnabled,
            "A temporary downgrade must not overwrite the saved widget preference",
        )
    }

    @Test
    fun `revertToFreeDefaults preserves a visible Quick Switcher preference`() {
        state.quickSwitcherWidgetEnabled = true
        LicenseChecker.revertToFreeDefaults(AyuVariant.MIRAGE)
        assertEquals(true, state.quickSwitcherWidgetEnabled)
    }
}
