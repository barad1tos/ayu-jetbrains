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

    /** Create a [Date] representing [daysFromNow] days in the future (or past if negative). */
    private fun dateFromNow(daysFromNow: Long): Date {
        val localDate = LocalDate.now().plusDays(daysFromNow)
        val instant = localDate.atStartOfDay(ZoneId.of("UTC")).toInstant()
        return Date.from(instant)
    }
}
