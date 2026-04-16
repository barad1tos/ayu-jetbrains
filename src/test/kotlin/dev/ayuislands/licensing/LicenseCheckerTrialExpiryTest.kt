package dev.ayuislands.licensing

import com.intellij.notification.Notification
import com.intellij.notification.NotificationGroup
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.ui.LicensingFacade
import dev.ayuislands.settings.AyuIslandsSettings
import dev.ayuislands.settings.AyuIslandsState
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import java.time.LocalDate
import java.time.ZoneId
import java.util.Date
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LicenseCheckerTrialExpiryTest {
    private lateinit var state: AyuIslandsState
    private lateinit var settings: AyuIslandsSettings
    private lateinit var facade: LicensingFacade
    private lateinit var notificationGroup: NotificationGroup
    private lateinit var notification: Notification

    @BeforeTest
    fun setUp() {
        state = AyuIslandsState()
        settings = mockk<AyuIslandsSettings>()
        every { settings.state } returns state

        mockkObject(AyuIslandsSettings.Companion)
        every { AyuIslandsSettings.getInstance() } returns settings

        facade = mockk<LicensingFacade>()
        mockkStatic(LicensingFacade::class)

        // Disable dev build shortcut
        System.clearProperty("ayu.islands.dev")

        // Notification mocks
        notification = mockk<Notification>(relaxed = true)
        every { notification.addAction(any()) } returns notification

        notificationGroup = mockk<NotificationGroup>()
        every {
            notificationGroup.createNotification(any<String>(), any<String>(), any<NotificationType>())
        } returns notification

        val groupManager = mockk<NotificationGroupManager>()
        every { groupManager.getNotificationGroup("Ayu Islands") } returns notificationGroup
        mockkStatic(NotificationGroupManager::class)
        every { NotificationGroupManager.getInstance() } returns groupManager
    }

    @AfterTest
    fun tearDown() {
        unmockkAll()
    }

    // --- getTrialDaysRemaining tests ---

    @Test
    fun `returns days remaining when trial active with 3 days left`() {
        every { LicensingFacade.getInstance() } returns facade
        every { facade.isEvaluationLicense } returns true
        every { facade.getExpirationDate(LicenseChecker.PRODUCT_CODE) } returns dateFromNow(3)

        val result = LicenseChecker.getTrialDaysRemaining()

        assertEquals(3L, result)
    }

    @Test
    fun `returns days remaining when trial active with 7 days left`() {
        every { LicensingFacade.getInstance() } returns facade
        every { facade.isEvaluationLicense } returns true
        every { facade.getExpirationDate(LicenseChecker.PRODUCT_CODE) } returns dateFromNow(7)

        val result = LicenseChecker.getTrialDaysRemaining()

        assertEquals(7L, result)
    }

    @Test
    fun `returns 0 when trial expires today`() {
        every { LicensingFacade.getInstance() } returns facade
        every { facade.isEvaluationLicense } returns true
        every { facade.getExpirationDate(LicenseChecker.PRODUCT_CODE) } returns dateFromNow(0)

        val result = LicenseChecker.getTrialDaysRemaining()

        assertEquals(0L, result)
    }

    @Test
    fun `returns null when facade is null`() {
        every { LicensingFacade.getInstance() } returns null

        val result = LicenseChecker.getTrialDaysRemaining()

        assertNull(result)
    }

    @Test
    fun `returns null when not evaluation license`() {
        every { LicensingFacade.getInstance() } returns facade
        every { facade.isEvaluationLicense } returns false

        val result = LicenseChecker.getTrialDaysRemaining()

        assertNull(result)
    }

    @Test
    fun `returns null when expiration date is null`() {
        every { LicensingFacade.getInstance() } returns facade
        every { facade.isEvaluationLicense } returns true
        every { facade.getExpirationDate(LicenseChecker.PRODUCT_CODE) } returns null

        val result = LicenseChecker.getTrialDaysRemaining()

        assertNull(result)
    }

    @Test
    fun `returns null when trial already expired`() {
        every { LicensingFacade.getInstance() } returns facade
        every { facade.isEvaluationLicense } returns true
        every { facade.getExpirationDate(LicenseChecker.PRODUCT_CODE) } returns dateFromNow(-1)

        val result = LicenseChecker.getTrialDaysRemaining()

        assertNull(result)
    }

    // --- checkTrialExpiryWarning tests ---

    @Test
    fun `shows warning at 7 days when not yet warned`() {
        every { LicensingFacade.getInstance() } returns facade
        every { facade.isEvaluationLicense } returns true
        every { facade.getExpirationDate(LicenseChecker.PRODUCT_CODE) } returns dateFromNow(7)
        state.trialExpiryWarningShown = false

        LicenseChecker.checkTrialExpiryWarning(null)

        verify(exactly = 1) {
            notificationGroup.createNotification(
                match { it.contains("7 days remaining") },
                match { it.contains("Glow, accent toggles, auto-fit, and plugin sync") },
                NotificationType.INFORMATION,
            )
        }
    }

    @Test
    fun `shows warning at 3 days when 7-day already shown`() {
        every { LicensingFacade.getInstance() } returns facade
        every { facade.isEvaluationLicense } returns true
        every { facade.getExpirationDate(LicenseChecker.PRODUCT_CODE) } returns dateFromNow(3)
        state.trialExpiryWarningShown = true
        state.trialExpiry3DayWarningShown = false

        LicenseChecker.checkTrialExpiryWarning(null)

        verify(exactly = 1) {
            notificationGroup.createNotification(
                match { it.contains("3 days remaining") },
                match { it.contains("Glow, accent toggles, auto-fit, and plugin sync") },
                NotificationType.INFORMATION,
            )
        }
    }

    @Test
    fun `skips warning when days greater than 7`() {
        every { LicensingFacade.getInstance() } returns facade
        every { facade.isEvaluationLicense } returns true
        every { facade.getExpirationDate(LicenseChecker.PRODUCT_CODE) } returns dateFromNow(10)

        LicenseChecker.checkTrialExpiryWarning(null)

        verify(exactly = 0) {
            notificationGroup.createNotification(any<String>(), any<String>(), any<NotificationType>())
        }
    }

    @Test
    fun `skips warning when not on trial`() {
        every { LicensingFacade.getInstance() } returns facade
        every { facade.isEvaluationLicense } returns false

        LicenseChecker.checkTrialExpiryWarning(null)

        verify(exactly = 0) {
            notificationGroup.createNotification(any<String>(), any<String>(), any<NotificationType>())
        }
    }

    @Test
    fun `skips 3-day warning when already shown`() {
        every { LicensingFacade.getInstance() } returns facade
        every { facade.isEvaluationLicense } returns true
        every { facade.getExpirationDate(LicenseChecker.PRODUCT_CODE) } returns dateFromNow(2)
        state.trialExpiryWarningShown = true
        state.trialExpiry3DayWarningShown = true

        LicenseChecker.checkTrialExpiryWarning(null)

        verify(exactly = 0) {
            notificationGroup.createNotification(any<String>(), any<String>(), any<NotificationType>())
        }
    }

    // Multi-session user journeys for the two-stage trial warning.
    // Each "day" is one user-visible session. State persists across sessions
    // (it is what would live on disk), the clock is mocked day by day.

    @Test
    fun `two-stage warning progression over the final week of trial`() {
        every { LicensingFacade.getInstance() } returns facade
        every { facade.isEvaluationLicense } returns true

        // Day 8: outside the window, no notification
        every { facade.getExpirationDate(LicenseChecker.PRODUCT_CODE) } returns dateFromNow(8)
        LicenseChecker.checkTrialExpiryWarning(null)

        // Day 7: first warning fires and the 7-day flag is latched
        every { facade.getExpirationDate(LicenseChecker.PRODUCT_CODE) } returns dateFromNow(7)
        LicenseChecker.checkTrialExpiryWarning(null)

        // Days 6, 5, 4: still within the 7-day window but flag prevents re-firing
        for (daysRemaining in listOf(6L, 5L, 4L)) {
            every { facade.getExpirationDate(LicenseChecker.PRODUCT_CODE) } returns dateFromNow(daysRemaining)
            LicenseChecker.checkTrialExpiryWarning(null)
        }

        // Day 3: second (3-day) warning fires
        every { facade.getExpirationDate(LicenseChecker.PRODUCT_CODE) } returns dateFromNow(3)
        LicenseChecker.checkTrialExpiryWarning(null)

        // Days 2, 1: both flags latched, silence
        for (daysRemaining in listOf(2L, 1L)) {
            every { facade.getExpirationDate(LicenseChecker.PRODUCT_CODE) } returns dateFromNow(daysRemaining)
            LicenseChecker.checkTrialExpiryWarning(null)
        }

        // Exactly two notifications total across the whole week
        verify(exactly = 2) {
            notificationGroup.createNotification(any<String>(), any<String>(), any<NotificationType>())
        }
        // Both latches set
        assertEquals(true, state.trialExpiryWarningShown)
        assertEquals(true, state.trialExpiry3DayWarningShown)
        // Each threshold surfaced its own message
        verify(exactly = 1) {
            notificationGroup.createNotification(
                match { it.contains("7 days remaining") },
                any<String>(),
                NotificationType.INFORMATION,
            )
        }
        verify(exactly = 1) {
            notificationGroup.createNotification(
                match { it.contains("3 days remaining") },
                any<String>(),
                NotificationType.INFORMATION,
            )
        }
    }

    @Test
    fun `7-day latch does not block the later 3-day warning`() {
        every { LicensingFacade.getInstance() } returns facade
        every { facade.isEvaluationLicense } returns true
        // User already saw the 7-day warning in a previous session
        state.trialExpiryWarningShown = true
        state.trialExpiry3DayWarningShown = false

        // Session resumes on day 3 — 3-day warning must still fire
        every { facade.getExpirationDate(LicenseChecker.PRODUCT_CODE) } returns dateFromNow(3)
        LicenseChecker.checkTrialExpiryWarning(null)

        verify(exactly = 1) {
            notificationGroup.createNotification(
                match { it.contains("3 days remaining") },
                any<String>(),
                NotificationType.INFORMATION,
            )
        }
        assertEquals(true, state.trialExpiry3DayWarningShown)
    }

    @Test
    fun `both latches set means silence regardless of day`() {
        every { LicensingFacade.getInstance() } returns facade
        every { facade.isEvaluationLicense } returns true
        state.trialExpiryWarningShown = true
        state.trialExpiry3DayWarningShown = true

        for (daysRemaining in listOf(7L, 5L, 3L, 1L, 0L)) {
            every { facade.getExpirationDate(LicenseChecker.PRODUCT_CODE) } returns dateFromNow(daysRemaining)
            LicenseChecker.checkTrialExpiryWarning(null)
        }

        verify(exactly = 0) {
            notificationGroup.createNotification(any<String>(), any<String>(), any<NotificationType>())
        }
    }

    @Test
    fun `trial warning stays idempotent across restarts on the same day`() {
        every { LicensingFacade.getInstance() } returns facade
        every { facade.isEvaluationLicense } returns true
        every { facade.getExpirationDate(LicenseChecker.PRODUCT_CODE) } returns dateFromNow(7)

        // Session 1: day 7, warning fires
        LicenseChecker.checkTrialExpiryWarning(null)
        // Session 2: same persisted state, same day — must stay silent
        LicenseChecker.checkTrialExpiryWarning(null)
        // Session 3: one more restart, still the same day
        LicenseChecker.checkTrialExpiryWarning(null)

        verify(exactly = 1) {
            notificationGroup.createNotification(any<String>(), any<String>(), any<NotificationType>())
        }
        assertEquals(true, state.trialExpiryWarningShown)
    }

    /**
     * Create a [Date] representing [daysFromNow] days in the future (or past if negative).
     *
     * Anchors the base date in UTC, not the JVM's system zone. Production
     * [LicenseChecker.getTrialDaysRemaining] computes the day diff as
     * `DAYS.between(LocalDate.now(UTC), expirationDay)`, so the fixture must use the same
     * zone — otherwise, during the few hours each day when the system zone and UTC are on
     * different calendar days (Kyiv/EEST 00:00–03:00 local ≈ previous-day 21:00–00:00 UTC),
     * `dateFromNow(N)` yields `N+1` days under production's UTC-anchored calculation and
     * every day-sensitive assertion fails off-by-one.
     */
    private fun dateFromNow(daysFromNow: Long): Date {
        val localDate = LocalDate.now(ZoneId.of("UTC")).plusDays(daysFromNow)
        val instant = localDate.atStartOfDay(ZoneId.of("UTC")).toInstant()
        return Date.from(instant)
    }
}
