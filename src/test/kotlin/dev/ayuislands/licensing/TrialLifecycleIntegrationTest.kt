package dev.ayuislands.licensing

import com.intellij.notification.Notification
import com.intellij.notification.NotificationGroup
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.Application
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.PathManager
import com.intellij.ui.LicensingFacade
import dev.ayuislands.accent.AccentApplicator
import dev.ayuislands.accent.AyuVariant
import dev.ayuislands.glow.GlowOverlayManager
import dev.ayuislands.rotation.AccentRotationService
import dev.ayuislands.settings.AyuIslandsSettings
import dev.ayuislands.settings.AyuIslandsState
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.runs
import io.mockk.unmockkAll
import io.mockk.verify
import java.time.LocalDate
import java.time.ZoneId
import java.util.Date
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * End-to-end trial lifecycle simulator: Day 0 (fresh install) through Day 45
 * (post-expiry re-purchase).
 *
 * Drives the public [LicenseChecker] surface as the startup activity would. Advances
 * a virtual "day" counter each step — instead of mocking wall-clock time, it mocks
 * `LicensingFacade.getExpirationDate` to return `now + daysRemaining`, and
 * mocks `getConfirmationStamp` / `isEvaluationLicense` to reflect the trial state.
 *
 * Real [LicenseChecker.getTrialDaysRemaining] anchors in `LocalDate.now(UTC)`, so
 * seeding expiration = `dateFromNow(daysRemaining)` yields exactly `daysRemaining`
 * — see the matching helper in [LicenseCheckerTrialExpiryTest.kt].
 *
 * Grace-window transitions (Day 31 + 49 h) switch to mocking `System.currentTimeMillis`
 * directly since that path does not hit the facade.
 */
class TrialLifecycleIntegrationTest {
    private lateinit var state: AyuIslandsState
    private lateinit var settings: AyuIslandsSettings
    private lateinit var facade: LicensingFacade
    private lateinit var notificationGroup: NotificationGroup
    private lateinit var notification: Notification

    // Deterministic clock anchor. Seeded in setUp and pushed through the
    // LicenseChecker.nowMsSupplier + todayUtcSupplier seams so grace-window /
    // trial-expiry checks do not race with the real wall clock.
    private var fakeNowMs: Long = 0L
    private lateinit var fakeTodayUtc: LocalDate

    @BeforeTest
    fun setUp() {
        state = AyuIslandsState()
        settings = mockk()
        every { settings.state } returns state
        every { settings.getAccentForVariant(any()) } returns "#FFCC66"

        mockkObject(AyuIslandsSettings.Companion)
        every { AyuIslandsSettings.getInstance() } returns settings

        mockkStatic(LicensingFacade::class)
        facade = mockk()
        every { LicensingFacade.getInstance() } returns facade

        mockkStatic(PathManager::class)
        every { PathManager.getConfigPath() } returns "/home/user/.config/JetBrains/IntelliJIdea2025.1"

        // Anchor UTC "today" at a stable date so dateFromNow and the trial-days
        // arithmetic are fully deterministic. fakeNowMs mirrors the same moment
        // (midnight UTC on the anchor day), which lets grace-window assertions
        // derive from the same reference frame as the expiration helper.
        fakeTodayUtc = LocalDate.of(2026, 4, 20)
        fakeNowMs = fakeTodayUtc.atStartOfDay(ZoneId.of("UTC")).toInstant().toEpochMilli()
        LicenseChecker.nowMsSupplier = { fakeNowMs }
        LicenseChecker.todayUtcSupplier = { fakeTodayUtc }

        System.clearProperty("ayu.islands.dev")

        mockkObject(AccentApplicator)
        every { AccentApplicator.apply(any()) } just runs

        mockkObject(GlowOverlayManager.Companion)
        every { GlowOverlayManager.syncGlowForAllProjects() } just runs

        mockkStatic(ApplicationManager::class)
        val app = mockk<Application>()
        every { ApplicationManager.getApplication() } returns app
        val rotationService = mockk<AccentRotationService>(relaxed = true)
        every { app.getService(AccentRotationService::class.java) } returns rotationService

        notification = mockk(relaxed = true)
        every { notification.addAction(any()) } returns notification
        notificationGroup = mockk()
        every {
            notificationGroup.createNotification(any<String>(), any<String>(), any<NotificationType>())
        } returns notification
        val ngm = mockk<NotificationGroupManager>()
        every { ngm.getNotificationGroup("Ayu Islands") } returns notificationGroup
        mockkStatic(NotificationGroupManager::class)
        every { NotificationGroupManager.getInstance() } returns ngm
    }

