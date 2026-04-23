package dev.ayuislands.licensing

import com.intellij.openapi.application.ApplicationManager
import com.intellij.testFramework.LoggedErrorProcessor
import com.intellij.ui.LicensingFacade
import dev.ayuislands.accent.AccentApplicator
import dev.ayuislands.accent.AyuVariant
import dev.ayuislands.settings.AyuIslandsSettings
import dev.ayuislands.settings.AyuIslandsState
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import java.util.EnumSet
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
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

        // Mock the accent re-apply path so transition tests can assert that it fires
        // (on either direction) without spinning up AccentApplicator's platform deps.
        // Each test re-stubs AyuVariant.detect() when it needs to exercise the
        // null-variant silent-skip branch.
        mockkObject(AccentApplicator)
        every { AccentApplicator.applyForFocusedProject(any()) } returns "#FFCC66"
        mockkObject(AyuVariant.Companion)
        every { AyuVariant.detect() } returns AyuVariant.MIRAGE
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

        assertEquals(1, loggedErrors.size, "expected exactly one LOG.error for the glitched call")
        assertEquals("platform glitch", loggedErrors[0]?.message)
    }

    // ── Chrome-refresh on license transitions ───────────────────────────────
    //
    // AccentResolver short-circuits per-project/per-language overrides when the
    // user is unlicensed so chrome tinting falls back to the global accent. On
    // either transition direction (licensed↔unlicensed) the listener MUST
    // re-apply the accent so the chrome renderer picks up the fresh resolver
    // output. The initial notification (previous == null) must not fire the
    // re-apply — otherwise a routine startup notification spams a redundant
    // repaint on every launch.

    @Test
    fun `initial notification does not re-apply accent`() {
        // First notification records the baseline only. An apply here would fire
        // on every IDE startup (Topic listeners receive a synthetic initial
        // notification) and trigger a redundant chrome repaint.
        every { LicenseChecker.isLicensed() } returns true
        LicenseTransitionListener().licenseStateChanged(facade)

        verify(exactly = 0) { AccentApplicator.applyForFocusedProject(any()) }
    }

    @Test
    fun `licensed to unlicensed transition re-applies accent for chrome refresh`() {
        val listener = LicenseTransitionListener()
        // Baseline call: licensed. No apply expected.
        every { LicenseChecker.isLicensed() } returns true
        listener.licenseStateChanged(facade)
        verify(exactly = 0) { AccentApplicator.applyForFocusedProject(any()) }

        // Transition: licensed → unlicensed. Chrome must re-render using the
        // global accent because the resolver now short-circuits overrides.
        every { LicenseChecker.isLicensed() } returns false
        listener.licenseStateChanged(facade)

        verify(exactly = 1) { AccentApplicator.applyForFocusedProject(AyuVariant.MIRAGE) }
    }

    @Test
    fun `unlicensed to licensed transition re-applies accent AND rearms wizard`() {
        state.premiumOnboardingShown = true
        val listener = LicenseTransitionListener()

        // Baseline call: unlicensed. No apply expected on initial notification.
        every { LicenseChecker.isLicensed() } returns false
        listener.licenseStateChanged(facade)
        verify(exactly = 0) { AccentApplicator.applyForFocusedProject(any()) }

        // Transition: unlicensed → licensed. BOTH side effects fire:
        //   - wizard re-arm (premiumOnboardingShown flipped to false)
        //   - accent re-apply (overrides resolver now honors project/language rules)
        every { LicenseChecker.isLicensed() } returns true
        listener.licenseStateChanged(facade)

        assertFalse(
            state.premiumOnboardingShown,
            "unlicensed→licensed transition must re-arm premium wizard for next startup",
        )
        verify(exactly = 1) { AccentApplicator.applyForFocusedProject(AyuVariant.MIRAGE) }
    }

    @Test
    fun `licensed to licensed refresh does not re-apply accent (idempotent noise filter)`() {
        // Topic listeners emit refresh notifications without an actual state
        // change. Re-applying on every refresh would cause a chrome repaint loop
        // during license heartbeats. The `previous != isNowLicensed` guard must
        // suppress this path.
        val listener = LicenseTransitionListener()

        every { LicenseChecker.isLicensed() } returns true
        listener.licenseStateChanged(facade)
        listener.licenseStateChanged(facade)

        verify(exactly = 0) { AccentApplicator.applyForFocusedProject(any()) }
    }

    @Test
    fun `unlicensed to unlicensed refresh does not re-apply accent`() {
        // Mirror of the licensed→licensed noise guard: an unlicensed refresh
        // (e.g. trial keeps pinging the facade) must not trigger a repaint.
        val listener = LicenseTransitionListener()

        every { LicenseChecker.isLicensed() } returns false
        listener.licenseStateChanged(facade)
        listener.licenseStateChanged(facade)

        verify(exactly = 0) { AccentApplicator.applyForFocusedProject(any()) }
    }

    @Test
    fun `transition with null variant skips re-apply silently`() {
        // AyuVariant.detect() returns null when the active theme is not an Ayu
        // theme (the user is on a non-Ayu dark theme but still has a valid
        // Ayu license). The invokeLater body must short-circuit silently — no
        // crash, no apply call, no LOG.error spam.
        every { AyuVariant.detect() } returns null

        val listener = LicenseTransitionListener()
        every { LicenseChecker.isLicensed() } returns true
        listener.licenseStateChanged(facade)

        every { LicenseChecker.isLicensed() } returns false
        listener.licenseStateChanged(facade)

        verify(exactly = 0) { AccentApplicator.applyForFocusedProject(any()) }
    }
}
