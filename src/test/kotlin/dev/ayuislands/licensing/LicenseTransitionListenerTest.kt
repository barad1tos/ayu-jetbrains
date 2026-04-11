package dev.ayuislands.licensing

import com.intellij.openapi.application.ApplicationManager
import com.intellij.testFramework.LoggedErrorProcessor
import com.intellij.ui.LicensingFacade
import dev.ayuislands.settings.AyuIslandsSettings
import dev.ayuislands.settings.AyuIslandsState
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import java.util.EnumSet
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LicenseTransitionListenerTest {
    private lateinit var state: AyuIslandsState
    private val facade: LicensingFacade = mockk(relaxed = true)

    @BeforeTest
    fun setUp() {
        state = AyuIslandsState()
        val settingsMock = mockk<AyuIslandsSettings>()
        mockkObject(AyuIslandsSettings.Companion)
        every { AyuIslandsSettings.getInstance() } returns settingsMock
        every { settingsMock.state } returns state
        mockkObject(LicenseChecker)
        mockkStatic(ApplicationManager::class)
        val appMock = mockk<com.intellij.openapi.application.Application>(relaxed = true)
        every { ApplicationManager.getApplication() } returns appMock
        every { appMock.invokeLater(any()) } answers { firstArg<Runnable>().run() }
    }

    @AfterTest
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `first licensed notification does not flip flag (initial state)`() {
        state.premiumOnboardingShown = true
        every { LicenseChecker.isLicensed() } returns true

        LicenseTransitionListener().licenseStateChanged(facade)

        assertTrue(state.premiumOnboardingShown)
    }

    @Test
    fun `unlicensed to licensed transition flips flag to false`() {
        state.premiumOnboardingShown = true
        val listener = LicenseTransitionListener()

        every { LicenseChecker.isLicensed() } returns false
        listener.licenseStateChanged(facade)
        assertTrue(state.premiumOnboardingShown)

        every { LicenseChecker.isLicensed() } returns true
        listener.licenseStateChanged(facade)

        assertFalse(state.premiumOnboardingShown)
    }

    @Test
    fun `licensed to licensed refresh does not flip flag`() {
        state.premiumOnboardingShown = true
        val listener = LicenseTransitionListener()

        every { LicenseChecker.isLicensed() } returns true
        listener.licenseStateChanged(facade)
        listener.licenseStateChanged(facade)

        assertTrue(state.premiumOnboardingShown)
    }

    @Test
    fun `unlicensed notifications do not flip flag`() {
        state.premiumOnboardingShown = true
        every { LicenseChecker.isLicensed() } returns false

        LicenseTransitionListener().licenseStateChanged(facade)

        assertTrue(state.premiumOnboardingShown)
    }

    @Test
    fun `transition with flag already false is a no-op`() {
        state.premiumOnboardingShown = false
        val listener = LicenseTransitionListener()

        every { LicenseChecker.isLicensed() } returns false
        listener.licenseStateChanged(facade)
        every { LicenseChecker.isLicensed() } returns true
        listener.licenseStateChanged(facade)

        assertFalse(state.premiumOnboardingShown)
    }

    @Test
    fun `licensed to unlicensed revocation does not reset premiumOnboardingShown`() {
        // User revokes license mid-session. The listener must NOT flip the flag
        // — the premium wizard has already been shown, so re-arming it on
        // revocation would re-trigger the wizard after next startup.
        state.premiumOnboardingShown = true
        val listener = LicenseTransitionListener()

        every { LicenseChecker.isLicensed() } returns true
        listener.licenseStateChanged(facade)
        assertTrue(state.premiumOnboardingShown)

        every { LicenseChecker.isLicensed() } returns false
        listener.licenseStateChanged(facade)

        assertTrue(state.premiumOnboardingShown)
    }

    @Test
    fun `RuntimeException from LicenseChecker does not corrupt wasLicensed state`() {
        // Simulates a platform glitch: first call throws, second succeeds as the
        // actual unlicensed→licensed transition. The post-exception retry MUST
        // still flip the flag — the listener must not be left in a broken state.
        // The listener logs the failure via LOG.error, which TestLoggerFactory
        // promotes into an AssertionError — install a LoggedErrorProcessor to
        // suppress (but count) the promotion.
        state.premiumOnboardingShown = true
        val listener = LicenseTransitionListener()

        val loggedErrors = mutableListOf<Throwable?>()
        val processor =
            object : LoggedErrorProcessor() {
                override fun processError(
                    category: String,
                    message: String,
                    details: Array<out String>,
                    throwable: Throwable?,
                ): Set<Action> {
                    loggedErrors += throwable
                    return EnumSet.noneOf(Action::class.java)
                }
            }

        LoggedErrorProcessor.executeWith<RuntimeException>(processor) {
            every { LicenseChecker.isLicensed() } throws RuntimeException("platform glitch")
            listener.licenseStateChanged(facade)
            assertTrue(state.premiumOnboardingShown, "exception must not mutate the flag")

            every { LicenseChecker.isLicensed() } returns false
            listener.licenseStateChanged(facade)
            assertTrue(state.premiumOnboardingShown, "first successful call records baseline only")

            every { LicenseChecker.isLicensed() } returns true
            listener.licenseStateChanged(facade)
            assertFalse(state.premiumOnboardingShown, "transition must still fire after glitch recovery")
        }

        assertTrue(loggedErrors.size == 1, "expected exactly one LOG.error for the glitched call")
        assertTrue(loggedErrors[0]?.message == "platform glitch")
    }
}