    @AfterTest
    fun tearDown() {
        LicenseChecker.nowMsSupplier = System::currentTimeMillis
        LicenseChecker.todayUtcSupplier = { LocalDate.now(ZoneId.of("UTC")) }
        unmockkAll()
    }

    private fun dateFromNow(offsetDays: Long): Date {
        val localDate = fakeTodayUtc.plusDays(offsetDays)
        return Date.from(localDate.atStartOfDay(ZoneId.of("UTC")).toInstant())
    }

    /** Advance to [day] of a 30-day trial. Day 0 = fresh install, Day 30 = expires today. */
    private fun advanceToTrialDay(day: Int) {
        val daysRemaining = (30 - day).toLong()
        every { facade.isEvaluationLicense } returns true
        every { facade.getConfirmationStamp(LicenseChecker.PRODUCT_CODE) } returns "eval:session-$day"
        every { facade.getExpirationDate(LicenseChecker.PRODUCT_CODE) } returns
            if (daysRemaining >= 0) dateFromNow(daysRemaining) else null
    }

    private fun advanceToPostTrial() {
        // Marketplace stops returning eval stamp; facade still reachable.
        every { facade.isEvaluationLicense } returns false
        every { facade.getConfirmationStamp(LicenseChecker.PRODUCT_CODE) } returns null
        every { facade.getExpirationDate(LicenseChecker.PRODUCT_CODE) } returns null
    }

    private fun advanceToLicensed() {
        every { facade.isEvaluationLicense } returns false
        every { facade.getConfirmationStamp(LicenseChecker.PRODUCT_CODE) } returns "eval:purchased-stamp"
        every { facade.getExpirationDate(LicenseChecker.PRODUCT_CODE) } returns null
    }

    // ---------- Full lifecycle simulation ----------

    @Test
    fun `full trial lifecycle from day 0 through post-expiry re-purchase`() {
        // Day 0: fresh install. Marketplace issues eval stamp. First licensed
        // check writes `lastKnownLicensedMs`, StartupLicenseHandler calls
        // enableProDefaults(). User gets Pro experience out of the box.
        advanceToTrialDay(0)
        assertTrue(LicenseChecker.isLicensedOrGrace(), "Day 0: eval stamp must be treated as licensed")
        assertEquals(30L, LicenseChecker.getTrialDaysRemaining())
        assertTrue(state.lastKnownLicensedMs > 0, "Day 0: licensed check must stamp lastKnownLicensedMs")

        LicenseChecker.enableProDefaults()
        assertTrue(state.everBeenPro, "Day 0: Pro activation must set everBeenPro latch")
        assertTrue(state.proDefaultsApplied)
        assertTrue(state.glowEnabled)
        assertEquals(100, state.sharpNeonIntensity)

        // Day 1..22: trial active, nothing notable. checkTrialExpiryWarning
        // must stay silent outside the 7-day window.
        for (day in listOf(1, 10, 15, 22)) {
            advanceToTrialDay(day)
            LicenseChecker.checkTrialExpiryWarning(null)
        }
        verify(exactly = 0) {
            notificationGroup.createNotification(any<String>(), any<String>(), any<NotificationType>())
        }
        assertFalse(state.trialExpiryWarningShown, "No warning before 7-day threshold")

        // Day 10: user disables glow manually (mid-trial customization).
        state.glowEnabled = false

        // Day 23: 7 days remaining. First warning fires and latches.
        advanceToTrialDay(23)
        assertEquals(7L, LicenseChecker.getTrialDaysRemaining())
        LicenseChecker.checkTrialExpiryWarning(null)
        assertTrue(state.trialExpiryWarningShown)
        verify(exactly = 1) {
            notificationGroup.createNotification(
                match { it.contains("7 days remaining") },
                any<String>(),
                NotificationType.INFORMATION,
            )
        }

        // Day 24-26: still within 7-day window but latch silences further warnings.
        for (day in listOf(24, 25, 26)) {
            advanceToTrialDay(day)
            LicenseChecker.checkTrialExpiryWarning(null)
        }
        // Still exactly one notification so far.
        verify(exactly = 1) {
            notificationGroup.createNotification(any<String>(), any<String>(), any<NotificationType>())
        }

        // Day 27: 3 days remaining, second warning fires.
        advanceToTrialDay(27)
        LicenseChecker.checkTrialExpiryWarning(null)
        assertTrue(state.trialExpiry3DayWarningShown)
        verify(exactly = 1) {
            notificationGroup.createNotification(
                match { it.contains("3 days remaining") },
                any<String>(),
                NotificationType.INFORMATION,
            )
        }

        // Day 28-29: both latched, silence.
        for (day in listOf(28, 29)) {
            advanceToTrialDay(day)
            LicenseChecker.checkTrialExpiryWarning(null)
        }
        verify(exactly = 2) {
            notificationGroup.createNotification(any<String>(), any<String>(), any<NotificationType>())
        }

        // Day 30: expires today. Marketplace still honors the stamp.
        advanceToTrialDay(30)
        assertEquals(0L, LicenseChecker.getTrialDaysRemaining())
        assertTrue(LicenseChecker.isLicensedOrGrace())

        // Day 31: eval stamp gone, facade still reachable. The 48 h offline
        // grace carries the user from their last licensed check on Day 30 into
        // a brief bonus window — they won't notice a lockout if they restart
        // the IDE the same day the stamp expires.
        advanceToPostTrial()
        val lastLicensedStamp = state.lastKnownLicensedMs
        assertTrue(lastLicensedStamp > 0)
        assertTrue(
            LicenseChecker.isLicensedOrGrace(),
            "Day 31 within 48 h grace: user must still see Pro",
        )

        // Day 31 + 49 h: grace expired. Move the deterministic clock 49 h past
        // the stamp so the grace-window check falls off the edge.
        state.lastKnownLicensedMs = fakeNowMs
        fakeNowMs += 49L * 3_600_000L
        assertFalse(
            LicenseChecker.isLicensedOrGrace(),
            "Day 31 + 49 h: grace window must close",
        )

        // StartupLicenseHandler would now invoke revertToFreeDefaults +
        // notifyTrialExpired. Simulate that.
        LicenseChecker.revertToFreeDefaults(AyuVariant.MIRAGE)
        assertFalse(state.glowEnabled, "revert keeps glow off")
        assertFalse(state.accentRotationEnabled)
        assertFalse(state.cgpIntegrationEnabled)
        LicenseChecker.notifyTrialExpired(null)
        state.trialExpiredNotified = true

        // Day 32 (restart same day): trialExpiredNotified latched → no duplicate
        // notification even if the startup handler is re-invoked.
        val notifyCountAfterDay31 = 3 // 7-day + 3-day + trial-expired = 3
        verify(exactly = notifyCountAfterDay31) {
            notificationGroup.createNotification(any<String>(), any<String>(), any<NotificationType>())
        }

        // Day 45: user purchases. We simulate what StartupLicenseHandler.applyLicensedDefaults
        // does — reset trialExpiredNotified + proDefaultsApplied, then enableProDefaults().
        advanceToLicensed()
        assertTrue(LicenseChecker.isLicensedOrGrace())
        state.trialExpiredNotified = false
        state.proDefaultsApplied = false
        state.premiumOnboardingShown = false
        LicenseChecker.enableProDefaults()

        // `everBeenPro` guard means enableProDefaults is a no-op for
        // customizations — glow stays off as it was at revert / Day 10.
        assertFalse(
            state.glowEnabled,
            "Re-purchase must not resurrect glow; everBeenPro gate preserves the revert outcome",
        )
        assertTrue(state.proDefaultsApplied, "enableProDefaults still sets the applied latch")
        assertTrue(state.everBeenPro)
    }

    // ---------- Focused per-step assertions ----------

    @Test
    fun `getTrialDaysRemaining returns 30 at the start of the trial`() {
        advanceToTrialDay(0)
        assertEquals(30L, LicenseChecker.getTrialDaysRemaining())
    }

    @Test
    fun `getTrialDaysRemaining returns zero on expiry day`() {
        advanceToTrialDay(30)
        assertEquals(0L, LicenseChecker.getTrialDaysRemaining())
    }

    @Test
    fun `post-trial stamp absence yields null trial days`() {
        advanceToPostTrial()
        assertNull(LicenseChecker.getTrialDaysRemaining())
    }

    @Test
    fun `seven-day warning fires exactly once even across many same-day checks`() {
        advanceToTrialDay(23) // 7 days remaining
        repeat(100) { LicenseChecker.checkTrialExpiryWarning(null) }
        verify(exactly = 1) {
            notificationGroup.createNotification(any<String>(), any<String>(), any<NotificationType>())
        }
    }

    @Test
    fun `three-day warning fires even if seven-day warning latched in a previous session`() {
        // Simulate a returning user who already saw the 7-day warning in a prior session.
        state.trialExpiryWarningShown = true
        advanceToTrialDay(27) // 3 days remaining
        LicenseChecker.checkTrialExpiryWarning(null)
        assertTrue(state.trialExpiry3DayWarningShown)
        verify(exactly = 1) {
            notificationGroup.createNotification(
                match { it.contains("3 days remaining") },
                any<String>(),
                NotificationType.INFORMATION,
            )
        }
    }

    @Test
    fun `isLicensedOrGrace stays true throughout the 30-day eval window`() {
        for (day in 0..30) {
            advanceToTrialDay(day)
            assertTrue(
                LicenseChecker.isLicensedOrGrace(),
                "Day $day: user must have Pro access throughout the trial",
            )
        }
    }

    @Test
    fun `lastKnownLicensedMs advances monotonically across consecutive licensed checks`() {
        advanceToTrialDay(0)
        LicenseChecker.isLicensedOrGrace()
        val first = state.lastKnownLicensedMs
        assertEquals(fakeNowMs, first, "First call pins the stamp to the fake clock")

        // Advance the fake clock by 5 ms instead of sleeping — keeps the test
        // deterministic and fast, and exercises the same monotonic-advance path.
        fakeNowMs += 5L
        advanceToTrialDay(1)
        LicenseChecker.isLicensedOrGrace()
        assertEquals(fakeNowMs, state.lastKnownLicensedMs, "Second call tracks the forward-moving clock")
        assertTrue(state.lastKnownLicensedMs > first, "Stamp must advance, not regress")
    }

    @Test
    fun `trial warning reuses the same state object across simulated sessions`() {
        // Day 23: 7-day warning.
        advanceToTrialDay(23)
        LicenseChecker.checkTrialExpiryWarning(null)
        val afterSession1 = state.trialExpiryWarningShown

        // Day 24 (new "session"): same state instance, latch persisted.
        advanceToTrialDay(24)
        LicenseChecker.checkTrialExpiryWarning(null)

        assertTrue(afterSession1, "Session 1 must latch the 7-day warning")
        assertTrue(state.trialExpiryWarningShown, "Session 2 must see the persisted latch")
    }

    @Test
    fun `re-purchase after grace expiry restores isLicensedOrGrace`() {
        advanceToTrialDay(0)
        LicenseChecker.isLicensedOrGrace()
        state.everBeenPro = true

        advanceToPostTrial()
        state.lastKnownLicensedMs = fakeNowMs
        fakeNowMs += 49L * 3_600_000L
        assertFalse(LicenseChecker.isLicensedOrGrace())

        advanceToLicensed()
        assertTrue(
            LicenseChecker.isLicensedOrGrace(),
            "Re-purchase must immediately restore licensed state",
        )
    }

    @Test
    fun `first licensed check on fresh install stamps lastKnownLicensedMs to a positive value`() {
        advanceToTrialDay(0)
        assertEquals(0L, state.lastKnownLicensedMs, "Precondition: fresh install has no stamp")
        LicenseChecker.isLicensedOrGrace()
        assertTrue(state.lastKnownLicensedMs > 0, "First licensed check writes a real timestamp")
        assertNotNull(LicenseChecker.getTrialDaysRemaining())
    }
}
